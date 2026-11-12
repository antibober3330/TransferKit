import unittest

from transferkit.contracts import (
    CATEGORY_TIER,
    CapabilityEntry,
    CapabilityStatus,
    CategoryId,
    DeviceInfo,
    ReasonCode,
)
from transferkit.vendor_adapters import (
    DEFAULT_VENDOR_ADAPTERS,
    GenericAndroidAdapter,
    VendorAdapterRegistry,
    default_vendor_adapters,
)


DEVICE = DeviceInfo(manufacturer="Example", model="Reference", sdk=34, fingerprint="example/device")


class SamsungFixture:
    name = "samsung-fixture"

    def matches(self, device_info: DeviceInfo) -> bool:
        return device_info.manufacturer.lower() == "samsung"

    def capabilities(self, device_info: DeviceInfo):
        return {
            CategoryId.PHOTOS: CapabilityEntry(
                tier=CATEGORY_TIER[CategoryId.PHOTOS],
                status=CapabilityStatus.AVAILABLE,
                method="MediaStore.Images",
                reason=ReasonCode.OK,
                evidence=["adapter_metadata_only"],
            )
        }

    def quirks(self):
        return {"photos": "Samsung Gallery album metadata may be stored separately."}


class VendorAdapterTests(unittest.TestCase):
    def test_registry_selects_matching_adapter_and_returns_validated_hints(self) -> None:
        registry = VendorAdapterRegistry([SamsungFixture()])
        samsung = DEVICE.model_copy(update={"manufacturer": "Samsung"})

        adapter = registry.for_device(samsung)
        hints = registry.capability_hints(samsung)

        self.assertEqual(adapter.name, "samsung-fixture")
        self.assertEqual(hints[CategoryId.PHOTOS].method, "MediaStore.Images")
        self.assertEqual(adapter.quirks()["photos"], "Samsung Gallery album metadata may be stored separately.")

    def test_unknown_manufacturer_uses_generic_adapter_without_claims(self) -> None:
        registry = VendorAdapterRegistry([SamsungFixture()])

        adapter = registry.for_device(DEVICE)

        self.assertIsInstance(adapter, GenericAndroidAdapter)
        self.assertEqual(registry.capability_hints(DEVICE), {})
        self.assertEqual(adapter.quirks(), {})

    def test_registry_rejects_duplicate_names_and_incorrect_tiers(self) -> None:
        with self.assertRaisesRegex(ValueError, "unique"):
            VendorAdapterRegistry([SamsungFixture(), SamsungFixture()])

        class IncorrectTierAdapter(SamsungFixture):
            name = "wrong-tier"

            def capabilities(self, device_info: DeviceInfo):
                return {
                    CategoryId.PHOTOS: CapabilityEntry(
                        tier=CATEGORY_TIER[CategoryId.CONTACTS],
                        status=CapabilityStatus.AVAILABLE,
                        method="invalid",
                        reason=ReasonCode.OK,
                        evidence=["fixture"],
                    )
                }

        registry = VendorAdapterRegistry([IncorrectTierAdapter()])
        with self.assertRaisesRegex(ValueError, "incorrect tier"):
            registry.capability_hints(DEVICE.model_copy(update={"manufacturer": "Samsung"}))

    def test_adapter_protocol_contains_no_transfer_operation(self) -> None:
        adapter = GenericAndroidAdapter()
        self.assertFalse(hasattr(adapter, "send_file"))
        self.assertFalse(hasattr(adapter, "restore"))

    def test_catalog_contains_all_thirteen_oems_and_matches_known_brands(self) -> None:
        adapters = default_vendor_adapters()
        self.assertEqual(len(adapters), 13)
        self.assertEqual(len({adapter.name for adapter in adapters}), 13)
        examples = (
            ("Samsung", "Galaxy"),
            ("Xiaomi", "Redmi"),
            ("Motorola", "Moto"),
            ("Google", "Pixel 9"),
            ("OPPO", "Find"),
            ("realme", "GT"),
            ("OnePlus", "12"),
            ("HONOR", "Magic"),
            ("HUAWEI", "Pura"),
            ("ZTE", "Axon"),
            ("Infinix", "Note"),
            ("TECNO", "Camon"),
            ("vivo", "X"),
        )
        for manufacturer, model in examples:
            with self.subTest(manufacturer=manufacturer):
                device = DeviceInfo(
                    manufacturer=manufacturer,
                    model=model,
                    sdk=34,
                    fingerprint=f"{manufacturer.casefold()}/test/device",
                )
                self.assertNotIsInstance(DEFAULT_VENDOR_ADAPTERS.for_device(device), GenericAndroidAdapter)


if __name__ == "__main__":
    unittest.main()
