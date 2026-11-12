"""Controlled end-to-end integration fixtures for the Python transfer path."""

from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID

from transferkit.contracts import (
    CategoryId,
    CategoryReceipt,
    DeviceInfo,
    FailureStage,
    PlanStatus,
    ReasonCode,
    ReportCategory,
    ReportFailure,
    ReportStatus,
    ReportSummary,
    Tier,
    TransferReceipt,
    TransferReport,
    validate_report_receipt_consistency,
)
from transferkit.integrity import decode_chunk
from transferkit.manifest import ManifestBuilder
from transferkit.report import report_json_bytes
from transferkit.transport.usb_adb import (
    ACK_KIND_CHUNK,
    ACK_KIND_MANIFEST,
    ACK_MAGIC,
    ACK_OK,
    ACK_STRUCT,
    CONTROL_VERSION,
    MANIFEST_HEADER,
    MANIFEST_MAGIC,
    UsbAdbTransport,
)
from transferkit.verify import verify_target_file


SOURCE_DEVICE = DeviceInfo(
    manufacturer="Fixture",
    model="Source",
    sdk=34,
    fingerprint="fixture/source",
)
TARGET_DEVICE = DeviceInfo(
    manufacturer="Fixture",
    model="Target",
    sdk=34,
    fingerprint="fixture/target",
)


class _AckingPeer:
    """Deterministic in-memory target that validates frames and returns ACKs."""

    def __init__(self, transfer_id: UUID):
        self.transfer_id = transfer_id
        self.responses = bytearray()
        self.manifest_received = False
        self.received_payload = bytearray()
        self.chunk_count = 0

    def sendall(self, data: bytes) -> None:
        if not self.manifest_received:
            if len(data) < MANIFEST_HEADER.size:
                raise AssertionError("truncated manifest header")
            magic, version, payload_size = MANIFEST_HEADER.unpack_from(data)
            if magic != MANIFEST_MAGIC or version != CONTROL_VERSION:
                raise AssertionError("unexpected manifest framing")
            if len(data) != MANIFEST_HEADER.size + payload_size:
                raise AssertionError("manifest frame length mismatch")
            document = json.loads(data[MANIFEST_HEADER.size:])
            if document["transfer_id"] != str(self.transfer_id):
                raise AssertionError("manifest transfer ID changed on the wire")
            self.responses.extend(
                ACK_STRUCT.pack(
                    ACK_MAGIC,
                    CONTROL_VERSION,
                    ACK_KIND_MANIFEST,
                    self.transfer_id.bytes,
                    b"\0" * 4,
                    b"\0" * 4,
                    0,
                    0,
                    ACK_OK,
                )
            )
            self.manifest_received = True
            return

        decoded = decode_chunk(data)
        self.received_payload.extend(decoded.payload)
        self.chunk_count += 1
        self.responses.extend(
            ACK_STRUCT.pack(
                ACK_MAGIC,
                CONTROL_VERSION,
                ACK_KIND_CHUNK,
                decoded.header.transfer_id.bytes,
                decoded.header.category_id_hash,
                decoded.header.item_id_hash,
                decoded.header.chunk_index,
                decoded.header.payload_crc32,
                ACK_OK,
            )
        )

    def recv(self, size: int) -> bytes:
        result = bytes(self.responses[:size])
        del self.responses[:size]
        return result

    def shutdown(self, _how) -> None:
        pass

    def close(self) -> None:
        pass


def _fixture_transfer(
    manifest,
    item,
    source_path: Path,
    *,
    chunk_size: int,
) -> bytes:
    peer = _AckingPeer(manifest.transfer_id)
    transport = UsbAdbTransport(object(), "CONTROLLED-FIXTURE")
    transport.connection = peer
    try:
        transport.send_manifest(manifest)
        sent = transport.send_file(
            manifest,
            CategoryId.PHOTOS,
            item.id,
            source_path,
            item.size,
            chunk_size=chunk_size,
        )
        if sent != peer.chunk_count:
            raise AssertionError(f"sender/receiver chunk count differs: {sent} != {peer.chunk_count}")
    finally:
        transport.close()
    return bytes(peer.received_payload)


