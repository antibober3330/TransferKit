import csv
import io
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID

from transferkit.contracts import (
    CategoryReceipt,
    DeviceInfo,
    FailureStage,
    PlanStatus,
    ReasonCode,
    ReportCategory,
    ReportDecision,
    ReportFailure,
    ReportStatus,
    ReportSummary,
    ReceiptFailure,
    ReceiptFailureStage,
    Tier,
    TransferReport,
    TransferReceipt,
    validate_document,
    validate_report_receipt_consistency,
)
from transferkit.report import (
    receipt_json_bytes,
    report_csv_bytes,
    report_json_bytes,
    write_receipt_json,
    write_report_csv,
    write_report_json,
)


def sample_report() -> TransferReport:
    started = datetime.now(timezone.utc)
    device = DeviceInfo(
        manufacturer="Example",
        model="Test",
        sdk=34,
        fingerprint="example/test",
    )


    return TransferReport(
        version="1.0",
        transfer_id=UUID("00000000-0000-4000-8000-000000000001"),
        started_at=started,
        finished_at=started + timedelta(seconds=1),
        mode="quick",
        source_device=device,
        target_device=device,
        categories=[
            ReportCategory(
                category_id="photos",
                tier=Tier.A,
                source_count=2,
                manifest_count=2,
                transferred_count=1,
                verified_count=1,
                restored_count=1,
                plan_status=PlanStatus.TRANSFERABLE,
                status=ReportStatus.PARTIAL,
                reason=ReasonCode.TARGET_NO_SPACE,
                failures=[
                    ReportFailure(
                        item_id="photo-large",
                        stage=FailureStage.TRANSFER,
                        reason=ReasonCode.TARGET_ITEM_TOO_LARGE,
                        display_message="The item exceeds the safe storage budget.",
                    )
                ],
            )
        ],
        summary=ReportSummary(
            total_categories=1,
            transferable=1,
            partial=0,
            unavailable=0,
            failed_integrity=0,
        ),
        decisions=[
            ReportDecision(
                reason=ReasonCode.USER_CHOSE_DROP_LARGEST,
                item_ids=["photo-large"],
            )
        ],
    )


def sample_receipt() -> TransferReceipt:
    return TransferReceipt(
        version="1.0",
        transfer_id=UUID("00000000-0000-4000-8000-000000000001"),
        received_at=datetime.now(timezone.utc),
        categories={"photos": CategoryReceipt(items_received=0, items_verified=0, items_failed=0)},
    )


