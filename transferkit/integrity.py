"""Streaming hashes and the shared TransferKit chunk frame codec."""

from __future__ import annotations

import hashlib
import hmac
import re
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Callable, Iterator
from uuid import UUID

from .contracts import ChunkHeader, CategoryId


CHUNK_MAGIC = b"TKCH"
CHUNK_VERSION = 1
RECOMMENDED_CHUNK_SIZE = 4 * 1024 * 1024
MAX_CHUNK_SIZE = 8 * 1024 * 1024
CHUNK_HEADER = struct.Struct(">4sB16s4s4sIIII")
CHUNK_HEADER_SIZE = CHUNK_HEADER.size
_SHA256_HEX_RE = re.compile(r"[a-f0-9]{64}\Z")

if CHUNK_HEADER_SIZE != 45:  # guard against accidental wire-contract drift
    raise RuntimeError(f"invalid TransferKit chunk header size: {CHUNK_HEADER_SIZE}")


class ChunkError(ValueError):
    """A malformed or corrupted chunk frame."""


class SourceChangedError(IOError):
    """The source stream no longer matches its manifest size while chunking."""


@dataclass(frozen=True, slots=True)
class DecodedChunk:
    header: ChunkHeader
    payload: bytes


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_stream(stream: BinaryIO, block_size: int = 1024 * 1024) -> str:
    if block_size <= 0:
        raise ValueError("block_size must be positive")
    digest = hashlib.sha256()
    while True:
        block = stream.read(block_size)
        if not block:
            break
        digest.update(block)
    return digest.hexdigest()


def sha256_file(path: str | Path, block_size: int = 1024 * 1024) -> str:
    with Path(path).open("rb") as source:
        return sha256_stream(source, block_size=block_size)


def verify_sha256(data: bytes, expected_hex: str) -> bool:
    if not isinstance(expected_hex, str) or _SHA256_HEX_RE.fullmatch(expected_hex) is None:
        return False
    actual = sha256_bytes(data)
    return hmac.compare_digest(actual, expected_hex)


def crc32_bytes(data: bytes) -> int:
    return zlib.crc32(data) & 0xFFFFFFFF


def id_hash(value: str) -> bytes:
    return hashlib.sha256(value.encode("utf-8")).digest()[:4]


def encode_chunk(
    transfer_id: UUID | str,
    category_id: CategoryId | str,
    item_id: str,
    chunk_index: int,
    total_chunks: int,
    payload: bytes,
) -> bytes:
    transfer_uuid = transfer_id if isinstance(transfer_id, UUID) else UUID(str(transfer_id))
    category_text = category_id.value if isinstance(category_id, CategoryId) else str(category_id)
    try:
        CategoryId(category_text)
    except ValueError as exc:
        raise ChunkError(f"unknown category ID: {category_text}") from exc
    if not item_id:
        raise ChunkError("item_id must not be empty")
    if len(payload) > MAX_CHUNK_SIZE:
        raise ChunkError("payload exceeds 8 MiB maximum")
    if total_chunks < 1 or total_chunks > 0xFFFFFFFF:
        raise ChunkError("total_chunks is out of range")
    if chunk_index < 0 or chunk_index >= total_chunks:
        raise ChunkError("chunk_index must be within total_chunks")
    header = ChunkHeader(
        version=CHUNK_VERSION,
        transfer_id=transfer_uuid,
        category_id_hash=id_hash(category_text),
        item_id_hash=id_hash(item_id),
        chunk_index=chunk_index,
        total_chunks=total_chunks,
        chunk_size=len(payload),
        payload_crc32=crc32_bytes(payload),
    )
    encoded_header = CHUNK_HEADER.pack(
        CHUNK_MAGIC,
        header.version,
        header.transfer_id.bytes,
        header.category_id_hash,
        header.item_id_hash,
        header.chunk_index,
        header.total_chunks,
        header.chunk_size,
        header.payload_crc32,
    )
    return encoded_header + payload


def decode_chunk(frame: bytes) -> DecodedChunk:
    if len(frame) < CHUNK_HEADER_SIZE:
        raise ChunkError("frame is shorter than the 45-byte header")
    (
        magic,
        version,
        transfer_bytes,
        category_hash,
        item_hash,
        chunk_index,
        total_chunks,
        chunk_size,
        payload_crc,
    ) = CHUNK_HEADER.unpack_from(frame)
    if magic != CHUNK_MAGIC:
        raise ChunkError("invalid chunk magic")
    if version != CHUNK_VERSION:
        raise ChunkError(f"unsupported chunk version: {version}")
    if chunk_size > MAX_CHUNK_SIZE:
        raise ChunkError("payload exceeds 8 MiB maximum")
    if len(frame) != CHUNK_HEADER_SIZE + chunk_size:
        raise ChunkError("frame length does not match chunk_size")
    try:
        header = ChunkHeader(
            version=version,
            transfer_id=UUID(bytes=transfer_bytes),
            category_id_hash=category_hash,
            item_id_hash=item_hash,
            chunk_index=chunk_index,
            total_chunks=total_chunks,
            chunk_size=chunk_size,
            payload_crc32=payload_crc,
        )
    except Exception as exc:
        raise ChunkError(f"invalid chunk header: {exc}") from exc
    payload = frame[CHUNK_HEADER_SIZE:]
    if crc32_bytes(payload) != header.payload_crc32:
        raise ChunkError("CRC32 mismatch")
    return DecodedChunk(header=header, payload=payload)


def _read_up_to(stream: BinaryIO, requested: int) -> bytes:
    pieces: list[bytes] = []
    remaining = requested
    while remaining:
        piece = stream.read(remaining)
        if not piece:
            break
        pieces.append(piece)
        remaining -= len(piece)
    return b"".join(pieces)


def iter_file_chunks(
    path: str | Path,
    transfer_id: UUID | str,
    category_id: CategoryId | str,
    item_id: str,
    expected_size: int,
    chunk_size: int = RECOMMENDED_CHUNK_SIZE,
    start_chunk: int = 0,
    on_payload: Callable[[bytes], None] | None = None,
) -> Iterator[bytes]:
    if expected_size < 0:
        raise ValueError("expected_size must not be negative")
    if chunk_size < 1 or chunk_size > MAX_CHUNK_SIZE:
        raise ValueError("chunk_size must be between 1 byte and 8 MiB")
    total_chunks = max(1, (expected_size + chunk_size - 1) // chunk_size)
    if start_chunk < 0 or start_chunk > total_chunks:
        raise ValueError("start_chunk must be within the item's chunk range")
    remaining = expected_size
    with Path(path).open("rb") as source:
        for index in range(total_chunks):
            expected_payload_size = min(chunk_size, remaining)
            payload = _read_up_to(source, expected_payload_size)
            if len(payload) != expected_payload_size:
                raise SourceChangedError("source file ended before manifest size")
            if on_payload is not None:
                on_payload(payload)
            if index >= start_chunk:
                yield encode_chunk(
                    transfer_id,
                    category_id,
                    item_id,
                    index,
                    total_chunks,
                    payload,
                )
            remaining -= expected_payload_size
        if source.read(1):
            raise SourceChangedError("source file is larger than manifest size")
