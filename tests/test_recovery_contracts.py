import json
import re
import unittest
from copy import deepcopy
from datetime import timezone
from pathlib import Path
from uuid import UUID

from pydantic import ValidationError

from transferkit.contracts import CheckpointState, ResumePlan, validate_document


ROOT = Path(__file__).resolve().parents[1]
TRANSFER_ID = "00000000-0000-4000-8000-000000000001"
SHA = "0" * 64
SOURCE_FINGERPRINT = "1" * 64
TARGET_FINGERPRINT = "2" * 64
STAMP = "2026-11-11T19:42:00Z"


def valid_checkpoint() -> dict:
    return {
        "version": "1.0",
        "transfer_id": TRANSFER_ID,
        "manifest_sha256": SHA,
        "source_fingerprint": SOURCE_FINGERPRINT,
        "target_fingerprint": TARGET_FINGERPRINT,
        "protocol_version": "1",
        "source_app_version": "0.1.0",
        "target_app_version": "0.1.0",
        "updated_at": STAMP,
        "categories": {
            "photos": {
                "items_total": 3,
                "items_verified": 1,
                "items_failed": 0,
                "items_pending": 1,
                "items_partial": [
                    {
                        "item_id": "photo_2",
                        "next_expected_chunk": 3,
                        "total_chunks": 5,
                        "next_byte_offset": 12_582_912,
                    }
                ],
            }
        },
    }


def valid_resume_plan() -> dict:
    return {
        "version": "1.0",
        "transfer_id": TRANSFER_ID,
        "manifest_sha256": SHA,
        "items": [
            {
                "category_id": "photos",
                "item_id": "photo_2",
                "start_chunk": 3,
                "byte_offset": 12_582_912,
                "total_chunks": 5,
            }
        ],
        "requires_user_confirmation": True,
    }


class RecoveryContractTests(unittest.TestCase):
    def test_protocol_examples_validate_and_return_typed_models(self) -> None:
        protocol = (ROOT / "shared" / "protocol.md").read_text(encoding="utf-8")
        for heading, schema_name, expected_type in (
            ("### CheckpointState", "checkpoint-state.schema.json", CheckpointState),
            ("### ResumePlan", "resume-plan.schema.json", ResumePlan),
        ):
            with self.subTest(example=heading):
                start = protocol.index(heading)
                match = re.search(r"```json\s*\n(.*?)\n```", protocol[start:], re.DOTALL)
                self.assertIsNotNone(match)
                payload = json.loads(match.group(1))
                model = validate_document(payload, schema_name)
                self.assertIsInstance(model, expected_type)

    def test_checkpoint_model_round_trip_keeps_utc_z_and_identity(self) -> None:
        model = validate_document(valid_checkpoint(), "checkpoint-state.schema.json")
        self.assertIsInstance(model, CheckpointState)
        self.assertEqual(model.transfer_id, UUID(TRANSFER_ID))
        self.assertEqual(model.updated_at.utcoffset(), timezone.utc.utcoffset(model.updated_at))
        dumped = model.model_dump(mode="json")
        self.assertTrue(dumped["updated_at"].endswith("Z"))
        validate_document(dumped, "checkpoint-state.schema.json")

    def test_checkpoint_rejects_invalid_uuid_digest_and_non_z_timestamp(self) -> None:
        for field, value in (
            ("transfer_id", "not-a-uuid"),
            ("manifest_sha256", "g" * 64),
            ("source_fingerprint", "short"),
            ("target_fingerprint", "A" * 64),
            ("updated_at", "2026-11-11T21:42:00+02:00"),
        ):
            with self.subTest(field=field):
                payload = valid_checkpoint()
                payload[field] = value
                with self.assertRaises(ValueError):
                    validate_document(payload, "checkpoint-state.schema.json")

    def test_checkpoint_rejects_counter_mismatch(self) -> None:
        payload = valid_checkpoint()
        payload["categories"]["photos"]["items_pending"] = 2
        with self.assertRaises(ValidationError):
            CheckpointState.model_validate(payload)
        with self.assertRaisesRegex(ValueError, "items_total"):
            validate_document(payload, "checkpoint-state.schema.json")

    def test_checkpoint_rejects_duplicate_partial_item_ids(self) -> None:
        payload = valid_checkpoint()
        category = payload["categories"]["photos"]
        category["items_total"] += 1
        category["items_partial"].append(deepcopy(category["items_partial"][0]))
        with self.assertRaisesRegex(ValidationError, "unique"):
            CheckpointState.model_validate(payload)

    def test_checkpoint_rejects_completed_chunk_index_and_inconsistent_offset(self) -> None:
        for field, value in (("next_expected_chunk", 5), ("next_byte_offset", -1), ("next_byte_offset", 0)):
            with self.subTest(field=field, value=value):
                payload = valid_checkpoint()
                payload["categories"]["photos"]["items_partial"][0][field] = value
                with self.assertRaises(ValueError):
                    validate_document(payload, "checkpoint-state.schema.json")

    def test_checkpoint_without_durable_chunks_is_pending_not_partial(self) -> None:
        payload = valid_checkpoint()
        partial = payload["categories"]["photos"]["items_partial"][0]
        partial.update(next_expected_chunk=0, next_byte_offset=0)
        with self.assertRaises(ValueError):
            validate_document(payload, "checkpoint-state.schema.json")

    def test_resume_plan_validates_and_round_trips(self) -> None:
        model = validate_document(valid_resume_plan(), "resume-plan.schema.json")
        self.assertIsInstance(model, ResumePlan)
        self.assertTrue(model.requires_user_confirmation)
        validate_document(model.model_dump(mode="json"), "resume-plan.schema.json")

    def test_resume_plan_requires_user_confirmation_and_at_least_one_item(self) -> None:
        for field, value in (("requires_user_confirmation", False), ("items", [])):
            with self.subTest(field=field):
                payload = valid_resume_plan()
                payload[field] = value
                with self.assertRaises(ValueError):
                    validate_document(payload, "resume-plan.schema.json")

    def test_resume_plan_rejects_duplicate_item_identity(self) -> None:
        payload = valid_resume_plan()
        payload["items"].append(deepcopy(payload["items"][0]))
        with self.assertRaisesRegex(ValidationError, "unique"):
            ResumePlan.model_validate(payload)

    def test_resume_plan_rejects_bad_chunk_boundaries_and_offsets(self) -> None:
        for field, value in (("start_chunk", 5), ("byte_offset", -1), ("byte_offset", 0)):
            with self.subTest(field=field, value=value):
                payload = valid_resume_plan()
                payload["items"][0][field] = value
                with self.assertRaises(ValueError):
                    validate_document(payload, "resume-plan.schema.json")

    def test_resume_plan_chunk_zero_starts_at_zero_offset(self) -> None:
        payload = valid_resume_plan()
        payload["items"][0].update(start_chunk=0, byte_offset=0)
        validate_document(payload, "resume-plan.schema.json")

    def test_recovery_schemas_reject_unknown_properties(self) -> None:
        checkpoint = valid_checkpoint()
        checkpoint["unexpected"] = True
        with self.assertRaises(ValueError):
            validate_document(checkpoint, "checkpoint-state.schema.json")
        plan = valid_resume_plan()
        plan["items"][0]["path"] = "private/path"
        with self.assertRaises(ValueError):
            validate_document(plan, "resume-plan.schema.json")


if __name__ == "__main__":
    unittest.main()
