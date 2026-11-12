"""Canonical receipt/report JSON and flat CSV exports for TransferKit."""

from __future__ import annotations

import csv
import io
import json
import os
import tempfile
from pathlib import Path

from .contracts import (
    TransferReceipt,
    TransferReport,
    canonical_json_bytes,
    validate_document,
    validate_report_receipt_consistency,
)


CSV_COLUMNS = (
    "record_type",
    "category_id",
    "tier",
    "source_count",
    "manifest_count",
    "transferred_count",
    "verified_count",
    "restored_count",
    "deduplicated_count",
    "deduplicated_bytes",
    "plan_status",
    "status",
    "reason",
    "item_id",
    "failure_stage",
    "failure_reason",
    "display_message",
    "decision_item_ids",
    "total_categories",
    "transferable",
    "partial",
    "unavailable",
    "failed_integrity",
)


def report_json_bytes(report: TransferReport, *, receipt: TransferReceipt | None = None) -> bytes:
    """Return canonical report JSON, optionally cross-checking its final receipt."""
    if receipt is not None:
        validate_report_receipt_consistency(report, receipt)
    payload = canonical_json_bytes(report)
    validate_document(json.loads(payload), "report.schema.json")
    return payload


def receipt_json_bytes(receipt: TransferReceipt) -> bytes:
    """Return canonical UTF-8 receipt JSON after schema and semantic validation."""
    payload = canonical_json_bytes(receipt)
    validate_document(json.loads(payload), "receipt.schema.json")
    return payload


def report_csv_bytes(report: TransferReport, *, receipt: TransferReceipt | None = None) -> bytes:
    """Return UTF-8-with-BOM CSV with summary, failure and decision rows."""
    report_json_bytes(report, receipt=receipt)  # Validate before exporting another format.
    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=CSV_COLUMNS, lineterminator="\n")
    writer.writeheader()
    writer.writerow(
        {
            "record_type": "summary",
            "total_categories": report.summary.total_categories,
            "transferable": report.summary.transferable,
            "partial": report.summary.partial,
            "unavailable": report.summary.unavailable,
            "failed_integrity": report.summary.failed_integrity,
        }
    )
    for category in report.categories:
        writer.writerow(
            {
                "record_type": "category",
                "category_id": category.category_id.value,
                "tier": category.tier.value,
                "source_count": category.source_count,
                "manifest_count": category.manifest_count,
                "transferred_count": category.transferred_count,
                "verified_count": category.verified_count,
                "restored_count": category.restored_count,
                "deduplicated_count": category.deduplicated_count,
                "deduplicated_bytes": category.deduplicated_bytes,
                "plan_status": category.plan_status.value,
                "status": category.status.value,
                "reason": category.reason.value,
            }
        )
        for failure in category.failures:
            writer.writerow(
                {
                    "record_type": "failure",
                    "category_id": category.category_id.value,
                    "item_id": failure.item_id,
                    "failure_stage": failure.stage.value,
                    "failure_reason": failure.reason.value,
                    "display_message": failure.display_message or "",
                }
            )
    for decision in report.decisions:
        writer.writerow(
            {
                "record_type": "decision",
                "category_id": decision.category_id.value if decision.category_id else "",
                "reason": decision.reason.value,
                "decision_item_ids": json.dumps(
                    decision.item_ids,
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
            }
        )
    return output.getvalue().encode("utf-8-sig")


def _atomic_write(path: str | Path, payload: bytes) -> Path:
    destination = Path(path)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{destination.name}.",
            suffix=".tmp",
            dir=destination.parent,
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
            temporary.write(payload)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_path, destination)
    except OSError:
        if temporary_path is not None:
            try:
                temporary_path.unlink(missing_ok=True)
            except OSError:
                pass
        raise
    return destination


def write_report_json(
    report: TransferReport,
    path: str | Path,
    *,
    receipt: TransferReceipt | None = None,
) -> Path:
    """Atomically write canonical JSON and return the resulting path."""
    return _atomic_write(path, report_json_bytes(report, receipt=receipt))


def write_receipt_json(receipt: TransferReceipt, path: str | Path) -> Path:
    """Atomically persist a validated final receipt as canonical JSON."""
    return _atomic_write(path, receipt_json_bytes(receipt))


def write_report_csv(
    report: TransferReport,
    path: str | Path,
    *,
    receipt: TransferReceipt | None = None,
) -> Path:
    """Atomically write UTF-8 CSV and return the resulting path."""
    return _atomic_write(path, report_csv_bytes(report, receipt=receipt))
