import unittest
from datetime import datetime, timezone

from transferkit.capability_probe import probe_capabilities
from transferkit.contracts import (
    CapabilityStatus,
    CategoryId,
    DeviceInfo,
    ReasonCode,
    validate_document,
)
from transferkit.device_detect import AdbError, DeviceSnapshot


class FakeAdbClient:
    def __init__(self, permission_denied: set[str] | None = None):
        self.calls: list[tuple[str, tuple[str, ...], float | None]] = []
        self.permission_denied = permission_denied or set()

    def run_shell(self, serial: str, *args: str, timeout: float | None = None) -> str:
        self.calls.append((serial, args, timeout))
        if args and args[0] == "content" and args[3] in self.permission_denied:
            raise AdbError(ReasonCode.PERMISSION_DENIED, "permission denied")
        if args and args[0] == "content":
            return "No result found."
        if args and args[0] == "pm":
            return ""
        return "120"


def make_device() -> DeviceSnapshot:
    return DeviceSnapshot(
        serial="TEST123",
        info=DeviceInfo(
            manufacturer="Example",
            model="Android Test Device",
            sdk=34,
            fingerprint="example/test/fingerprint",
        ),
        discovered_at=datetime.now(timezone.utc),
    )


class CapabilityProbeTests(unittest.TestCase):
    def test_probe_returns_every_category_and_only_read_only_commands(self) -> None:
        client = FakeAdbClient()
        manifest = probe_capabilities(client, make_device())
        self.assertEqual(set(manifest.categories), set(CategoryId))
        self.assertEqual(manifest.categories[CategoryId.PHOTOS].reason, ReasonCode.NO_DATA)
        self.assertEqual(manifest.categories[CategoryId.SETTINGS].status, CapabilityStatus.PARTIAL)
        self.assertEqual(manifest.categories[CategoryId.ACCOUNTS].reason, ReasonCode.OEM_RESTRICTION)
        self.assertLessEqual(manifest.probe_duration_ms, 30_000)
        self.assertTrue(all(timeout == 2.0 for _, _, timeout in client.calls))
        for _, command, _ in client.calls:
            self.assertIn(command[0], {"content", "pm", "settings"})
            self.assertNotIn("insert", command)
            self.assertNotIn("delete", command)
            self.assertNotIn("put", command)
            self.assertNotIn("reboot", command)
            self.assertNotIn("su", command)
        validate_document(manifest.model_dump(mode="json"), "capability.schema.json")

    def test_permission_denial_is_partial_with_recovery_action(self) -> None:
        client = FakeAdbClient(permission_denied={"content://media/external/images/media"})
        manifest = probe_capabilities(client, make_device())
        photos = manifest.categories[CategoryId.PHOTOS]
        self.assertEqual(photos.status, CapabilityStatus.PARTIAL)
        self.assertEqual(photos.reason, ReasonCode.PERMISSION_DENIED)
        self.assertIsNotNone(photos.fallback)

    def test_platform_limited_and_tier_g_categories_have_specific_unavailability_reasons(self) -> None:
        manifest = probe_capabilities(FakeAdbClient(), make_device())
        expected_reasons = {
            CategoryId.APP_DATA: ReasonCode.UNSUPPORTED,
            CategoryId.WIFI_PASSWORDS: ReasonCode.SHIZUKU_REQUIRED,
            CategoryId.LAUNCHER_LAYOUT: ReasonCode.OEM_RESTRICTION,
            CategoryId.ACCOUNTS: ReasonCode.OEM_RESTRICTION,
            CategoryId.DRM: ReasonCode.UNSUPPORTED,
            CategoryId.BANKING_APPS: ReasonCode.OEM_RESTRICTION,
            CategoryId.TWO_FACTOR_TOKENS: ReasonCode.USER_ACTION_REQUIRED,
            CategoryId.DOCUMENTS: ReasonCode.USER_ACTION_REQUIRED,
            CategoryId.DOWNLOADS: ReasonCode.USER_ACTION_REQUIRED,
            CategoryId.SMS: ReasonCode.USER_ACTION_REQUIRED,
            CategoryId.CALL_LOG: ReasonCode.USER_ACTION_REQUIRED,
            CategoryId.APKS: ReasonCode.SPLIT_APK,
            CategoryId.SETTINGS: ReasonCode.SHIZUKU_REQUIRED,
        }
        unavailable_categories = {
            CategoryId.APP_DATA,
            CategoryId.WIFI_PASSWORDS,
            CategoryId.LAUNCHER_LAYOUT,
            CategoryId.ACCOUNTS,
            CategoryId.DRM,
            CategoryId.BANKING_APPS,
            CategoryId.TWO_FACTOR_TOKENS,
        }
        for category_id, reason in expected_reasons.items():
            with self.subTest(category=category_id):
                entry = manifest.categories[category_id]
                self.assertEqual(entry.reason, reason)
                self.assertEqual(
                    entry.status,
                    CapabilityStatus.UNAVAILABLE if category_id in unavailable_categories else CapabilityStatus.PARTIAL,
                )
                self.assertTrue(entry.method)
                self.assertTrue(entry.fallback)

    def test_vendor_quirk_is_attached_without_changing_runtime_probe_result(self) -> None:
        device = make_device().model_copy(
            update={
                "info": DeviceInfo(
                    manufacturer="Samsung",
                    model="Galaxy Test",
                    sdk=34,
                    fingerprint="samsung/test/fingerprint",
                )
            }
        )
        manifest = probe_capabilities(FakeAdbClient(), device)
        photos = manifest.categories[CategoryId.PHOTOS]

        self.assertEqual(photos.status, CapabilityStatus.AVAILABLE)
        self.assertEqual(photos.reason, ReasonCode.NO_DATA)
        self.assertIn("MediaStore", photos.quirk)


if __name__ == "__main__":
    unittest.main()
