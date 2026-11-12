"""Curated OEM quirk catalog. Static manufacturer data never claims runtime access."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping

from ..contracts import CapabilityEntry, CategoryId, DeviceInfo


@dataclass(frozen=True, slots=True)
class CatalogVendorAdapter:
    name: str
    manufacturer_aliases: tuple[str, ...]
    quirk_notes: Mapping[str, str]

    def matches(self, device_info: DeviceInfo) -> bool:
        manufacturer = device_info.manufacturer.strip().casefold()
        return any(manufacturer == alias.casefold() for alias in self.manufacturer_aliases)

    def capabilities(self, device_info: DeviceInfo) -> Mapping[CategoryId, CapabilityEntry]:
        """Manufacturer alone is not runtime evidence, so catalog adapters add no claims."""
        del device_info
        return {}

    def quirks(self) -> Mapping[str, str]:
        return dict(self.quirk_notes)


class SamsungAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "samsung",
            ("Samsung", "Samsung Electronics"),
            {
                "photos": "Samsung Gallery album organization may use OEM metadata; media access remains through standard MediaStore.",
                "calendar": "Calendar access uses CalendarContract; account and calendar availability must be checked at runtime.",
            },
        )


class XiaomiAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "xiaomi",
            ("Xiaomi", "Redmi", "POCO"),
            {
                "photos": "Some MIUI builds may show additional per-app or special-access prompts; verify permissions at runtime.",
                "settings": "MIUI settings behavior varies by build; only report settings confirmed by the active probe.",
            },
        )


class MotorolaAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "motorola",
            ("Motorola",),
            {"settings": "Settings access follows Android API and role restrictions; verify on the connected build."},
        )


class GooglePixelAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "google-pixel",
            ("Google Pixel", "Google"),
            {"general": "Device-to-device setup availability depends on Android version and device setup state."},
        )

    def matches(self, device_info: DeviceInfo) -> bool:
        manufacturer = device_info.manufacturer.strip().casefold()
        if manufacturer == "google pixel":
            return True
        return manufacturer == "google" and (
            "pixel" in device_info.model.casefold() or "google/" in device_info.fingerprint.casefold()
        )


class OppoAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "oppo",
            ("OPPO",),
            {"settings": "ColorOS settings behavior varies by release; runtime probe and Android role checks remain authoritative."},
        )


class RealmeAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "realme",
            ("realme", "Realme"),
            {"settings": "realme UI settings behavior varies by release; runtime probe and Android role checks remain authoritative."},
        )


class OnePlusAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "oneplus",
            ("OnePlus",),
            {"settings": "OxygenOS settings behavior varies by release; runtime probe and Android role checks remain authoritative."},
        )


class HonorAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "honor",
            ("HONOR", "Honor"),
            {
                "settings": "HONOR system settings remain subject to the active Android build and available roles.",
                "accounts": "Account access depends on system/OEM privileges and is not inferred from HMS or GMS branding.",
            },
        )


class HuaweiAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "huawei",
            ("HUAWEI", "Huawei"),
            {
                "settings": "EMUI system settings remain subject to the active Android build and available roles.",
                "accounts": "Account access depends on system/OEM privileges and is not inferred from HMS or GMS branding.",
            },
        )


class ZTEAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "zte",
            ("ZTE",),
            {"settings": "ZTE system settings may be OEM-specific; use only runtime-probed Android APIs."},
        )


class InfinixAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "infinix",
            ("Infinix",),
            {"settings": "Infinix system settings may be OEM-specific; use only runtime-probed Android APIs."},
        )


class TecnoAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "tecno",
            ("TECNO", "Tecno"),
            {"settings": "TECNO system settings may be OEM-specific; use only runtime-probed Android APIs."},
        )


class VivoAdapter(CatalogVendorAdapter):
    def __init__(self):
        super().__init__(
            "vivo",
            ("vivo", "Vivo"),
            {"settings": "vivo system settings may be OEM-specific; use only runtime-probed Android APIs."},
        )


def default_vendor_adapters() -> tuple[CatalogVendorAdapter, ...]:
    """Return specific OEM adapters in deterministic matching order."""
    return (
        SamsungAdapter(),
        XiaomiAdapter(),
        MotorolaAdapter(),
        GooglePixelAdapter(),
        OppoAdapter(),
        RealmeAdapter(),
        OnePlusAdapter(),
        HonorAdapter(),
        HuaweiAdapter(),
        ZTEAdapter(),
        InfinixAdapter(),
        TecnoAdapter(),
        VivoAdapter(),
    )


ZteAdapter = ZTEAdapter
