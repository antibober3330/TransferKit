"""Authenticated source-stream relay from an Android source to an Android receiver."""

from __future__ import annotations

import hashlib
import json
import math
import socket
from collections.abc import Callable
from pathlib import Path
from typing import Protocol

from ..contracts import CategoryId, ReasonCode, TransferManifest, TransferReceipt
from ..manifest import manifest_json_bytes, parse_manifest_json
from ..report import write_receipt_json
from ..integrity import DecodedChunk
from .usb_adb import (
    ACK_KIND_CHUNK,
    ACK_KIND_MANIFEST,
    ACK_STRUCT,
    CONTROL_VERSION,
    MANIFEST_HEADER,
    MANIFEST_MAGIC,
    ChunkAcknowledgement,
    TransportError,
)
from ..integrity import CHUNK_HEADER_SIZE, RECOMMENDED_CHUNK_SIZE, decode_chunk


class TargetTransport(Protocol):
    def send_manifest(self, manifest: TransferManifest) -> None: ...

    def send_chunk(self, frame: bytes): ...

    def finish_transfer(self) -> TransferReceipt: ...


ProgressCallback = Callable[[CategoryId, str, int, int], None]
ManifestCallback = Callable[[TransferManifest], None]
ChunkAckCallback = Callable[[CategoryId, str, DecodedChunk, ChunkAcknowledgement], None]


def _read_exact(connection: socket.socket, size: int) -> bytes:
    chunks: list[bytes] = []
    remaining = size
    while remaining:
        try:
            chunk = connection.recv(remaining)
        except (TimeoutError, OSError) as exc:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Source ADB stream was interrupted") from exc
        if not chunk:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Source ADB stream closed before the next frame")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def _read_source_manifest(connection: socket.socket) -> TransferManifest:
    try:
        header = _read_exact(connection, MANIFEST_HEADER.size)
        magic, version, payload_size = MANIFEST_HEADER.unpack(header)
        if magic != MANIFEST_MAGIC or version != CONTROL_VERSION:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "Source sent an invalid TKMF header")
        if payload_size > 64 * 1024 * 1024:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "Source manifest exceeds the protocol limit")
        payload = _read_exact(connection, payload_size)
        manifest = parse_manifest_json(payload)
        if manifest_json_bytes(manifest) != payload:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "Source manifest is not canonical JSON")
        return manifest
    except TransportError:
        raise
    except (ValueError, TypeError, json.JSONDecodeError) as exc:
        raise TransportError(ReasonCode.INVALID_MANIFEST, "Source manifest failed contract validation") from exc


def _ack_frame(acknowledgement, kind: int) -> bytes:
    if kind == ACK_KIND_MANIFEST:
        return ACK_STRUCT.pack(
            b"TKAK", CONTROL_VERSION, kind, acknowledgement.bytes,
            bytes(4), bytes(4), 0, 0, 0,
        )
    return ACK_STRUCT.pack(
        b"TKAK", CONTROL_VERSION, kind, acknowledgement.transfer_id.bytes,
        acknowledgement.category_id_hash, acknowledgement.item_id_hash,
        acknowledgement.chunk_index, acknowledgement.payload_crc32, acknowledgement.status,
    )


def relay_authenticated_source_stream(
    source_connection: socket.socket,
    target: TargetTransport,
    *,
    receipt_path: str | Path | None = None,
    chunk_size: int = RECOMMENDED_CHUNK_SIZE,
    on_progress: ProgressCallback | None = None,
    on_manifest: ManifestCallback | None = None,
    on_chunk_ack: ChunkAckCallback | None = None,
) -> TransferReceipt:
    """Relay an already-authenticated Android TKMF/TKCH stream to the target.

    A source ACK is sent only after the target has accepted and durably acknowledged
    the corresponding chunk. The caller owns both authenticated sockets/transports.
    """
    if not 1 <= chunk_size <= 8 * 1024 * 1024:
        raise ValueError("chunk_size must be between 1 byte and 8 MiB")
    manifest = _read_source_manifest(source_connection)
    if on_manifest is not None:
        on_manifest(manifest)
    target.send_manifest(manifest)
    try:
        source_connection.sendall(_ack_frame(manifest.transfer_id, ACK_KIND_MANIFEST))
    except OSError as exc:
        raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Could not acknowledge the source manifest") from exc

    total_bytes = sum(item.size for category in manifest.categories.values() for item in category.items)
    total_confirmed = 0
    for category_id, category in sorted(manifest.categories.items(), key=lambda entry: entry[0].value):
        for item in category.items:
            total_chunks = max(1, math.ceil(item.size / chunk_size))
            expected_category_hash = hashlib.sha256(category_id.value.encode("utf-8")).digest()[:4]
            expected_item_hash = hashlib.sha256(item.id.encode("utf-8")).digest()[:4]
            item_confirmed = 0
            for expected_index in range(total_chunks):
                header = _read_exact(source_connection, CHUNK_HEADER_SIZE)
                try:
                    payload_size = int.from_bytes(header[37:41], "big")
                    if payload_size > chunk_size:
                        raise TransportError(ReasonCode.INVALID_MANIFEST, "Source chunk exceeds the negotiated size")
                    frame = header + _read_exact(source_connection, payload_size)
                    decoded = decode_chunk(frame)
                except TransportError:
                    raise
                except (ValueError, TypeError) as exc:
                    raise TransportError(ReasonCode.CRC32_MISMATCH, "Source chunk failed frame or CRC validation") from exc

                chunk = decoded.header
                expected_payload = min(chunk_size, max(0, item.size - expected_index * chunk_size))
                if (
                    chunk.transfer_id != manifest.transfer_id
                    or chunk.category_id_hash != expected_category_hash
                    or chunk.item_id_hash != expected_item_hash
                    or chunk.chunk_index != expected_index
                    or chunk.total_chunks != total_chunks
                    or chunk.chunk_size != expected_payload
                    or len(decoded.payload) != expected_payload
                ):
                    raise TransportError(ReasonCode.INVALID_MANIFEST, "Source chunk does not match the manifest item order or size")

                acknowledgement = target.send_chunk(frame)
                if acknowledgement.status != 0:
                    raise TransportError(ReasonCode.TARGET_WRITE_FAILED, "Target did not durably accept a source chunk")
                if (
                    acknowledgement.transfer_id != chunk.transfer_id
                    or acknowledgement.category_id_hash != chunk.category_id_hash
                    or acknowledgement.item_id_hash != chunk.item_id_hash
                    or acknowledgement.chunk_index != chunk.chunk_index
                    or acknowledgement.payload_crc32 != chunk.payload_crc32
                ):
                    raise TransportError(ReasonCode.INVALID_MANIFEST, "Target ACK does not match the source chunk")
                if on_chunk_ack is not None:
                    on_chunk_ack(category_id, item.id, decoded, acknowledgement)
                try:
                    source_connection.sendall(_ack_frame(acknowledgement, ACK_KIND_CHUNK))
                except OSError as exc:
                    raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Could not forward the durable target ACK to the source") from exc
                item_confirmed += expected_payload
                total_confirmed += expected_payload
                if on_progress is not None:
                    on_progress(category_id, item.id, total_confirmed, total_bytes)

    receipt = target.finish_transfer()
    if receipt_path is not None:
        try:
            write_receipt_json(receipt, receipt_path)
        except OSError as exc:
            raise TransportError(ReasonCode.IO_ERROR, "Could not save the final target receipt") from exc
    return receipt
