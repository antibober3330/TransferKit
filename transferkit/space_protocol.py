"""Versioned TKSP/TKSD control-frame codecs for Space Preflight negotiation."""

from __future__ import annotations

import json
import struct
from typing import BinaryIO

from .contracts import SpaceDecision, SpaceReport, canonical_json_bytes, validate_document


SPACE_FRAME_VERSION = 1
SPACE_PROTOCOL_VERSION = "2"
SPACE_FRAME_HEADER = struct.Struct(">4sBI")
MAX_SPACE_FRAME_BYTES = 64 * 1024 * 1024
SPACE_REPORT_MAGIC = b"TKSP"
SPACE_DECISION_MAGIC = b"TKSD"


def encode_space_report_frame(report: SpaceReport | dict) -> bytes:
    return _encode_frame(SPACE_REPORT_MAGIC, report, "space-report.schema.json")


def encode_space_decision_frame(decision: SpaceDecision | dict) -> bytes:
    return _encode_frame(SPACE_DECISION_MAGIC, decision, "space-decision.schema.json")


def decode_space_report_frame(frame: bytes) -> SpaceReport:
    return _decode_frame(frame, SPACE_REPORT_MAGIC, "space-report.schema.json", SpaceReport)


def decode_space_decision_frame(frame: bytes) -> SpaceDecision:
    return _decode_frame(frame, SPACE_DECISION_MAGIC, "space-decision.schema.json", SpaceDecision)


def read_space_report_frame(stream: BinaryIO) -> SpaceReport:
    return decode_space_report_frame(_read_frame(stream, SPACE_REPORT_MAGIC))


def read_space_decision_frame(stream: BinaryIO) -> SpaceDecision:
    return decode_space_decision_frame(_read_frame(stream, SPACE_DECISION_MAGIC))


def _encode_frame(magic: bytes, value, schema_name: str) -> bytes:
    document = value.model_dump(mode="python") if isinstance(value, (SpaceReport, SpaceDecision)) else value
    validated = validate_document(document, schema_name)
    if validated is None:
        raise ValueError("Space frame schema has no semantic model")
    payload = canonical_json_bytes(validated)
    if not payload or len(payload) > MAX_SPACE_FRAME_BYTES:
        raise ValueError("Space frame payload exceeds the 64 MiB limit")
    return SPACE_FRAME_HEADER.pack(magic, SPACE_FRAME_VERSION, len(payload)) + payload


def _decode_frame(frame: bytes, expected_magic: bytes, schema_name: str, model_type):
    if len(frame) < SPACE_FRAME_HEADER.size:
        raise ValueError("Space frame is shorter than its header")
    magic, version, payload_size = SPACE_FRAME_HEADER.unpack_from(frame)
    if magic != expected_magic:
        raise ValueError("Unexpected Space frame magic")
    if version != SPACE_FRAME_VERSION:
        raise ValueError(f"Unsupported Space frame version: {version}")
    if payload_size == 0 or payload_size > MAX_SPACE_FRAME_BYTES:
        raise ValueError("Space frame payload size is outside the allowed range")
    if len(frame) != SPACE_FRAME_HEADER.size + payload_size:
        raise ValueError("Space frame length does not match payload_size")
    payload = frame[SPACE_FRAME_HEADER.size :]
    try:
        document = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("Space frame payload must be UTF-8 JSON") from exc
    if not isinstance(document, dict):
        raise ValueError("Space frame payload must be a JSON object")
    if canonical_json_bytes(document) != payload:
        raise ValueError("Space frame payload must use canonical JSON encoding")
    validated = validate_document(document, schema_name)
    if not isinstance(validated, model_type):
        raise ValueError("Space frame schema returned an unexpected semantic model")
    if validated.protocol_version != SPACE_PROTOCOL_VERSION:
        raise ValueError("Unsupported Space negotiation protocol version")
    return validated


def _read_frame(stream: BinaryIO, expected_magic: bytes) -> bytes:
    header = _read_exact(stream, SPACE_FRAME_HEADER.size)
    magic, version, payload_size = SPACE_FRAME_HEADER.unpack(header)
    if magic != expected_magic:
        raise ValueError("Unexpected Space frame magic")
    if version != SPACE_FRAME_VERSION:
        raise ValueError(f"Unsupported Space frame version: {version}")
    if payload_size == 0 or payload_size > MAX_SPACE_FRAME_BYTES:
        raise ValueError("Space frame payload size is outside the allowed range")
    return header + _read_exact(stream, payload_size)


def _read_exact(stream: BinaryIO, size: int) -> bytes:
    parts: list[bytes] = []
    remaining = size
    while remaining:
        chunk = stream.read(remaining)
        if not chunk:
            raise EOFError("Stream ended before the Space frame was complete")
        parts.append(chunk)
        remaining -= len(chunk)
    return b"".join(parts)
