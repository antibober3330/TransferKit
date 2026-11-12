"""Contracts and deterministic registry for read-only vendor adapters."""

from __future__ import annotations

from typing import Mapping, Protocol, runtime_checkable

from ..contracts import CATEGORY_TIER, CapabilityEntry, CategoryId, DeviceInfo


@runtime_checkable
class VendorAdapter(Protocol):
    """A capability-only extension point; implementations must not transfer data."""

    @property
    def name(self) -> str: ...

    def matches(self, device_info: DeviceInfo) -> bool: ...

    def capabilities(self, device_info: DeviceInfo) -> Mapping[CategoryId, CapabilityEntry]: ...

    def quirks(self) -> Mapping[str, str]: ...


class GenericAndroidAdapter:
    """Fallback adapter which contributes no OEM-specific capability claims."""

    name = "generic-android"

    def matches(self, device_info: DeviceInfo) -> bool:
        del device_info
        return True

    def capabilities(self, device_info: DeviceInfo) -> Mapping[CategoryId, CapabilityEntry]:
        del device_info
        return {}

    def quirks(self) -> Mapping[str, str]:
        return {}


class VendorAdapterRegistry:
    """Select adapters in caller-supplied order with a guaranteed generic fallback."""

    def __init__(self, adapters: tuple[VendorAdapter, ...] | list[VendorAdapter] = ()):
        names: set[str] = set()
        validated: list[VendorAdapter] = []
        for adapter in adapters:
            if not isinstance(adapter, VendorAdapter):
                raise TypeError("adapter does not implement the VendorAdapter protocol")
            name = adapter.name.strip()
            if not name or name in names:
                raise ValueError("vendor adapter names must be non-empty and unique")
            names.add(name)
            validated.append(adapter)
        self._adapters = tuple(validated)
        self._generic = GenericAndroidAdapter()

    @property
    def adapters(self) -> tuple[VendorAdapter, ...]:
        return self._adapters

    def for_device(self, device_info: DeviceInfo) -> VendorAdapter:
        for adapter in self._adapters:
            if adapter.matches(device_info):
                return adapter
        return self._generic

    def capability_hints(self, device_info: DeviceInfo) -> dict[CategoryId, CapabilityEntry]:
        """Get and validate supplemental records; this never performs a device operation."""
        adapter = self.for_device(device_info)
        records = dict(adapter.capabilities(device_info))
        if len(records) != len(set(records)):
            raise ValueError("vendor adapter returned duplicate category records")
        for category_id, entry in records.items():
            if not isinstance(category_id, CategoryId) or not isinstance(entry, CapabilityEntry):
                raise TypeError("vendor capability records must map CategoryId to CapabilityEntry")
            if entry.tier != CATEGORY_TIER[category_id]:
                raise ValueError(f"vendor capability has an incorrect tier for {category_id.value}")
            if not entry.evidence:
                raise ValueError(f"vendor capability for {category_id.value} requires evidence")
        quirks = adapter.quirks()
        if len(quirks) > len(CategoryId):
            raise ValueError("vendor adapter returned too many quirk records")
        for key, value in quirks.items():
            if not isinstance(key, str) or key not in {item.value for item in CategoryId} | {"general"}:
                raise ValueError("quirk keys must be category IDs or 'general'")
            if not isinstance(value, str) or not value or len(value) > 512:
                raise ValueError("quirk values must be non-empty strings of at most 512 characters")
        return records

    def enrich_capabilities(
        self,
        device_info: DeviceInfo,
        categories: Mapping[CategoryId, CapabilityEntry],
    ) -> dict[CategoryId, CapabilityEntry]:
        """Attach curated quirk notes without changing probe status/reason/method."""
        self.capability_hints(device_info)  # Validate catalog output before attaching it.
        result = dict(categories)
        adapter = self.for_device(device_info)
        for category_name, note in adapter.quirks().items():
            if category_name == "general":
                continue
            category_id = CategoryId(category_name)
            entry = result.get(category_id)
            if entry is None or entry.quirk:
                continue
            result[category_id] = entry.model_copy(update={"quirk": note})
        return result
