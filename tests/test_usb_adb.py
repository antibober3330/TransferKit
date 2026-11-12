import json
import struct
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID
from unittest.mock import patch

from transferkit.contracts import DeviceInfo, ManifestCategory, ManifestItem, TransferManifest
from transferkit.integrity import CHUNK_HEADER_SIZE, decode_chunk, encode_chunk, sha256_bytes
from transferkit.transport.usb_adb import (
    ACK_KIND_CHUNK,
    ACK_KIND_MANIFEST,
    ACK_MAGIC,
    ACK_CRC32_MISMATCH,
    ACK_INVALID_MANIFEST,
    ACK_OK,
    ACK_STRUCT,
    FINISH_REQUEST_STRUCT,
    CONTROL_VERSION,
    MANIFEST_HEADER,
    MANIFEST_MAGIC,
    RECEIPT_RESPONSE_HEADER,
    UsbAdbTransport,
)
from transferkit.transport.usb_adb import TransportError
from transferkit.contracts import ReasonCode


TRANSFER_ID = UUID("00000000-0000-4000-8000-000000000001")


class MemorySocket:
    """In-memory peer for exercising the wire protocol without loopback sockets."""

    def __init__(
        self,
        *,
        manifest_ack_status: int = ACK_OK,
        manifest_ack_transfer_id: UUID = TRANSFER_ID,
        chunk_ack_status: int = ACK_OK,
        chunk_ack_transfer_id: UUID | None = None,
        chunk_ack_index: int | None = None,
        manifest_ack_enabled: bool = True,
        chunk_ack_enabled: bool = True,
        finish_status: int = 0,
        receipt_transfer_id: UUID | None = None,
    ) -> None:
        self.outgoing = bytearray()
        self.responses = bytearray()
        self.manifest_accepted = False
        self.closed = False
        self.manifest_ack_status = manifest_ack_status
        self.manifest_ack_transfer_id = manifest_ack_transfer_id
        self.chunk_ack_status = chunk_ack_status
        self.chunk_ack_transfer_id = chunk_ack_transfer_id
        self.chunk_ack_index = chunk_ack_index
        self.manifest_ack_enabled = manifest_ack_enabled
        self.chunk_ack_enabled = chunk_ack_enabled
        self.received_payload = bytearray()
        self.received_chunk_indexes: list[int] = []
        self.finish_status = finish_status
        self.receipt_transfer_id = receipt_transfer_id or TRANSFER_ID
        self.receipt_payload = json.dumps(
            {
                "version": "1.0",
                "transfer_id": str(TRANSFER_ID),
                "received_at": "2026-11-11T12:00:00Z",
                "categories": {
                    "photos": {
                        "items_received": 1,
                        "items_verified": 1,
                        "items_failed": 0,
                        "failures": [],
                    },
                },
            },
            separators=(",", ":"),
        ).encode("utf-8")

    def sendall(self, data: bytes) -> None:
        self.outgoing.extend(data)
        if not self.manifest_accepted:
            if len(self.outgoing) < MANIFEST_HEADER.size:
                return
            magic, version, payload_size = MANIFEST_HEADER.unpack_from(self.outgoing)
            if magic != MANIFEST_MAGIC or version != CONTROL_VERSION:
                raise AssertionError("invalid manifest frame header")
            frame_size = MANIFEST_HEADER.size + payload_size
            if len(self.outgoing) < frame_size:
                return
            document = json.loads(self.outgoing[MANIFEST_HEADER.size:frame_size])
            transfer_id = UUID(document["transfer_id"])
            if transfer_id != TRANSFER_ID:
                raise AssertionError("unexpected transfer ID in manifest")
            del self.outgoing[:frame_size]
            if self.manifest_ack_enabled:
                self.responses.extend(
                    ACK_STRUCT.pack(
                        ACK_MAGIC,
                        CONTROL_VERSION,
                        ACK_KIND_MANIFEST,
                        self.manifest_ack_transfer_id.bytes,
                        b"\0" * 4,
                        b"\0" * 4,
                        0,
                        0,
                        self.manifest_ack_status,
                    )
                )
            self.manifest_accepted = True

        if self.outgoing.startswith(b"TKFN"):
            if len(self.outgoing) < FINISH_REQUEST_STRUCT.size:
                return
            magic, version, transfer_raw = FINISH_REQUEST_STRUCT.unpack_from(self.outgoing)
            if magic != b"TKFN" or version != CONTROL_VERSION or transfer_raw != TRANSFER_ID.bytes:
                raise AssertionError("invalid finalization request")
            del self.outgoing[: FINISH_REQUEST_STRUCT.size]
            payload = self.receipt_payload if self.finish_status == 0 else b""
            self.responses.extend(
                RECEIPT_RESPONSE_HEADER.pack(
                    b"TKRC",
                    CONTROL_VERSION,
                    self.finish_status,
                    self.receipt_transfer_id.bytes,
                    len(payload),
                ) + payload
            )
            return

        while len(self.outgoing) >= CHUNK_HEADER_SIZE:
            chunk_size = struct.unpack_from(">I", self.outgoing, 37)[0]
            frame_size = CHUNK_HEADER_SIZE + chunk_size
            if len(self.outgoing) < frame_size:
                return
            decoded = decode_chunk(bytes(self.outgoing[:frame_size]))
            self.received_payload.extend(decoded.payload)
            self.received_chunk_indexes.append(decoded.header.chunk_index)
            del self.outgoing[:frame_size]
            if self.chunk_ack_enabled:
                self.responses.extend(
                    ACK_STRUCT.pack(
                        ACK_MAGIC,
                        CONTROL_VERSION,
                        ACK_KIND_CHUNK,
                        (self.chunk_ack_transfer_id or decoded.header.transfer_id).bytes,
                        decoded.header.category_id_hash,
                        decoded.header.item_id_hash,
                        self.chunk_ack_index if self.chunk_ack_index is not None else decoded.header.chunk_index,
                        decoded.header.payload_crc32,
                        self.chunk_ack_status,
                    )
                )

    def recv(self, size: int) -> bytes:
        if not self.responses:
            return b""
        result = bytes(self.responses[:size])
        del self.responses[:size]
        return result

    def shutdown(self, _how: int) -> None:
        self.closed = True

    def close(self) -> None:
        self.closed = True


