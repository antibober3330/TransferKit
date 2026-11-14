import unittest
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from uuid import UUID

from pydantic import ValidationError

from transferkit.contracts import (
    CATEGORY_TIER,
    MAX_MANIFEST_ITEMS,
    CategoryId,
    CapabilityManifest,
    ReportDecision,
    ReasonCode,
    ReportStatus,
    SpaceDecision,
    SpaceReport,
    TransferManifest,
    TransferReceipt,
    TransferReport,
    _validate_manifest_item_limit,
    canonical_json_bytes,
    validate_document,
)


DEVICE = {
    "manufacturer": "Example",
    "model": "TransferKit Test",
    "sdk": 34,
    "fingerprint": "example/device/fingerprint",
}
TRANSFER_ID = "00000000-0000-4000-8000-000000000001"
STAMP = "2026-11-11T12:00:00Z"
ZERO_SHA = "0" * 64


def valid_manifest() -> dict:
    return {
        "version": "1.0",
        "transfer_id": TRANSFER_ID,
        "created_at": STAMP,
        "source_device": DEVICE,
        "categories": {
            "photos": {
                "provider": "MediaStore",
                "method": "ContentResolver",
                "items": [{"id": "p1", "size": 0, "sha256": ZERO_SHA, "metadata": {}}],
            }
        },
    }


def valid_capability_manifest() -> dict:
    return {
        "version": "1.0",
        "generated_at": STAMP,
        "device": DEVICE,
        "probe_duration_ms": 25,
        "categories": {
            category.value: {
                "tier": CATEGORY_TIER[category].value,
                "status": "UNAVAILABLE",
                "method": "none",
                "permission_required": [],
                "fallback": None,
                "reason": "UNAVAILABLE",
                "evidence": ["probe completed"],
            }
            for category in CategoryId
        },
    }


def valid_report() -> dict:
    return {
        "version": "1.0",
        "transfer_id": TRANSFER_ID,
        "started_at": STAMP,
        "finished_at": STAMP,
        "mode": "quick",
        "source_device": DEVICE,
        "target_device": DEVICE,
        "categories": [
            {
                "category_id": "photos",
                "tier": "A",
                "source_count": 1,
                "manifest_count": 1,
                "transferred_count": 1,
                "verified_count": 0,
                "restored_count": 0,
                "plan_status": "TRANSFERABLE",
                "status": "FAILED_INTEGRITY",
                "reason": "SHA256_MISMATCH",
                "failures": [
                    {"item_id": "p1", "stage": "VERIFY", "reason": "SHA256_MISMATCH"}
                ],
            }
        ],
        "summary": {
            "total_categories": 1,
            "transferable": 1,
            "partial": 0,
            "unavailable": 0,
            "failed_integrity": 1,
        },
        "decisions": [],
    }


def valid_space_report() -> dict:
    return {
        "version": "1.0",
        "protocol_version": "2",
        "transfer_id": TRANSFER_ID,
        "available_bytes": 3_000_000_000,
        "allocatable_bytes": 3_000_000_000,
        "safety_margin_bytes": 150_000_000,
        "safe_available_bytes": 2_850_000_000,
        "staging_leftover_bytes": 0,
        "total_manifest_bytes": 2_500_000_000,
        "largest_item_bytes": 5_000_000,
        "batch_peak_bytes": 5_000_000_000,
        "streaming_peak_bytes": 2_505_000_000,
        "estimates_complete": True,
        "unknown_item_ids": [],
        "strategy": "streaming",
        "decision_required": True,
        "kept_item_ids": ["photo_1"],
        "skipped_item_ids": [],
    }


def valid_space_decision() -> dict:
    return {
        "version": "1.0",
        "protocol_version": "2",
        "transfer_id": TRANSFER_ID,
        "strategy": "streaming",
        "user_confirmed": True,
        "kept_item_ids": ["photo_1"],
        "skipped_item_ids": [],
    }


def valid_receipt() -> dict:
    return {
        "version": "1.0",
        "transfer_id": TRANSFER_ID,
        "received_at": STAMP,
        "categories": {
            "photos": {
                "items_received": 1,
                "items_verified": 0,
                "items_failed": 1,
                "failures": [
                    {"item_id": "p1", "stage": "SHA256_VERIFY", "reason": "SHA256_MISMATCH"}
                ],
            }
        },
    }


