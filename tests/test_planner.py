import unittest
from datetime import datetime, timezone

from transferkit.contracts import (
    CATEGORY_TIER,
    CategoryId,
    CapabilityEntry,
    CapabilityManifest,
    CapabilityStatus,
    DeviceInfo,
    PlanStatus,
    ReasonCode,
)
from transferkit.planner import build_transfer_plan


DEVICE_SOURCE = DeviceInfo(manufacturer="Samsung", model="Source", sdk=34, fingerprint="samsung/source")
DEVICE_TARGET = DeviceInfo(manufacturer="Xiaomi", model="Target", sdk=34, fingerprint="xiaomi/target")


def capability_manifest(
    overrides: dict[CategoryId, tuple[CapabilityStatus, ReasonCode, str | None]] | None = None,
    method_suffix: str = "source",
    device: DeviceInfo = DEVICE_SOURCE,
) -> CapabilityManifest:
    overrides = overrides or {}
    categories = {}
    for category_id in CategoryId:
        status, reason, fallback = overrides.get(
            category_id,
            (CapabilityStatus.AVAILABLE, ReasonCode.OK, None),
        )
        categories[category_id] = CapabilityEntry(
            tier=CATEGORY_TIER[category_id],
            status=status,
            method=f"{category_id.value}.{method_suffix}",
            permission_required=[],
            fallback=fallback,
            reason=reason,
            evidence=["fixture"],
        )
    return CapabilityManifest(
        version="1.0",
        generated_at=datetime.now(timezone.utc),
        device=device,
        probe_duration_ms=1,
        categories=categories,
    )


class PlannerTests(unittest.TestCase):
    def test_available_categories_are_transferable_even_with_different_api_methods(self) -> None:
        source = capability_manifest(method_suffix="source")
        target = capability_manifest(method_suffix="target", device=DEVICE_TARGET)
        plan = build_transfer_plan(source, target)
        self.assertEqual(len(plan.categories), len(CategoryId))
        photos = plan.categories[CategoryId.PHOTOS]
        self.assertEqual(photos.status, PlanStatus.TRANSFERABLE)
        self.assertEqual(photos.source_method, "photos.source")
        self.assertEqual(photos.target_method, "photos.target")
        self.assertTrue(plan.model_dump(mode="json")["created_at"].endswith("Z"))

    def test_plan_exposes_role_specific_permissions_and_api_limits(self) -> None:
        source = capability_manifest(device=DeviceInfo(manufacturer="Samsung", model="Source", sdk=34, fingerprint="source"))
        source.categories[CategoryId.CONTACTS] = CapabilityEntry(
            tier=CATEGORY_TIER[CategoryId.CONTACTS],
            status=CapabilityStatus.AVAILABLE,
            method="ContactsContract",
            permission_required=["android.permission.READ_CONTACTS"],
            reason=ReasonCode.OK,
        )
        old_target = capability_manifest(
            device=DeviceInfo(manufacturer="Xiaomi", model="Target", sdk=28, fingerprint="target")
        )
        plan = build_transfer_plan(source, old_target)

        contacts = plan.categories[CategoryId.CONTACTS]
        self.assertEqual(contacts.source_permissions, ["android.permission.READ_CONTACTS"])
        self.assertEqual(contacts.target_permissions, ["android.permission.WRITE_CONTACTS"])
        photos = plan.categories[CategoryId.PHOTOS]
        self.assertEqual(photos.target_permissions, ["android.permission.WRITE_EXTERNAL_STORAGE"])
        self.assertTrue(any("API 26–28" in note and "WRITE_EXTERNAL_STORAGE" in note for note in photos.preflight_notes))
        self.assertEqual(
            plan.categories[CategoryId.APKS].target_permissions,
            ["android.permission.REQUEST_INSTALL_PACKAGES"],
        )

    def test_partial_source_or_target_produces_partial_with_action(self) -> None:
        source = capability_manifest(
            {CategoryId.CONTACTS: (CapabilityStatus.PARTIAL, ReasonCode.USER_ACTION_REQUIRED, "Grant contacts permission.")}
        )
        target = capability_manifest(device=DEVICE_TARGET)
        plan = build_transfer_plan(source, target)
        contacts = plan.categories[CategoryId.CONTACTS]
        self.assertEqual(contacts.status, PlanStatus.PARTIAL)
        self.assertEqual(contacts.reason, ReasonCode.USER_ACTION_REQUIRED)
        self.assertEqual(contacts.user_action, "Grant contacts permission.")

    def test_partial_user_action_uses_generic_text_only_without_specific_fallback(self) -> None:
        source = capability_manifest(
            {CategoryId.CONTACTS: (CapabilityStatus.PARTIAL, ReasonCode.USER_ACTION_REQUIRED, None)}
        )
        target = capability_manifest(device=DEVICE_TARGET)
        contacts = build_transfer_plan(source, target).categories[CategoryId.CONTACTS]
        self.assertEqual(contacts.user_action, "Complete the displayed Android system prompt or picker.")

    def test_unavailable_reason_priority_preserves_both_device_reasons(self) -> None:
        source = capability_manifest(
            {CategoryId.PHOTOS: (CapabilityStatus.PARTIAL, ReasonCode.PERMISSION_DENIED, "Grant read permission.")}
        )
        target = capability_manifest(
            {CategoryId.PHOTOS: (CapabilityStatus.UNAVAILABLE, ReasonCode.UNSUPPORTED, None)},
            device=DEVICE_TARGET,
        )
        photos = build_transfer_plan(source, target).categories[CategoryId.PHOTOS]
        self.assertEqual(photos.status, PlanStatus.UNAVAILABLE)
        self.assertEqual(photos.reason, ReasonCode.PERMISSION_DENIED)
        self.assertEqual(photos.source_reason, ReasonCode.PERMISSION_DENIED)
        self.assertEqual(photos.target_reason, ReasonCode.UNSUPPORTED)

    def test_inactive_shizuku_is_partial_but_active_shizuku_requires_reprobe(self) -> None:
        blocked = {
            CategoryId.SETTINGS: (CapabilityStatus.UNAVAILABLE, ReasonCode.SHIZUKU_REQUIRED, "Activate Shizuku."),
        }
        source = capability_manifest(blocked)
        target = capability_manifest(blocked, device=DEVICE_TARGET)
        inactive = build_transfer_plan(source, target).categories[CategoryId.SETTINGS]
        self.assertEqual(inactive.status, PlanStatus.PARTIAL)
        self.assertEqual(inactive.reason, ReasonCode.SHIZUKU_REQUIRED)
        self.assertIn("Activate Shizuku", inactive.user_action)
        active = build_transfer_plan(source, target, shizuku_active=True).categories[CategoryId.SETTINGS]
        self.assertEqual(active.status, PlanStatus.UNAVAILABLE)


if __name__ == "__main__":
    unittest.main()
