"""Build an honest per-category plan from two runtime capability manifests."""

from __future__ import annotations

from datetime import datetime, timezone

from .contracts import (
    CATEGORY_TIER,
    CapabilityManifest,
    CapabilityStatus,
    CategoryId,
    PlanEntry,
    PlanStatus,
    ReasonCode,
    TransferPlan,
)


_UNAVAILABLE_REASON_PRIORITY = (
    ReasonCode.PERMISSION_DENIED,
    ReasonCode.UNSUPPORTED,
    ReasonCode.UNAVAILABLE,
)


def _reason_for_unavailable(source_reason: ReasonCode, target_reason: ReasonCode) -> ReasonCode:
    reasons = (source_reason, target_reason)
    for candidate in _UNAVAILABLE_REASON_PRIORITY:
        if candidate in reasons:
            return candidate
    for candidate in reasons:
        if candidate not in (ReasonCode.OK, ReasonCode.NO_DATA):
            return candidate
    return ReasonCode.UNAVAILABLE


def _reason_for_partial(source_reason: ReasonCode, target_reason: ReasonCode) -> ReasonCode:
    reasons = (source_reason, target_reason)
    for candidate in (
        ReasonCode.PERMISSION_DENIED,
        ReasonCode.SHIZUKU_REQUIRED,
        ReasonCode.DEVICE_OWNER_REQUIRED,
        ReasonCode.USER_ACTION_REQUIRED,
        ReasonCode.INSTALL_CONFIRMATION_REQUIRED,
        ReasonCode.SPLIT_APK,
        ReasonCode.OEM_RESTRICTION,
    ):
        if candidate in reasons:
            return candidate
    for candidate in reasons:
        if candidate not in (ReasonCode.OK, ReasonCode.NO_DATA):
            return candidate
    return ReasonCode.UNAVAILABLE


def _shizuku_only_block(
    source_status: CapabilityStatus,
    source_reason: ReasonCode,
    target_status: CapabilityStatus,
    target_reason: ReasonCode,
    shizuku_active: bool,
) -> bool:
    if shizuku_active or ReasonCode.SHIZUKU_REQUIRED not in (source_reason, target_reason):
        return False
    return all(
        status != CapabilityStatus.UNAVAILABLE or reason == ReasonCode.SHIZUKU_REQUIRED
        for status, reason in ((source_status, source_reason), (target_status, target_reason))
    )


def _user_action(
    reason: ReasonCode,
    source_fallback: str | None,
    target_fallback: str | None,
) -> str | None:
    specific_fallback = source_fallback or target_fallback
    if specific_fallback:
        return specific_fallback

    built_in = {
        ReasonCode.SHIZUKU_REQUIRED: "Activate Shizuku on the required device(s), then run the capability probe again.",
        ReasonCode.DEVICE_OWNER_REQUIRED: "Set up Device Owner using the documented Android provisioning flow, then probe again.",
        ReasonCode.PERMISSION_DENIED: "Grant the listed permission on the affected device(s), then probe again.",
        ReasonCode.USER_ACTION_REQUIRED: "Complete the displayed Android system prompt or picker.",
        ReasonCode.INSTALL_CONFIRMATION_REQUIRED: "Confirm each APK installation in the Android system dialog.",
        ReasonCode.SPLIT_APK: "Review package splits; install only the complete APK set with explicit confirmation.",
    }
    return built_in.get(reason)


def _target_permissions(category_id: CategoryId, sdk: int) -> list[str]:
    """Permissions needed by the current Android restore path on the target."""
    if category_id in (CategoryId.PHOTOS, CategoryId.VIDEOS, CategoryId.AUDIO, CategoryId.DOWNLOADS):
        # MediaStore owns inserted rows on Android 10+; older APIs need the
        # legacy public-storage write permission for shared media and Downloads.
        return ["android.permission.WRITE_EXTERNAL_STORAGE"] if sdk <= 28 else []
    if category_id == CategoryId.CONTACTS:
        return ["android.permission.WRITE_CONTACTS"]
    if category_id == CategoryId.CALENDAR:
        return ["android.permission.WRITE_CALENDAR"]
    if category_id == CategoryId.APKS:
        return ["android.permission.REQUEST_INSTALL_PACKAGES"]
    if category_id == CategoryId.SETTINGS:
        return ["android.permission.WRITE_SETTINGS"]
    return []


