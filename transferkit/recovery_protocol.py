"""Bounded, canonical JSON framing for the Recovery control channel.

This module only frames Recovery documents. It does not authenticate a peer or
authorize a ResumePlan; callers must validate the document contract and proof.
"""

from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from enum import StrEnum
from typing import BinaryIO

from .contracts import canonical_json_bytes


FRAME_VERSION = 1
MAX_RECOVERY_PAYLOAD_BYTES = 256 * 1024
_HEADER = struct.Struct(">4sBI")


class RecoveryFrameKind(StrEnum):
    HELLO = "TKRH"
    CHALLENGE = "TKRQ"
    PROOF = "TKRP"
    ACCEPT = "TKRA"
    BLOCKED = "TKRB"
    CHECKPOINT = "TKCP"
    PLAN = "TKPL"


@dataclass(frozen=True, slots=True)
class RecoveryFrame:
    kind: RecoveryFrameKind
    document: dict[str, object]


def encode_recovery_frame(kind: RecoveryFrameKind, document: dict[str, object]) -> bytes:
    if not isinstance(document, dict):
        raise TypeError("Recovery frame payload must be a JSON object")
    payload = canonical_json_bytes(document)
    _validate_payload(payload)
    return _HEADER.pack(kind.value.encode("ascii"), FRAME_VERSION, len(payload)) + payload


def decode_recovery_frame(frame: bytes, *, expected_kind: RecoveryFrameKind | None = None) -> RecoveryFrame:
    if len(frame) < _HEADER.size:
        raise ValueError("Recovery frame is shorter than its header")
    magic, version, payload_size = _HEADER.unpack_from(frame)
    if version != FRAME_VERSION:
        raise ValueError(f"Unsupported Recovery frame version: {version}")
    try:
        kind = RecoveryFrameKind(magic.decode("ascii"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ValueError("Unknown Recovery frame magic") from exc
    if expected_kind is not None and kind != expected_kind:
        raise ValueError("Unexpected Recovery frame kind")
    if payload_size == 0 or payload_size > MAX_RECOVERY_PAYLOAD_BYTES:
        raise ValueError("Recovery payload size is outside the allowed range")
    if len(frame) != _HEADER.size + payload_size:
        raise ValueError("Recovery frame length does not match payload_size")
    payload = frame[_HEADER.size:]
    document = _validate_payload(payload)
    return RecoveryFrame(kind=kind, document=document)


def read_recovery_frame(
    stream: BinaryIO,
    *,
    expected_kind: RecoveryFrameKind | None = None,
) -> RecoveryFrame:
    header = _read_exact(stream, _HEADER.size)
    magic, version, payload_size = _HEADER.unpack(header)
    if version != FRAME_VERSION:
        raise ValueError(f"Unsupported Recovery frame version: {version}")
    if payload_size == 0 or payload_size > MAX_RECOVERY_PAYLOAD_BYTES:
        raise ValueError("Recovery payload size is outside the allowed range")
    payload = _read_exact(stream, payload_size)
    return decode_recovery_frame(header + payload, expected_kind=expected_kind)


def _validate_payload(payload: bytes) -> dict[str, object]:
    if not payload or len(payload) > MAX_RECOVERY_PAYLOAD_BYTES:
        raise ValueError("Recovery payload size is outside the allowed range")
    try:
        document = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("Recovery payload must be valid UTF-8 JSON") from exc
    if not isinstance(document, dict):
        raise ValueError("Recovery payload must be a JSON object")
    if canonical_json_bytes(document) != payload:
        raise ValueError("Recovery payload must use canonical JSON encoding")
    return document


def _read_exact(stream: BinaryIO, size: int) -> bytes:
    result = bytearray()
    while len(result) < size:
        part = stream.read(size - len(result))
        if not part:
            raise EOFError("Stream ended before the Recovery frame was complete")
        result.extend(part)
    return bytes(result)