def _report_for(manifest, item, verification, *, when: datetime) -> TransferReport:
    failed_integrity = verification.status.value == "FAILED_INTEGRITY"
    return TransferReport(
        version="1.0",
        transfer_id=manifest.transfer_id,
        started_at=when - timedelta(seconds=1),
        finished_at=when + timedelta(seconds=1),
        mode="quick",
        source_device=manifest.source_device,
        target_device=TARGET_DEVICE,
        categories=[
            ReportCategory(
                category_id=CategoryId.PHOTOS,
                tier=Tier.A,
                source_count=1,
                manifest_count=1,
                transferred_count=1,
                verified_count=1,
                restored_count=0 if failed_integrity else 1,
                plan_status=PlanStatus.TRANSFERABLE,
                status=ReportStatus.FAILED_INTEGRITY if failed_integrity else ReportStatus.OK,
                reason=ReasonCode.SHA256_MISMATCH if failed_integrity else ReasonCode.OK,
                failures=(
                    [
                        ReportFailure(
                            item_id=item.id,
                            stage=FailureStage.POST_VERIFY,
                            reason=ReasonCode.SHA256_MISMATCH,
                        )
                    ]
                    if failed_integrity
                    else []
                ),
            )
        ],
        summary=ReportSummary(
            total_categories=1,
            transferable=1,
            partial=0,
            unavailable=0,
            failed_integrity=1 if failed_integrity else 0,
        ),
    )


class TransferIntegrationTests(unittest.TestCase):
    def run_controlled_file_transfer(self, *, corrupt_destination: bool):
        payload = b"fixture image payload with multiple transfer chunks"
        now = datetime.now(timezone.utc)
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            root = Path(folder)
            source = root / "photo.jpg"
            target = root / "received.jpg"
            source.write_bytes(payload)
            builder = ManifestBuilder(
                SOURCE_DEVICE,
                transfer_id=UUID("00000000-0000-4000-8000-000000000121"),
                created_at=now,
            )
            item = builder.add_file(
                CategoryId.PHOTOS,
                source,
                root=root,
                provider="fixture",
                method="controlled socket peer",
            )
            manifest = builder.build()

            received = _fixture_transfer(manifest, item, source, chunk_size=7)
            self.assertEqual(received, payload)
            target.write_bytes(received)
            if corrupt_destination:
                target.write_bytes(b"X" + payload[1:])

            verification = verify_target_file(item, target)
            receipt = TransferReceipt(
                version="1.0",
                transfer_id=manifest.transfer_id,
                received_at=now,
                categories={
                    CategoryId.PHOTOS: CategoryReceipt(
                        items_received=1,
                        items_verified=1,
                        items_failed=0,
                    )
                },
            )
            report = _report_for(manifest, item, verification, when=now)
            validate_report_receipt_consistency(report, receipt)
            report_json_bytes(report, receipt=receipt)
            return verification, report, receipt

    def test_manifest_chunks_file_verify_receipt_and_report_form_one_flow(self) -> None:
        verification, report, receipt = self.run_controlled_file_transfer(corrupt_destination=False)
        self.assertEqual(verification.status.value, "VERIFIED")
        self.assertEqual(report.categories[0].restored_count, 1)
        self.assertEqual(receipt.categories[CategoryId.PHOTOS].items_verified, 1)

    def test_destination_corruption_fails_integrity_without_rewriting_receive_receipt(self) -> None:
        verification, report, receipt = self.run_controlled_file_transfer(corrupt_destination=True)
        self.assertEqual(verification.reason, ReasonCode.SHA256_MISMATCH)
        self.assertEqual(report.categories[0].status, ReportStatus.FAILED_INTEGRITY)
        self.assertEqual(report.categories[0].restored_count, 0)
        # The receipt documents validated bytes received into target staging;
        # destination post-verification is a later, separate report outcome.
        self.assertEqual(receipt.categories[CategoryId.PHOTOS].items_verified, 1)


if __name__ == "__main__":
    unittest.main()