def _preflight_notes(category_id: CategoryId, source_sdk: int, target_sdk: int) -> list[str]:
    notes: list[str] = []
    if category_id == CategoryId.PHOTOS and source_sdk >= 34:
        notes.append("Android 14+: photo access may be limited to items selected in the system picker.")
    if category_id == CategoryId.VIDEOS and source_sdk >= 34:
        notes.append("Android 14+: video access may be limited to items selected in the system picker.")
    if category_id in (CategoryId.PHOTOS, CategoryId.VIDEOS, CategoryId.AUDIO, CategoryId.DOWNLOADS) and target_sdk < 29:
        notes.append(
            "Android 8–9 (API 26–28) restores through public storage and requires WRITE_EXTERNAL_STORAGE; "
            "Android 10+ uses the scoped MediaStore restore path."
        )
    if category_id in (CategoryId.DOCUMENTS, CategoryId.DOWNLOADS):
        notes.append("Access is granted to user-selected files through Android's system document picker (SAF).")
    if category_id == CategoryId.APKS:
        notes.append("Every app install still needs a separate Android system confirmation.")
    if category_id == CategoryId.SETTINGS:
        notes.append("WRITE_SETTINGS uses special app access; Secure and Global settings are outside the allowlist.")
    return notes


def build_transfer_plan(
    source: CapabilityManifest,
    target: CapabilityManifest,
    *,
    shizuku_active: bool = False,
) -> TransferPlan:
    """Match every category and retain both sides' reason/method details.

    Different source and target API methods are allowed: category-specific
    providers can serialize/restore through the common TransferKit protocol.
    """
    entries: dict[CategoryId, PlanEntry] = {}
    for category_id in CategoryId:
        source_capability = source.categories[category_id]
        target_capability = target.categories[category_id]
        source_status = source_capability.status
        target_status = target_capability.status
        source_reason = source_capability.reason
        target_reason = target_capability.reason

        shizuku_block = _shizuku_only_block(
            source_status,
            source_reason,
            target_status,
            target_reason,
            shizuku_active,
        )
        if shizuku_block:
            plan_status = PlanStatus.PARTIAL
            reason = ReasonCode.SHIZUKU_REQUIRED
        elif CapabilityStatus.UNAVAILABLE in (source_status, target_status):
            plan_status = PlanStatus.UNAVAILABLE
            reason = _reason_for_unavailable(source_reason, target_reason)
        elif CapabilityStatus.PARTIAL in (source_status, target_status):
            plan_status = PlanStatus.PARTIAL
            reason = _reason_for_partial(source_reason, target_reason)
        else:
            plan_status = PlanStatus.TRANSFERABLE
            reason = ReasonCode.OK

        method = f"{source_capability.method} -> {target_capability.method}"
        if plan_status == PlanStatus.PARTIAL:
            action = _user_action(reason, source_capability.fallback, target_capability.fallback)
        elif plan_status == PlanStatus.UNAVAILABLE:
            action = source_capability.fallback or target_capability.fallback
        else:
            action = None

        entries[category_id] = PlanEntry(
            category_id=category_id,
            tier=CATEGORY_TIER[category_id],
            source_status=source_status,
            target_status=target_status,
            source_reason=source_reason,
            target_reason=target_reason,
            status=plan_status,
            reason=reason,
            source_method=source_capability.method,
            target_method=target_capability.method,
            method=method,
            source_permissions=list(source_capability.permission_required),
            target_permissions=_target_permissions(category_id, target.device.sdk),
            preflight_notes=_preflight_notes(category_id, source.device.sdk, target.device.sdk),
            user_action=action,
        )

    return TransferPlan(
        version="1.0",
        created_at=datetime.now(timezone.utc),
        source_device=source.device,
        target_device=target.device,
        categories=entries,
    )
