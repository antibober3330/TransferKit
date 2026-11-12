"""Read-only Android capability probes for the Windows ADB station."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from time import monotonic

from .contracts import (
    CATEGORY_TIER,
    CapabilityEntry,
    CapabilityManifest,
    CapabilityStatus,
    CategoryId,
    ReasonCode,
)
from .device_detect import AdbClient, AdbError, DeviceSnapshot
from .vendor_adapters import DEFAULT_VENDOR_ADAPTERS, VendorAdapterRegistry


@dataclass(frozen=True, slots=True)
class ReadOnlyProbe:
    category_id: CategoryId
    method: str
    command: tuple[str, ...]
    permissions: tuple[str, ...]
    fallback: str
    partial_reason: ReasonCode | None = None
    quirk: str | None = None


def _media_permissions(sdk: int, kind: str) -> tuple[str, ...]:
    if sdk >= 33:
        return (f"android.permission.READ_MEDIA_{kind}",)
    return ("android.permission.READ_EXTERNAL_STORAGE",)


def _probes_for(sdk: int) -> tuple[ReadOnlyProbe, ...]:
    content = ("content", "query", "--projection", "_id", "--limit", "1")
    return (
        ReadOnlyProbe(
            CategoryId.PHOTOS,
            "MediaStore.Images",
            (*content[:2], "--uri", "content://media/external/images/media", *content[2:]),
            _media_permissions(sdk, "IMAGES"),
            "Grant the listed media permission to the Android client.",
        ),
        ReadOnlyProbe(
            CategoryId.VIDEOS,
            "MediaStore.Video",
            (*content[:2], "--uri", "content://media/external/video/media", *content[2:]),
            _media_permissions(sdk, "VIDEO"),
            "Grant the listed media permission to the Android client.",
        ),
        ReadOnlyProbe(
            CategoryId.AUDIO,
            "MediaStore.Audio",
            (*content[:2], "--uri", "content://media/external/audio/media", *content[2:]),
            _media_permissions(sdk, "AUDIO"),
            "Grant the listed media permission to the Android client.",
        ),
        ReadOnlyProbe(
            CategoryId.DOCUMENTS,
            "MediaStore.Files/StorageAccessFramework",
            (*content[:2], "--uri", "content://media/external/file", *content[2:]),
            (),
            "Use the Android system document picker (SAF) for user-selected files.",
            partial_reason=ReasonCode.USER_ACTION_REQUIRED,
        ),
        ReadOnlyProbe(
            CategoryId.DOWNLOADS,
            "MediaStore.Downloads/StorageAccessFramework",
            (*content[:2], "--uri", "content://media/external/downloads", *content[2:]),
            (),
            "Use the Android system document picker (SAF) for user-selected downloads.",
            partial_reason=ReasonCode.USER_ACTION_REQUIRED,
        ),
        ReadOnlyProbe(
            CategoryId.CONTACTS,
            "ContactsContract",
            (*content[:2], "--uri", "content://com.android.contacts/contacts", *content[2:]),
            ("android.permission.READ_CONTACTS",),
            "Grant READ_CONTACTS; restore also requires WRITE_CONTACTS.",
        ),
        ReadOnlyProbe(
            CategoryId.CALENDAR,
            "CalendarContract",
            (*content[:2], "--uri", "content://com.android.calendar/events", *content[2:]),
            ("android.permission.READ_CALENDAR",),
            "Grant READ_CALENDAR; restore also requires WRITE_CALENDAR.",
        ),
        ReadOnlyProbe(
            CategoryId.SMS,
            "Telephony.Sms",
            (*content[:2], "--uri", "content://sms", *content[2:]),
            ("android.permission.READ_SMS",),
            "Sideload the app and request SMS permissions/default SMS role where Android allows it.",
            partial_reason=ReasonCode.USER_ACTION_REQUIRED,
        ),
        ReadOnlyProbe(
            CategoryId.CALL_LOG,
            "CallLog.Calls",
            (*content[:2], "--uri", "content://call_log/calls", *content[2:]),
            ("android.permission.READ_CALL_LOG",),
            "Sideload the app and request call-log permissions where Android allows it.",
            partial_reason=ReasonCode.USER_ACTION_REQUIRED,
        ),
        ReadOnlyProbe(
            CategoryId.APKS,
            "PackageManager",
            ("pm", "list", "packages", "-3"),
            ("android.permission.QUERY_ALL_PACKAGES",) if sdk >= 30 else (),
            "Each APK needs a separate install confirmation; split/system APKs may be incomplete.",
            partial_reason=ReasonCode.SPLIT_APK,
        ),
        ReadOnlyProbe(
            CategoryId.SETTINGS,
            "Settings.System",
            ("settings", "get", "system", "screen_brightness"),
            ("android.permission.WRITE_SETTINGS",),
            "Enable Shizuku or Device Owner for restricted settings; Secure/Global settings remain limited.",
            partial_reason=ReasonCode.SHIZUKU_REQUIRED,
        ),
    )


def _unavailable_entries() -> dict[CategoryId, CapabilityEntry]:
    entries: dict[CategoryId, tuple[ReasonCode, str, str]] = {
        CategoryId.APP_DATA: (
            ReasonCode.UNSUPPORTED,
            "none",
            "No public third-party API can read another app's private data; Android sandbox is enforced.",
        ),
        CategoryId.WIFI_PASSWORDS: (
            ReasonCode.SHIZUKU_REQUIRED,
            "none",
            "Passwords are not queried; Shizuku may expose only methods allowed by this Android build.",
        ),
        CategoryId.LAUNCHER_LAYOUT: (
            ReasonCode.OEM_RESTRICTION,
            "none",
            "No portable public launcher-layout API is available.",
        ),
        CategoryId.ACCOUNTS: (
            ReasonCode.OEM_RESTRICTION,
            "none",
            "Third-party APKs lack the OEM-signed system privileges used by device migration tools.",
        ),
        CategoryId.DRM: (
            ReasonCode.UNSUPPORTED,
            "none",
            "DRM keys and protected offline content are not exportable through public APIs.",
        ),
        CategoryId.BANKING_APPS: (
            ReasonCode.OEM_RESTRICTION,
            "none",
            "Banking app secrets and device bindings must be reactivated in the app.",
        ),
        CategoryId.TWO_FACTOR_TOKENS: (
            ReasonCode.USER_ACTION_REQUIRED,
            "none",
            "Use the authenticator's own supported export or recovery flow.",
        ),
    }
    return {
        category_id: CapabilityEntry(
            tier=CATEGORY_TIER[category_id],
            status=CapabilityStatus.UNAVAILABLE,
            method=method,
            permission_required=[],
            fallback=fallback,
            reason=reason,
            evidence=["official_method_not_available_to_this_probe"],
        )
        for category_id, (reason, method, fallback) in entries.items()
    }


def probe_capabilities(
    client: AdbClient,
    device: DeviceSnapshot,
    command_timeout_seconds: float = 2.0,
    adapter_registry: VendorAdapterRegistry = DEFAULT_VENDOR_ADAPTERS,
) -> CapabilityManifest:
    """Probe known providers with read-only commands and return all category results.

    No item contents or setting values are retained. The only setting queried is
    the non-sensitive `screen_brightness` key. Each shell invocation is bounded
    by `command_timeout_seconds`.
    """
    if command_timeout_seconds <= 0 or command_timeout_seconds > 5:
        raise ValueError("command_timeout_seconds must be between 0 and 5 seconds")
    started = monotonic()
    categories = _unavailable_entries()
    for probe in _probes_for(device.info.sdk):
        try:
            output = client.run_shell(
                device.serial,
                *probe.command,
                timeout=command_timeout_seconds,
            )
            no_data = "no result found" in output.lower() or (
                probe.category_id == CategoryId.APKS and not output.strip()
            )
            reason = probe.partial_reason or (ReasonCode.NO_DATA if no_data else ReasonCode.OK)
            status = CapabilityStatus.PARTIAL if probe.partial_reason else CapabilityStatus.AVAILABLE
            evidence = ["adb_read_only_probe_succeeded"]
            if no_data:
                evidence.append("probe_returned_no_items")
            entry = CapabilityEntry(
                tier=CATEGORY_TIER[probe.category_id],
                status=status,
                method=probe.method,
                permission_required=list(probe.permissions),
                fallback=probe.fallback,
                reason=reason,
                evidence=evidence,
                quirk=probe.quirk,
            )
        except AdbError as exc:
            if exc.reason == ReasonCode.PERMISSION_DENIED:
                status = CapabilityStatus.PARTIAL
                fallback = probe.fallback
            else:
                status = CapabilityStatus.UNAVAILABLE
                fallback = probe.fallback if exc.reason == ReasonCode.PROBE_TIMEOUT else None
            entry = CapabilityEntry(
                tier=CATEGORY_TIER[probe.category_id],
                status=status,
                method=probe.method,
                permission_required=list(probe.permissions),
                fallback=fallback,
                reason=exc.reason,
                evidence=["adb_read_only_probe_failed"],
            )
        categories[probe.category_id] = entry

    # Curated OEM notes annotate probe output; only the read-only runtime probe
    # determines status, method, permission, reason and fallback.
    categories = adapter_registry.enrich_capabilities(device.info, categories)

    duration_ms = max(0, round((monotonic() - started) * 1000))
    return CapabilityManifest(
        version="1.0",
        generated_at=datetime.now(timezone.utc),
        device=device.info,
        probe_duration_ms=duration_ms,
        categories=categories,
    )