def sample_manifest(payload: bytes = b"abc", *, item_sha256: str | None = None) -> TransferManifest:
    return TransferManifest(
        version="1.0",
        transfer_id=TRANSFER_ID,
        created_at=datetime.now(timezone.utc),
        source_device=DeviceInfo(
            manufacturer="Example",
            model="Source",
            sdk=34,
            fingerprint="example/source",
        ),
        categories={
            "photos": ManifestCategory(
                provider="MediaStore",
                method="ContentResolver",
                items=[
                    ManifestItem(
                        id="photo-1",
                        size=len(payload),
                        sha256=item_sha256 or sha256_bytes(payload),
                        metadata={},
                    )
                ],
            )
        },
    )


class UsbAdbTransportTests(unittest.TestCase):
    def test_manifest_and_chunk_frames_receive_matching_acks(self) -> None:
        manifest = sample_manifest()
        connection = MemorySocket()
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        try:
            transport.send_manifest(manifest)
            frame = encode_chunk(TRANSFER_ID, "photos", "photo-1", 0, 1, b"abc")
            acknowledgement = transport.send_chunk(frame)
            self.assertEqual(acknowledgement.chunk_index, 0)
            self.assertEqual(acknowledgement.transfer_id, TRANSFER_ID)
        finally:
            transport.close()
        self.assertTrue(connection.closed)

    def test_send_file_streams_and_hashes_source_in_one_read(self) -> None:
        payload = b"multiple chunks of source data"
        manifest = sample_manifest(payload)
        connection = MemorySocket()
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            source = Path(folder) / "video.bin"
            source.write_bytes(payload)
            original_open = Path.open
            source_open_calls = 0
            acknowledgements = []

            def counted_open(path, *args, **kwargs):
                nonlocal source_open_calls
                if Path(path) == source:
                    source_open_calls += 1
                return original_open(path, *args, **kwargs)

            try:
                transport.send_manifest(manifest)
                with patch.object(Path, "open", counted_open):
                    sent = transport.send_file(
                        manifest,
                        "photos",
                        "photo-1",
                        source,
                        len(payload),
                        chunk_size=5,
                        on_chunk_ack=lambda ack, confirmed: acknowledgements.append(
                            (ack.chunk_index, confirmed)
                        ),
                    )
            finally:
                transport.close()
        self.assertEqual(sent, 6)
        self.assertEqual(source_open_calls, 1)
        self.assertEqual(connection.received_payload, payload)
        self.assertEqual(
            acknowledgements,
            [(index, min(len(payload), (index + 1) * 5)) for index in range(6)],
        )

    def test_send_file_reports_hash_mismatch_after_streaming(self) -> None:
        payload = b"changed source"
        manifest = sample_manifest(payload, item_sha256="0" * 64)
        connection = MemorySocket()
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            source = Path(folder) / "photo.bin"
            source.write_bytes(payload)
            try:
                transport.send_manifest(manifest)
                with self.assertRaises(TransportError) as caught:
                    transport.send_file(manifest, "photos", "photo-1", source, len(payload))
            finally:
                transport.close()
        self.assertEqual(caught.exception.reason, ReasonCode.SHA256_MISMATCH)
        self.assertEqual(connection.received_payload, payload)

    def test_send_file_resumes_from_first_unconfirmed_chunk(self) -> None:
        payload = b"0123456789abcdefghijkl"
        start_chunk = 2
        chunk_size = 5
        manifest = sample_manifest(payload)
        connection = MemorySocket()
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        acknowledgements = []
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            source = Path(folder) / "resume.bin"
            source.write_bytes(payload)
            try:
                transport.send_manifest(manifest)
                sent = transport.send_file(
                    manifest,
                    "photos",
                    "photo-1",
                    source,
                    len(payload),
                    chunk_size=chunk_size,
                    start_chunk=start_chunk,
                    on_chunk_ack=lambda ack, confirmed: acknowledgements.append(
                        (ack.chunk_index, confirmed)
                    ),
                )
            finally:
                transport.close()
        self.assertEqual(sent, 3)
        self.assertEqual(connection.received_chunk_indexes, [2, 3, 4])
        self.assertEqual(connection.received_payload, payload[start_chunk * chunk_size :])
        self.assertEqual(acknowledgements, [(2, 15), (3, 20), (4, len(payload))])

    def test_chunk_ack_crc_failure_maps_to_specific_reason(self) -> None:
        connection = MemorySocket(chunk_ack_status=ACK_CRC32_MISMATCH)
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        try:
            transport.send_manifest(sample_manifest())
            with self.assertRaises(TransportError) as caught:
                transport.send_chunk(encode_chunk(TRANSFER_ID, "photos", "photo-1", 0, 1, b"abc"))
        finally:
            transport.close()
        self.assertEqual(caught.exception.reason, ReasonCode.CRC32_MISMATCH)

    def test_chunk_ack_transfer_mismatch_is_rejected(self) -> None:
        wrong_id = UUID("00000000-0000-4000-8000-000000000002")
        connection = MemorySocket(chunk_ack_transfer_id=wrong_id)
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        try:
            transport.send_manifest(sample_manifest())
            with self.assertRaises(TransportError) as caught:
                transport.send_chunk(encode_chunk(TRANSFER_ID, "photos", "photo-1", 0, 1, b"abc"))
        finally:
            transport.close()
        self.assertEqual(caught.exception.reason, ReasonCode.INVALID_MANIFEST)

    def test_manifest_ack_transfer_mismatch_is_rejected(self) -> None:
        wrong_id = UUID("00000000-0000-4000-8000-000000000002")
        connection = MemorySocket(manifest_ack_transfer_id=wrong_id)
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        try:
            with self.assertRaises(TransportError) as caught:
                transport.send_manifest(sample_manifest())
        finally:
            transport.close()
        self.assertEqual(caught.exception.reason, ReasonCode.INVALID_MANIFEST)

    def test_missing_chunk_ack_is_reported_as_interruption(self) -> None:
        connection = MemorySocket(chunk_ack_enabled=False)
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        try:
            transport.send_manifest(sample_manifest())
            with self.assertRaises(TransportError) as caught:
                transport.send_chunk(encode_chunk(TRANSFER_ID, "photos", "photo-1", 0, 1, b"abc"))
        finally:
            transport.close()
        self.assertEqual(caught.exception.reason, ReasonCode.TRANSPORT_INTERRUPTED)

    def test_invalid_manifest_ack_status_is_mapped(self) -> None:
        connection = MemorySocket(manifest_ack_status=ACK_INVALID_MANIFEST)
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        try:
            with self.assertRaises(TransportError) as caught:
                transport.send_manifest(sample_manifest())
        finally:
            transport.close()
        self.assertEqual(caught.exception.reason, ReasonCode.INVALID_MANIFEST)

    def test_finish_transfer_returns_schema_validated_receipt(self) -> None:
        connection = MemorySocket()
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        try:
            transport.send_manifest(sample_manifest())
            receipt = transport.finish_transfer()
        finally:
            transport.close()
        self.assertEqual(receipt.transfer_id, TRANSFER_ID)
        self.assertEqual(receipt.categories["photos"].items_verified, 1)

    def test_finish_transfer_reports_incomplete_target_receipt(self) -> None:
        connection = MemorySocket(finish_status=1)
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        try:
            transport.send_manifest(sample_manifest())
            with self.assertRaises(TransportError) as caught:
                transport.finish_transfer()
        finally:
            transport.close()
        self.assertEqual(caught.exception.reason, ReasonCode.TRANSFER_INCOMPLETE)

    def test_finish_transfer_rejects_receipt_for_another_transfer(self) -> None:
        other_id = UUID("00000000-0000-4000-8000-000000000099")
        connection = MemorySocket(receipt_transfer_id=other_id)
        transport = UsbAdbTransport(object(), "TEST123")
        transport.connection = connection
        try:
            transport.send_manifest(sample_manifest())
            with self.assertRaises(TransportError) as caught:
                transport.finish_transfer()
        finally:
            transport.close()
        self.assertEqual(caught.exception.reason, ReasonCode.INVALID_MANIFEST)


if __name__ == "__main__":
    unittest.main()
