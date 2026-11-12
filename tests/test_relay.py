import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

from transferkit.contracts import (
    CategoryId,
    CategoryReceipt,
    DeviceInfo,
    ManifestCategory,
    ManifestItem,
    TransferManifest,
    TransferReceipt,
    validate_document,
)
from transferkit.integrity import decode_chunk, encode_chunk, sha256_bytes
from transferkit.manifest import manifest_json_bytes
from transferkit.transport.relay import relay_authenticated_source_stream
from transferkit.transport.usb_adb import (
    ACK_KIND_CHUNK,
    ACK_KIND_MANIFEST,
    ACK_OK,
    ACK_STRUCT,
    CONTROL_VERSION,
    MANIFEST_HEADER,
    MANIFEST_MAGIC,
    ChunkAcknowledgement,
    TransportError,
)


TRANSFER_ID = UUID("00000000-0000-4000-8000-000000000001")


class MemoryConnection:
    """Small deterministic duplex stream used instead of loopback sockets."""

    def __init__(self, incoming: bytes):
        self.incoming = bytearray(incoming)
        self.outgoing = bytearray()

    def recv(self, size: int) -> bytes:
        chunk = bytes(self.incoming[:size])
        del self.incoming[:size]
        return chunk

    def sendall(self, data: bytes) -> None:
        self.outgoing.extend(data)


def sample_manifest(payload: bytes) -> TransferManifest:
    return TransferManifest(
        version="1.0",
        transfer_id=TRANSFER_ID,
        created_at=datetime.now(timezone.utc),
        source_device=DeviceInfo(
            manufacturer="Example", model="Source", sdk=34, fingerprint="example/source"
        ),
        categories={
            CategoryId.DOCUMENTS: ManifestCategory(
                provider="TestProvider",
                method="GeneratedFixture",
                items=[ManifestItem(id="doc-1", size=len(payload), sha256=sha256_bytes(payload), metadata={})],
            )
        },
    )


class MemoryTarget:
    def __init__(self, *, mismatched_ack=False):
        self.manifest = None
        self.frames = []
        self.mismatched_ack = mismatched_ack

    def send_manifest(self, manifest):
        self.manifest = manifest

    def send_chunk(self, frame):
        decoded = decode_chunk(frame)
        self.frames.append(frame)
        header = decoded.header
        return ChunkAcknowledgement(
            transfer_id=UUID(int=0) if self.mismatched_ack else header.transfer_id,
            category_id_hash=header.category_id_hash,
            item_id_hash=header.item_id_hash,
            chunk_index=header.chunk_index,
            payload_crc32=header.payload_crc32,
            status=ACK_OK,
        )

    def finish_transfer(self):
        return TransferReceipt(
            version="1.0",
            transfer_id=TRANSFER_ID,
            received_at=datetime.now(timezone.utc),
            categories={
                CategoryId.DOCUMENTS: CategoryReceipt(
                    items_received=1, items_verified=1, items_failed=0
                )
            },
        )


def source_stream(manifest: TransferManifest, payload: bytes, chunk_size: int) -> bytes:
    document = manifest_json_bytes(manifest)
    frames = [MANIFEST_HEADER.pack(MANIFEST_MAGIC, CONTROL_VERSION, len(document)) + document]
    total_chunks = max(1, (len(payload) + chunk_size - 1) // chunk_size)
    for index in range(total_chunks):
        start = index * chunk_size
        frames.append(
            encode_chunk(
                manifest.transfer_id,
                CategoryId.DOCUMENTS,
                "doc-1",
                index,
                total_chunks,
                payload[start : start + chunk_size],
            )
        )
    return b"".join(frames)


class SourceRelayTests(unittest.TestCase):
    def test_relay_acks_source_only_after_target_accepts_and_saves_receipt(self) -> None:
        payload = b"android source relay"
        chunk_size = 4
        manifest = sample_manifest(payload)
        target = MemoryTarget()
        progress = []
        source = MemoryConnection(source_stream(manifest, payload, chunk_size))
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            receipt_path = Path(folder) / "receipt.json"
            receipt = relay_authenticated_source_stream(
                source,
                target,
                receipt_path=receipt_path,
                chunk_size=chunk_size,
                on_progress=lambda category, item, confirmed, total: progress.append(
                    (category, item, confirmed, total)
                ),
            )
            stored = json.loads(receipt_path.read_bytes())
            validate_document(stored, "receipt.schema.json")
            self.assertEqual(stored["transfer_id"], str(TRANSFER_ID))
        ack_bytes = source.outgoing

        self.assertEqual(target.manifest.transfer_id, TRANSFER_ID)
        received_payload = b"".join(decode_chunk(frame).payload for frame in target.frames)
        self.assertEqual(received_payload, payload)
        self.assertEqual(receipt.transfer_id, TRANSFER_ID)
        self.assertEqual(len(ack_bytes), ACK_STRUCT.size * 6)  # manifest ACK + five chunk ACKs
        acknowledgements = [
            ACK_STRUCT.unpack_from(ack_bytes, offset)
            for offset in range(0, len(ack_bytes), ACK_STRUCT.size)
        ]
        self.assertEqual(acknowledgements[0][2], ACK_KIND_MANIFEST)
        self.assertTrue(all(item[2] == ACK_KIND_CHUNK and item[-1] == ACK_OK for item in acknowledgements[1:]))
        self.assertEqual([row[2] for row in progress], [4, 8, 12, 16, len(payload)])

    def test_corrupt_source_chunk_is_rejected_before_target_write(self) -> None:
        payload = b"bad"
        manifest = sample_manifest(payload)
        stream = bytearray(source_stream(manifest, payload, 8))
        stream[-1] ^= 0x01
        target = MemoryTarget()
        source = MemoryConnection(stream)
        with self.assertRaises(TransportError) as caught:
            relay_authenticated_source_stream(source, target, chunk_size=8)
        self.assertEqual(caught.exception.reason.value, "CRC32_MISMATCH")
        self.assertEqual(target.frames, [])

    def test_target_ack_mismatch_is_not_forwarded_to_source(self) -> None:
        payload = b"ack-check"
        manifest = sample_manifest(payload)
        source = MemoryConnection(source_stream(manifest, payload, 16))
        target = MemoryTarget(mismatched_ack=True)

        with self.assertRaises(TransportError) as caught:
            relay_authenticated_source_stream(source, target, chunk_size=16)

        self.assertEqual(caught.exception.reason.value, "INVALID_MANIFEST")
        self.assertEqual(len(source.outgoing), ACK_STRUCT.size)  # manifest ACK only
        self.assertEqual(len(target.frames), 1)
