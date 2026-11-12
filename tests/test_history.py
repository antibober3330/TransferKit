import tempfile
import sqlite3
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID

from transferkit.contracts import (
    CategoryReceipt,
    CategoryId,
    DeviceInfo,
    PlanStatus,
    ReasonCode,
    ReceiptFailure,
    ReceiptFailureStage,
    ReportCategory,
    ReportDecision,
    ReportStatus,
    ReportSummary,
    Tier,
    TransferReceipt,
    TransferReport,
)
from transferkit.history import TransferHistory


TRANSFER_ID = UUID("00000000-0000-4000-8000-000000000001")
MANIFEST_SHA = "a" * 64
SOURCE_FINGERPRINT = "b" * 64
TARGET_FINGERPRINT = "c" * 64


def add_transfer(history: TransferHistory, *, category_totals=None) -> None:
    history.begin_transfer(
        TRANSFER_ID,
        manifest_sha256=MANIFEST_SHA,
        source_fingerprint=SOURCE_FINGERPRINT,
        target_fingerprint=TARGET_FINGERPRINT,
        protocol_version="2",
        category_totals=category_totals,
    )


def sample_report() -> TransferReport:
    started = datetime.now(timezone.utc)
    device = DeviceInfo(manufacturer="Example", model="Test", sdk=34, fingerprint="example/test")
    return TransferReport(
        version="1.0",
        transfer_id=TRANSFER_ID,
        started_at=started,
        finished_at=started + timedelta(seconds=1),
        mode="quick",
        source_device=device,
        target_device=device,
        categories=[
            ReportCategory(
                category_id=CategoryId.PHOTOS,
                tier=Tier.A,
                source_count=0,
                manifest_count=0,
                transferred_count=0,
                verified_count=0,
                restored_count=0,
                plan_status=PlanStatus.TRANSFERABLE,
                status=ReportStatus.OK,
                reason=ReasonCode.OK,
                failures=[],
            )
        ],
        summary=ReportSummary(
            total_categories=1,
            transferable=1,
            partial=0,
            unavailable=0,
            failed_integrity=0,
        ),
        decisions=[ReportDecision(reason=ReasonCode.USER_CHOSE_STREAMING, item_ids=[])],
    )


def sample_receipt() -> TransferReceipt:
    return TransferReceipt(
        version="1.0",
        transfer_id=TRANSFER_ID,
        received_at=datetime.now(timezone.utc),
        categories={"photos": CategoryReceipt(items_received=0, items_verified=0, items_failed=0)},
    )