class ContractModelTests(unittest.TestCase):
    def test_valid_manifest_model(self) -> None:
        model = TransferManifest.model_validate(valid_manifest())
        self.assertEqual(model.transfer_id, UUID(TRANSFER_ID))
        self.assertEqual(model.created_at.utcoffset(), timezone.utc.utcoffset(model.created_at))

    def test_manifest_rejects_bad_hash_duplicate_ids_and_non_utc_time(self) -> None:
        payload = valid_manifest()
        payload["categories"]["photos"]["items"][0]["sha256"] = "not-a-hash"
        with self.assertRaises(ValidationError):
            TransferManifest.model_validate(payload)

        payload = valid_manifest()
        payload["categories"]["photos"]["items"].append(payload["categories"]["photos"]["items"][0])
        with self.assertRaises(ValidationError):
            TransferManifest.model_validate(payload)

        payload = valid_manifest()
        payload["created_at"] = "2026-11-11T14:00:00+02:00"
        with self.assertRaises(ValidationError):
            TransferManifest.model_validate(payload)

    def test_manifest_total_item_limit_is_enforced_across_categories(self) -> None:
        with self.assertRaisesRegex(ValueError, "100000 items"):
            _validate_manifest_item_limit(
                {
                    CategoryId.PHOTOS: SimpleNamespace(items=range(MAX_MANIFEST_ITEMS // 2 + 1)),
                    CategoryId.VIDEOS: SimpleNamespace(items=range(MAX_MANIFEST_ITEMS // 2)),
                }
            )

    def test_manifest_schema_validation_rejects_oversized_item_metadata(self) -> None:
        payload = valid_manifest()
        payload["categories"]["photos"]["items"][0]["metadata"] = {"note": "x" * (64 * 1024)}
        with self.assertRaisesRegex(ValueError, "64 KiB"):
            validate_document(payload, "manifest.schema.json")

    def test_capability_requires_all_categories_and_correct_tiers(self) -> None:
        CapabilityManifest.model_validate(valid_capability_manifest())
        payload = valid_capability_manifest()
        del payload["categories"]["drm"]
        with self.assertRaises(ValidationError):
            CapabilityManifest.model_validate(payload)

        payload = valid_capability_manifest()
        payload["categories"]["contacts"]["permission_required"] = [""]
        with self.assertRaises(ValidationError):
            CapabilityManifest.model_validate(payload)
        with self.assertRaises(ValueError):
            validate_document(payload, "capability.schema.json")
        payload = valid_capability_manifest()
        payload["categories"]["contacts"]["tier"] = "A"
        with self.assertRaises(ValidationError):
            CapabilityManifest.model_validate(payload)

    def test_report_counts_and_integrity_status_are_enforced(self) -> None:
        TransferReport.model_validate(valid_report())
        payload = valid_report()
        payload["categories"][0]["status"] = ReportStatus.OK.value
        with self.assertRaises(ValidationError):
            TransferReport.model_validate(payload)
        payload = valid_report()
        payload["summary"]["failed_integrity"] = 0
        with self.assertRaises(ValidationError):
            TransferReport.model_validate(payload)

    def test_all_shared_json_schemas_accept_valid_payloads(self) -> None:
        for payload, schema_name in (
            (valid_manifest(), "manifest.schema.json"),
            (valid_capability_manifest(), "capability.schema.json"),
            (valid_report(), "report.schema.json"),
            (valid_receipt(), "receipt.schema.json"),
            (valid_space_report(), "space-report.schema.json"),
            (valid_space_decision(), "space-decision.schema.json"),
        ):
            with self.subTest(schema=schema_name):
                validate_document(payload, schema_name)

    def test_space_report_unknown_measurements_and_insufficient_space_fail_closed(self) -> None:
        payload = valid_space_report()
        payload["allocatable_bytes"] = None
        payload["safety_margin_bytes"] = None
        payload["safe_available_bytes"] = None
        payload["batch_peak_bytes"] = None
        payload["streaming_peak_bytes"] = None
        payload["estimates_complete"] = False
        payload["unknown_item_ids"] = ["photo_1"]
        payload["strategy"] = "insufficient"
        payload["decision_required"] = True
        payload["kept_item_ids"] = []
        payload["skipped_item_ids"] = []

        validate_document(payload, "space-report.schema.json")
        self.assertEqual(SpaceReport.model_validate(payload).strategy, "insufficient")

        payload["strategy"] = "batch"
        with self.assertRaisesRegex(ValueError, "incomplete estimates"):
            validate_document(payload, "space-report.schema.json")

    def test_space_report_budget_equation_and_strategy_choice_are_semantic(self) -> None:
        payload = valid_space_report()
        payload["safe_available_bytes"] -= 1
        with self.assertRaisesRegex(ValueError, "safe_available_bytes"):
            validate_document(payload, "space-report.schema.json")

        payload = valid_space_report()
        payload["strategy"] = "batch"
        payload["decision_required"] = True
        with self.assertRaisesRegex(ValueError, "batch is the default"):
            validate_document(payload, "space-report.schema.json")

    def test_space_decision_requires_confirmation_and_prohibits_unsafe_subsets(self) -> None:
        payload = valid_space_decision()
        payload["user_confirmed"] = False
        with self.assertRaisesRegex(ValueError, "requires user confirmation"):
            validate_document(payload, "space-decision.schema.json")

        payload = valid_space_decision()
        payload["strategy"] = "insufficient"
        payload["user_confirmed"] = False
        with self.assertRaisesRegex(ValueError, "cannot confirm or authorize"):
            payload["kept_item_ids"] = ["photo_1"]
            validate_document(payload, "space-decision.schema.json")

    def test_pydantic_json_serialization_uses_z_and_passes_schemas(self) -> None:
        now = datetime.now(timezone.utc)
        manifest_payload = valid_manifest()
        manifest_payload["created_at"] = now
        capability_payload = valid_capability_manifest()
        capability_payload["generated_at"] = now
        report_payload = valid_report()
        report_payload["started_at"] = now
        report_payload["finished_at"] = now
        receipt_payload = valid_receipt()
        receipt_payload["received_at"] = now
        payloads = (
            (TransferManifest.model_validate(manifest_payload), "manifest.schema.json", "created_at"),
            (CapabilityManifest.model_validate(capability_payload), "capability.schema.json", "generated_at"),
            (TransferReport.model_validate(report_payload), "report.schema.json", "started_at"),
            (TransferReceipt.model_validate(receipt_payload), "receipt.schema.json", "received_at"),
        )
        for model, schema_name, timestamp_field in payloads:
            with self.subTest(schema=schema_name):
                dumped = model.model_dump(mode="json")
                self.assertTrue(dumped[timestamp_field].endswith("Z"))
                validate_document(dumped, schema_name)
                canonical = canonical_json_bytes(model)
                validate_document(json.loads(canonical), schema_name)

    def test_final_receipt_requires_every_received_item_to_be_classified(self) -> None:
        payload = valid_receipt()
        payload["categories"]["photos"] = {
            "items_received": 100,
            "items_verified": 0,
            "items_failed": 0,
            "failures": [],
        }
        with self.assertRaises(ValidationError):
            TransferReceipt.model_validate(payload)
        with self.assertRaises(ValueError):
            validate_document(payload, "receipt.schema.json")

    def test_final_receipt_requires_one_failure_record_per_failed_item(self) -> None:
        payload = valid_receipt()
        payload["categories"]["photos"]["failures"] = []
        with self.assertRaises(ValidationError):
            TransferReceipt.model_validate(payload)

    def test_final_receipt_rejects_restore_stages_and_empty_categories(self) -> None:
        payload = valid_receipt()
        payload["categories"]["photos"]["failures"][0]["stage"] = "RESTORE"
        with self.assertRaises(ValidationError):
            TransferReceipt.model_validate(payload)
        with self.assertRaises(ValueError):
            validate_document(payload, "receipt.schema.json")

        payload = valid_receipt()
        payload["categories"] = {}
        with self.assertRaises(ValidationError):
            TransferReceipt.model_validate(payload)
        with self.assertRaises(ValueError):
            validate_document(payload, "receipt.schema.json")

    def test_all_reason_codes_match_schema_and_report_decisions_validate(self) -> None:
        reason_pattern = re.compile(r"^[A-Z][A-Z0-9_]{1,63}$")
        decisions = []
        for code in ReasonCode:
            with self.subTest(code=code.value):
                self.assertRegex(code.value, reason_pattern)
                decision = ReportDecision(reason=code, item_ids=[])
                decisions.append(decision.model_dump(mode="json"))

        payload = valid_report()
        payload["decisions"] = decisions
        validate_document(payload, "report.schema.json")
        TransferReport.model_validate(payload)

    def test_json_schema_rejects_invalid_id_hash_and_offset_timestamp(self) -> None:
        payload = valid_manifest()
        payload["categories"]["photos"]["items"][0]["sha256"] = "x" * 64
        with self.assertRaises(ValueError):
            validate_document(payload, "manifest.schema.json")
        payload = valid_manifest()
        payload["created_at"] = "2026-11-11T14:00:00+02:00"
        with self.assertRaises(ValueError):
            validate_document(payload, "manifest.schema.json")

    def test_project_spec_json_examples_match_their_schemas(self) -> None:
        spec = (Path(__file__).resolve().parents[1] / "PROJECT_SPEC.md").read_text(encoding="utf-8")

        def example_after(heading: str) -> dict:
            start = spec.index(heading)
            match = re.search(r"```(?:json)?\s*\n(.*?)\n```", spec[start:], re.DOTALL)
            if match is None:
                raise AssertionError(f"no fenced JSON example follows {heading!r}")
            return json.loads(match.group(1))

        stage4 = example_after("### Етап 4: Збір маніфесту")
        manifest_72 = example_after("### 7.2 `manifest.json`")
        receipt = example_after("### 7.4 `receipt.json`")
        report = example_after("### 7.5 `report.json`")

        validate_document(stage4, "manifest.schema.json")
        validate_document(manifest_72, "manifest.schema.json")
        validate_document(receipt, "receipt.schema.json")
        validate_document(report, "report.schema.json")

        protocol = (Path(__file__).resolve().parents[1] / "shared" / "protocol.md").read_text(encoding="utf-8")
        for heading, schema_name in (
            ('Target надсилає `SpaceReport` frame', "space-report.schema.json"),
            ('Source повертає `SpaceDecision`', "space-decision.schema.json"),
        ):
            with self.subTest(protocol_example=schema_name):
                start = protocol.index(heading)
                match = re.search(r"```(?:json)?\s*\n(.*?)\n```", protocol[start:], re.DOTALL)
                self.assertIsNotNone(match)
                validate_document(json.loads(match.group(1)), schema_name)

        sample_item = stage4["categories"]["photos"]["items"][0]
        self.assertNotIn("path", sample_item)
        self.assertIn("path", sample_item["metadata"])
        self.assertEqual(report["categories"][0]["plan_status"], "TRANSFERABLE")
        self.assertEqual(report["categories"][0]["status"], "OK")

    def test_windows_scripts_in_spec_match_repository_scripts(self) -> None:
        spec = (Path(__file__).resolve().parents[1] / "PROJECT_SPEC.md").read_text(encoding="utf-8")
        for label, filename in (
            ("`install.bat`:", "install.bat"),
            ("`run.bat`:", "run.bat"),
            ("`build.bat`:", "build.bat"),
        ):
            with self.subTest(script=filename):
                start = spec.index(label, spec.index("### 11.1 Windows Station"))
                match = re.search(r"```\s*\n(.*?)\n```", spec[start:], re.DOTALL)
                self.assertIsNotNone(match)
                self.assertEqual(match.group(1).strip(), (Path(__file__).resolve().parents[1] / filename).read_text(encoding="utf-8").strip())

    def test_canonical_json_is_compact_sorted_unicode_and_rejects_nan(self) -> None:
        encoded = canonical_json_bytes({"z": "Київ", "a": 2})
        self.assertEqual(encoded, '{"a":2,"z":"Київ"}'.encode("utf-8"))
        self.assertFalse(encoded.startswith(b"\xef\xbb\xbf"))
        with self.assertRaises(ValueError):
            canonical_json_bytes({"bad": float("nan")})
        timestamp = datetime(2026, 11, 11, 12, 0, tzinfo=timezone.utc)
        self.assertEqual(canonical_json_bytes({"at": timestamp}), b'{"at":"2026-11-11T12:00:00Z"}')


if __name__ == "__main__":
    unittest.main()