class ReportExportTests(unittest.TestCase):
    def test_dedup_skips_are_reported_as_automatic_category_decisions(self) -> None:
        report = sample_report()
        category = report.categories[0].model_copy(
            update={"deduplicated_count": 1, "deduplicated_bytes": 200}
        )
        report = report.model_copy(
            update={
                "categories": [category],
                "decisions": report.decisions
                + [
                    ReportDecision(
                        reason=ReasonCode.ALREADY_PRESENT,
                        item_ids=["photo-duplicate"],
                        category_id="photos",
                    )
                ],
            }
        )

        document = json.loads(report_json_bytes(report))
        validate_document(document, "report.schema.json")
        self.assertEqual(document["categories"][0]["deduplicated_count"], 1)
        self.assertEqual(document["categories"][0]["deduplicated_bytes"], 200)
        self.assertEqual(document["decisions"][1]["category_id"], "photos")
        rows = list(csv.DictReader(io.StringIO(report_csv_bytes(report).decode("utf-8-sig"))))
        self.assertEqual(rows[1]["deduplicated_count"], "1")
        self.assertEqual(rows[-1]["reason"], "ALREADY_PRESENT")
        self.assertEqual(rows[-1]["category_id"], "photos")

    def test_deduplicated_count_cannot_exceed_untransferred_manifest_items(self) -> None:
        row = sample_report().categories[0]
        with self.assertRaisesRegex(ValueError, "deduplicated_count exceeds"):
            type(row).model_validate({**row.model_dump(), "deduplicated_count": 2})

    def test_receipt_counts_and_failures_must_match_report(self) -> None:
        report = sample_report()
        receipt = TransferReceipt(
            version="1.0",
            transfer_id=report.transfer_id,
            received_at=report.finished_at,
            categories={
                "photos": CategoryReceipt(
                    items_received=1,
                    items_verified=0,
                    items_failed=1,
                    failures=[
                        ReceiptFailure(
                            item_id="p1",
                            stage=ReceiptFailureStage.CRC32_VERIFY,
                            reason=ReasonCode.CRC32_MISMATCH,
                        )
                    ],
                )
            },
        )
        base_row = report.categories[0]
        consistent = report.model_copy(
            update={
                "categories": [
                    base_row.model_copy(
                        update={
                            "verified_count": 0,
                            "restored_count": 0,
                            "failures": [
                                ReportFailure(
                                    item_id="p1",
                                    stage=FailureStage.VERIFY,
                                    reason=ReasonCode.CRC32_MISMATCH,
                                )
                            ],
                        }
                    )
                ]
            }
        )
        validate_report_receipt_consistency(consistent, receipt)
        validate_document(json.loads(report_json_bytes(consistent, receipt=receipt)), "report.schema.json")

        inconsistent = consistent.model_copy(
            update={
                "categories": [
                    consistent.categories[0].model_copy(update={"transferred_count": 0})
                ]
            }
        )
        with self.assertRaisesRegex(ValueError, "transferred_count"):
            validate_report_receipt_consistency(inconsistent, receipt)
        with self.assertRaisesRegex(ValueError, "transferred_count"):
            report_json_bytes(inconsistent, receipt=receipt)

    def test_receipt_failure_records_are_unique_and_match_failed_count(self) -> None:
        failure = ReceiptFailure(
            item_id="p1",
            stage=ReceiptFailureStage.SHA256_VERIFY,
            reason=ReasonCode.SHA256_MISMATCH,
        )
        with self.assertRaisesRegex(ValueError, "one failure record"):
            CategoryReceipt(items_received=1, items_verified=0, items_failed=1, failures=[])
        with self.assertRaisesRegex(ValueError, "unique"):
            CategoryReceipt(
                items_received=2,
                items_verified=0,
                items_failed=2,
                failures=[failure, failure],
            )

    def test_json_export_is_canonical_schema_valid_and_includes_decisions(self) -> None:
        report = sample_report()
        payload = report_json_bytes(report)
        document = json.loads(payload)
        self.assertEqual(document["decisions"][0]["reason"], "USER_CHOSE_DROP_LARGEST")
        self.assertEqual(payload, report_json_bytes(report))
        validate_document(document, "report.schema.json")

    def test_receipt_json_export_is_canonical_schema_valid_and_atomic(self) -> None:
        receipt = sample_receipt()
        payload = receipt_json_bytes(receipt)
        document = json.loads(payload)
        self.assertEqual(payload, receipt_json_bytes(receipt))
        validate_document(document, "receipt.schema.json")

        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            path = write_receipt_json(receipt, Path(folder) / "receipt.json")
            self.assertEqual(path.read_bytes(), payload)
            self.assertEqual({item.name for item in Path(folder).iterdir()}, {"receipt.json"})

    def test_csv_export_has_summary_category_failure_and_decision_rows(self) -> None:
        payload = report_csv_bytes(sample_report())
        rows = list(csv.DictReader(io.StringIO(payload.decode("utf-8-sig"))))
        self.assertEqual([row["record_type"] for row in rows], ["summary", "category", "failure", "decision"])
        self.assertEqual(rows[2]["item_id"], "photo-large")
        self.assertEqual(rows[2]["failure_reason"], "TARGET_ITEM_TOO_LARGE")
        self.assertEqual(rows[3]["reason"], "USER_CHOSE_DROP_LARGEST")
        self.assertEqual(rows[3]["decision_item_ids"], '["photo-large"]')

    def test_json_and_csv_files_are_written_atomically(self) -> None:
        report = sample_report()
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            root = Path(folder)
            json_path = write_report_json(report, root / "report.json")
            csv_path = write_report_csv(report, root / "report.csv")
            self.assertEqual(json_path.read_bytes(), report_json_bytes(report))
            self.assertEqual(csv_path.read_bytes(), report_csv_bytes(report))
            self.assertEqual({path.name for path in root.iterdir()}, {"report.json", "report.csv"})


if __name__ == "__main__":
    unittest.main()