class TransferHistoryTests(unittest.TestCase):
    def test_source_category_aggregate_tracks_acknowledged_items_and_final_receipt(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            database = Path(folder) / "history.sqlite3"
            history = TransferHistory(database)
            add_transfer(history, category_totals={CategoryId.PHOTOS: (2, 9)})
            history.record_chunk_ack(
                TRANSFER_ID, "photos", "photo-1", chunk_index=0, total_chunks=2,
                chunk_size=4, byte_offset=0, payload_size=4, payload_crc32=11,
            )
            history.record_chunk_ack(
                TRANSFER_ID, "photos", "photo-2", chunk_index=0, total_chunks=1,
                chunk_size=4, byte_offset=0, payload_size=3, payload_crc32=22,
            )

            reopened = TransferHistory(database)
            progress = reopened.list_category_progress(TRANSFER_ID)[0]
            self.assertEqual(
                (progress.items_total, progress.total_bytes, progress.acknowledged_bytes,
                 progress.items_partial, progress.items_chunk_complete),
                (2, 9, 7, 1, 1),
            )
            receipt = TransferReceipt(
                version="1.0",
                transfer_id=TRANSFER_ID,
                received_at=datetime.now(timezone.utc),
                categories={
                    CategoryId.PHOTOS: CategoryReceipt(
                        items_received=2,
                        items_verified=1,
                        items_failed=1,
                        failures=[ReceiptFailure(
                            item_id="photo-2",
                            stage=ReceiptFailureStage.SHA256_VERIFY,
                            reason=ReasonCode.SHA256_MISMATCH,
                        )],
                    )
                },
            )
            with self.assertRaisesRegex(ValueError, "ACK-confirmed chunk progress"):
                reopened.save_receipt(receipt)
            reopened.record_chunk_ack(
                TRANSFER_ID, "photos", "photo-1", chunk_index=1, total_chunks=2,
                chunk_size=4, byte_offset=4, payload_size=2, payload_crc32=33,
            )
            reopened.save_receipt(receipt)
            final = reopened.list_category_progress(TRANSFER_ID)[0]

        self.assertEqual(
            (final.acknowledged_bytes, final.items_partial, final.items_chunk_complete,
             final.items_verified, final.items_failed),
            (9, 0, 2, 1, 1),
        )

    def test_empty_category_summary_is_immutable(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            history = TransferHistory(Path(folder) / "history.sqlite3")
            add_transfer(history, category_totals={})
            with self.assertRaisesRegex(ValueError, "category manifest summary is immutable"):
                add_transfer(history, category_totals={CategoryId.PHOTOS: (0, 0)})

    def test_recovery_identity_fingerprints_are_bound_once_after_proof(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            history = TransferHistory(Path(folder) / "history.sqlite3")
            add_transfer(history)
            self.assertIsNone(history.list_transfers()[0].source_identity_fingerprint)
            self.assertIsNone(history.list_transfers()[0].target_identity_fingerprint)
            history.bind_recovery_identity_fingerprints(
                TRANSFER_ID,
                source_identity_fingerprint="d" * 64,
                target_identity_fingerprint="e" * 64,
            )
            self.assertEqual(history.list_transfers()[0].source_identity_fingerprint, "d" * 64)
            with self.assertRaisesRegex(ValueError, "immutable once bound"):
                history.bind_recovery_identity_fingerprints(
                    TRANSFER_ID,
                    source_identity_fingerprint="f" * 64,
                    target_identity_fingerprint="e" * 64,
                )

    def test_legacy_source_history_migrates_optional_recovery_and_summary_columns(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            database = Path(folder) / "history.sqlite3"
            connection = sqlite3.connect(database)
            try:
                connection.execute(
                    "CREATE TABLE active_transfers ("
                    "transfer_id TEXT PRIMARY KEY, manifest_sha256 TEXT NOT NULL, "
                    "source_fingerprint TEXT NOT NULL, target_fingerprint TEXT NOT NULL, "
                    "protocol_version TEXT NOT NULL, status TEXT NOT NULL, "
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)"
                )
                timestamp = "2026-11-12T00:00:00Z"
                connection.execute(
                    "INSERT INTO active_transfers VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (str(TRANSFER_ID), MANIFEST_SHA, SOURCE_FINGERPRINT, TARGET_FINGERPRINT,
                     "2", "PAUSED_AWAITING_USER", timestamp, timestamp),
                )
                connection.commit()
            finally:
                connection.close()

            history = TransferHistory(database)
            migrated = history.list_transfers()[0]
            self.assertEqual(migrated.transfer_id, str(TRANSFER_ID))
            self.assertIsNone(migrated.source_identity_fingerprint)
            self.assertIsNone(migrated.target_identity_fingerprint)
            add_transfer(history, category_totals={CategoryId.PHOTOS: (0, 0)})
            self.assertEqual(history.list_category_progress(TRANSFER_ID)[0].items_total, 0)

    def test_chunk_acks_advance_and_survive_reopen(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            database = Path(folder) / "history.sqlite3"
            history = TransferHistory(database)
            add_transfer(history)
            first = history.record_chunk_ack(
                TRANSFER_ID,
                CategoryId.PHOTOS,
                "photo-1",
                chunk_index=0,
                total_chunks=2,
                chunk_size=4,
                byte_offset=0,
                payload_size=4,
                payload_crc32=0x12345678,
            )
            second = history.record_chunk_ack(
                TRANSFER_ID,
                CategoryId.PHOTOS,
                "photo-1",
                chunk_index=1,
                total_chunks=2,
                chunk_size=4,
                byte_offset=4,
                payload_size=2,
                payload_crc32=0x87654321,
            )
            reopened = TransferHistory(database).get_checkpoint(TRANSFER_ID, "photos", "photo-1")
        self.assertEqual(first.next_expected_chunk, 1)
        self.assertEqual(second.next_expected_chunk, 2)
        self.assertEqual(second.next_byte_offset, 6)
        self.assertEqual(reopened, second)

    def test_duplicate_ack_is_idempotent_but_conflicting_duplicate_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            history = TransferHistory(Path(folder) / "history.sqlite3")
            add_transfer(history)
            ack = {
                "chunk_index": 0,
                "total_chunks": 2,
                "chunk_size": 4,
                "byte_offset": 0,
                "payload_size": 4,
                "payload_crc32": 123,
            }
            first = history.record_chunk_ack(TRANSFER_ID, "photos", "photo-1", **ack)
            duplicate = history.record_chunk_ack(TRANSFER_ID, "photos", "photo-1", **ack)
            self.assertEqual(first, duplicate)
            with self.assertRaisesRegex(ValueError, "different checkpoint"):
                history.record_chunk_ack(
                    TRANSFER_ID,
                    "photos",
                    "photo-1",
                    **{**ack, "payload_crc32": 999},
                )

    def test_nonsequential_offset_and_chunk_layout_changes_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            history = TransferHistory(Path(folder) / "history.sqlite3")
            add_transfer(history)
            with self.assertRaisesRegex(ValueError, "sequentially"):
                history.record_chunk_ack(
                    TRANSFER_ID,
                    "photos",
                    "photo-1",
                    chunk_index=1,
                    total_chunks=2,
                    chunk_size=4,
                    byte_offset=4,
                    payload_size=2,
                    payload_crc32=1,
                )
            history.record_chunk_ack(
                TRANSFER_ID,
                "photos",
                "photo-1",
                chunk_index=0,
                total_chunks=2,
                chunk_size=4,
                byte_offset=0,
                payload_size=4,
                payload_crc32=1,
            )
            with self.assertRaisesRegex(ValueError, "byte offset"):
                history.record_chunk_ack(
                    TRANSFER_ID,
                    "photos",
                    "photo-1",
                    chunk_index=1,
                    total_chunks=2,
                    chunk_size=4,
                    byte_offset=3,
                    payload_size=2,
                    payload_crc32=1,
                )
            with self.assertRaisesRegex(ValueError, "layout changed"):
                history.record_chunk_ack(
                    TRANSFER_ID,
                    "photos",
                    "photo-1",
                    chunk_index=1,
                    total_chunks=2,
                    chunk_size=8,
                    byte_offset=4,
                    payload_size=2,
                    payload_crc32=1,
                )

    def test_transfer_identity_is_immutable_and_terminal_transfer_cannot_reopen(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            history = TransferHistory(Path(folder) / "history.sqlite3")
            add_transfer(history)
            with self.assertRaisesRegex(ValueError, "immutable identity"):
                history.begin_transfer(
                    TRANSFER_ID,
                    manifest_sha256="d" * 64,
                    source_fingerprint=SOURCE_FINGERPRINT,
                    target_fingerprint=TARGET_FINGERPRINT,
                    protocol_version="2",
                )
            history.set_status(TRANSFER_ID, "COMPLETED")
            with self.assertRaisesRegex(ValueError, "terminal transfer_id"):
                add_transfer(history)

    def test_final_report_is_persisted_and_immutable(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            history = TransferHistory(Path(folder) / "history.sqlite3")
            add_transfer(history)
            report = sample_report()
            history.save_report(report)
            history.save_report(report)
            self.assertEqual(history.get_report(TRANSFER_ID)["transfer_id"], str(TRANSFER_ID))
            modified = report.model_copy(update={"decisions": []})
            with self.assertRaisesRegex(ValueError, "immutable"):
                history.save_report(modified)

    def test_final_receipt_is_persisted_schema_validated_and_immutable(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            database = Path(folder) / "history.sqlite3"
            history = TransferHistory(database)
            add_transfer(history)
            receipt = sample_receipt()
            history.save_receipt(receipt)
            history.save_receipt(receipt)

            reopened = TransferHistory(database)
            stored = reopened.get_receipt(TRANSFER_ID)
            self.assertEqual(stored["transfer_id"], str(TRANSFER_ID))
            self.assertEqual(stored["categories"]["photos"]["items_received"], 0)

            modified = receipt.model_copy(update={"received_at": receipt.received_at + timedelta(seconds=1)})
            with self.assertRaisesRegex(ValueError, "immutable"):
                reopened.save_receipt(modified)
            self.assertEqual(reopened.get_receipt(TRANSFER_ID), stored)

    def test_final_receipt_requires_an_existing_transfer(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            history = TransferHistory(Path(folder) / "history.sqlite3")
            with self.assertRaisesRegex(KeyError, "receipt transfer_id"):
                history.save_receipt(sample_receipt())

    def test_history_list_is_newest_first_and_has_bounded_limit(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            history = TransferHistory(Path(folder) / "history.sqlite3")
            add_transfer(history)
            records = history.list_transfers()
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0].transfer_id, str(TRANSFER_ID))
            self.assertEqual(records[0].manifest_sha256, MANIFEST_SHA)
            with self.assertRaisesRegex(ValueError, "limit"):
                history.list_transfers(limit=0)


if __name__ == "__main__":
    unittest.main()
