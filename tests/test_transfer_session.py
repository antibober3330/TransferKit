import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID
from unittest.mock import patch

from transferkit.contracts import (
    CategoryId,
    CategoryReceipt,
    DeviceInfo,
    ManifestCategory,
    ManifestItem,
    TransferManifest,
    TransferReceipt,
)
from transferkit.device_detect import DeviceSnapshot
from transferkit.history import TransferHistory
from transferkit.integrity import decode_chunk, encode_chunk, sha256_bytes
from transferkit.manifest import manifest_json_bytes
from transferkit.transport.relay import MANIFEST_HEADER, MANIFEST_MAGIC
from transferkit.transport.session import run_authenticated_usb_adb_transfer
from transferkit.transport.usb_adb import CONTROL_VERSION, ChunkAcknowledgement, TransportError


TRANSFER_ID = UUID("00000000-0000-4000-8000-000000000001")
PAYLOAD = b"source-to-target session"


class MemoryConnection:
    def __init__(self, incoming: bytes):
        self.incoming = bytearray(incoming)
        self.outgoing = bytearray()

    def recv(self, size: int) -> bytes:
        data = bytes(self.incoming[:size])
        del self.incoming[:size]
        return data

    def sendall(self, data: bytes) -> None:
        self.outgoing.extend(data)


class FakeSourceTransport:
    def __init__(self, connection: MemoryConnection):
        self.connection = connection
        self.transfer_id = TRANSFER_ID

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def open(self):
        return TRANSFER_ID


class FakeTargetTransport:
    def __init__(self):
        self.manifest = None
        self.frames = []

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def open(self):
        return None

    def send_manifest(self, manifest):
        self.manifest = manifest

    def send_chunk(self, frame):
        decoded = decode_chunk(frame)
        self.frames.append(frame)
        header = decoded.header
        return ChunkAcknowledgement(
            transfer_id=header.transfer_id,
            category_id_hash=header.category_id_hash,
            item_id_hash=header.item_id_hash,
            chunk_index=header.chunk_index,
            payload_crc32=header.payload_crc32,
            status=0,
        )

    def finish_transfer(self):
        return TransferReceipt(
            version="1.0",
            transfer_id=TRANSFER_ID,
            received_at=datetime.now(timezone.utc),
            categories={
                CategoryId.DOCUMENTS: CategoryReceipt(
                    items_received=1,
                    items_verified=1,
                    items_failed=0,
                )
            },
        )


class FakeAdb:
    def __init__(self, source_fingerprint="example/source"):
        self.devices = {
            "SOURCE": snapshot("SOURCE", source_fingerprint),
            "TARGET": snapshot("TARGET", "example/target"),
        }

    def discover(self, serial):
        return self.devices[serial]


def snapshot(serial: str, fingerprint: str) -> DeviceSnapshot:
    return DeviceSnapshot(
        serial=serial,
        info=DeviceInfo(manufacturer="Example", model=serial, sdk=34, fingerprint=fingerprint),
        discovered_at=datetime.now(timezone.utc),
    )


def sample_manifest() -> TransferManifest:
    return TransferManifest(
        version="1.0",
        transfer_id=TRANSFER_ID,
        created_at=datetime.now(timezone.utc),
        source_device=DeviceInfo(
            manufacturer="Example", model="SOURCE", sdk=34, fingerprint="example/source"
        ),
        categories={
            CategoryId.DOCUMENTS: ManifestCategory(
                provider="TestProvider",
                method="GeneratedFixture",
                items=[
                    ManifestItem(
                        id="doc-1",
                        size=len(PAYLOAD),
                        sha256=sha256_bytes(PAYLOAD),
                        metadata={},
                    )
                ],
            )
        },
    )


def source_stream(manifest: TransferManifest, *, include_chunk: bool = True) -> bytes:
    payload = manifest_json_bytes(manifest)
    frames = [MANIFEST_HEADER.pack(MANIFEST_MAGIC, CONTROL_VERSION, len(payload)) + payload]
    if include_chunk:
        frames.append(encode_chunk(TRANSFER_ID, CategoryId.DOCUMENTS, "doc-1", 0, 1, PAYLOAD))
    return b"".join(frames)


class TransferSessionTests(unittest.TestCase):
    def run_session(self, history: TransferHistory, adb: FakeAdb, *, include_chunk=True):
        source = FakeSourceTransport(MemoryConnection(source_stream(sample_manifest(), include_chunk=include_chunk)))
        target = FakeTargetTransport()
        with (
            patch("transferkit.transport.session.UsbAdbSourceTransport", return_value=source),
            patch("transferkit.transport.session.UsbAdbTransport", return_value=target),
        ):
            result = run_authenticated_usb_adb_transfer(adb, history, "SOURCE", "TARGET")
        return result, target

    def test_success_relays_ack_checkpoints_receipt_and_history(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            history = TransferHistory(Path(folder) / "history.sqlite3")
            result, target = self.run_session(history, FakeAdb())

            records = history.list_transfers()
            checkpoint = history.get_checkpoint(TRANSFER_ID, CategoryId.DOCUMENTS, "doc-1")
            self.assertEqual(result.transfer_id, TRANSFER_ID)
            self.assertEqual(result.total_bytes, len(PAYLOAD))
            self.assertEqual(target.manifest.transfer_id, TRANSFER_ID)
            self.assertEqual(records[0].status, "COMPLETED")
            self.assertEqual(records[0].manifest_sha256, result.manifest_sha256)
            self.assertEqual(checkpoint.next_expected_chunk, 1)
            self.assertEqual(checkpoint.next_byte_offset, len(PAYLOAD))
            self.assertEqual(history.get_receipt(TRANSFER_ID)["transfer_id"], str(TRANSFER_ID))

    def test_source_manifest_must_match_selected_adb_device(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            history = TransferHistory(Path(folder) / "history.sqlite3")
            source = FakeSourceTransport(MemoryConnection(source_stream(sample_manifest())))
            target = FakeTargetTransport()
            with (
                patch("transferkit.transport.session.UsbAdbSourceTransport", return_value=source),
                patch("transferkit.transport.session.UsbAdbTransport", return_value=target),
                self.assertRaises(TransportError),
            ):
                run_authenticated_usb_adb_transfer(
                    FakeAdb(source_fingerprint="other/build"), history, "SOURCE", "TARGET"
                )
            self.assertIsNone(target.manifest)
            self.assertEqual(history.list_transfers(), [])

    def test_interruption_keeps_acknowledged_checkpoint_and_marks_waiting(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            history = TransferHistory(Path(folder) / "history.sqlite3")
            source = FakeSourceTransport(
                MemoryConnection(source_stream(sample_manifest(), include_chunk=False))
            )
            target = FakeTargetTransport()
            with (
                patch("transferkit.transport.session.UsbAdbSourceTransport", return_value=source),
                patch("transferkit.transport.session.UsbAdbTransport", return_value=target),
                self.assertRaises(TransportError),
            ):
                run_authenticated_usb_adb_transfer(FakeAdb(), history, "SOURCE", "TARGET")
            self.assertEqual(history.list_transfers()[0].status, "PAUSED_AWAITING_USER")
            self.assertIsNone(history.get_checkpoint(TRANSFER_ID, CategoryId.DOCUMENTS, "doc-1"))
            self.assertIsNone(history.get_receipt(TRANSFER_ID))


if __name__ == "__main__":
    unittest.main()
