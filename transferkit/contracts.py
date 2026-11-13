"""Typed, shared contract models for TransferKit's Python components."""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from enum import Enum, StrEnum
from pathlib import Path
from typing import Any, Literal
from uuid import UUID

from jsonschema import Draft202012Validator, FormatChecker
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_serializer, field_validator, model_validator
from referencing import Registry, Resource


class CategoryId(StrEnum):
    PHOTOS = "photos"
    VIDEOS = "videos"
    AUDIO = "audio"
    DOCUMENTS = "documents"
    DOWNLOADS = "downloads"
    CONTACTS = "contacts"
    CALENDAR = "calendar"
    SMS = "sms"
    CALL_LOG = "call_log"
    APKS = "apks"
    SETTINGS = "settings"
    APP_DATA = "app_data"
    WIFI_PASSWORDS = "wifi_passwords"
    LAUNCHER_LAYOUT = "launcher_layout"
    ACCOUNTS = "accounts"
    DRM = "drm"
    BANKING_APPS = "banking_apps"
    TWO_FACTOR_TOKENS = "two_factor_tokens"


class Tier(StrEnum):
    A = "A"
    B = "B"
    C = "C"
    D = "D"
    E = "E"
    F = "F"
    G = "G"


class CapabilityStatus(StrEnum):
    AVAILABLE = "AVAILABLE"
    PARTIAL = "PARTIAL"
    UNAVAILABLE = "UNAVAILABLE"


class PlanStatus(StrEnum):
    TRANSFERABLE = "TRANSFERABLE"
    PARTIAL = "PARTIAL"
    UNAVAILABLE = "UNAVAILABLE"


class ReportStatus(StrEnum):
    OK = "OK"
    PARTIAL = "PARTIAL"
    UNAVAILABLE = "UNAVAILABLE"
    FAILED = "FAILED"
    FAILED_INTEGRITY = "FAILED_INTEGRITY"


class ItemStatus(StrEnum):
    PENDING = "PENDING"
    TRANSFERRED = "TRANSFERRED"
    VERIFIED = "VERIFIED"
    RESTORED = "RESTORED"
    SKIPPED = "SKIPPED"
    FAILED = "FAILED"
    FAILED_INTEGRITY = "FAILED_INTEGRITY"
    SKIPPED_NO_SPACE = "SKIPPED_NO_SPACE"


class ReasonCode(StrEnum):
    OK = "OK"
    PERMISSION_DENIED = "PERMISSION_DENIED"
    UNSUPPORTED = "UNSUPPORTED"
    UNAVAILABLE = "UNAVAILABLE"
    SHIZUKU_REQUIRED = "SHIZUKU_REQUIRED"
    DEVICE_OWNER_REQUIRED = "DEVICE_OWNER_REQUIRED"
    USER_ACTION_REQUIRED = "USER_ACTION_REQUIRED"
    INSTALL_CONFIRMATION_REQUIRED = "INSTALL_CONFIRMATION_REQUIRED"
    OEM_RESTRICTION = "OEM_RESTRICTION"
    SYSTEM_APP = "SYSTEM_APP"
    SPLIT_APK = "SPLIT_APK"
    NO_DATA = "NO_DATA"
    TARGET_NO_SPACE = "TARGET_NO_SPACE"
    TARGET_SPACE_INSUFFICIENT = "TARGET_SPACE_INSUFFICIENT"
    TARGET_ITEM_TOO_LARGE = "TARGET_ITEM_TOO_LARGE"
    ALREADY_PRESENT = "ALREADY_PRESENT"
    NEAR_DUPLICATE_SKIPPED = "NEAR_DUPLICATE_SKIPPED"
    TRANSPORT_INTERRUPTED = "TRANSPORT_INTERRUPTED"
    TRANSFER_INCOMPLETE = "TRANSFER_INCOMPLETE"
    RESUME_AVAILABLE = "RESUME_AVAILABLE"
    RESUME_NOT_POSSIBLE = "RESUME_NOT_POSSIBLE"
    CHECKPOINT_EXPIRED = "CHECKPOINT_EXPIRED"
    SOURCE_DEVICE_MISMATCH = "SOURCE_DEVICE_MISMATCH"
    TARGET_DEVICE_MISMATCH = "TARGET_DEVICE_MISMATCH"
    SOURCE_MANIFEST_CHANGED = "SOURCE_MANIFEST_CHANGED"
    INCOMPATIBLE_PROTOCOL_VERSION = "INCOMPATIBLE_PROTOCOL_VERSION"
    STAGING_CORRUPTED = "STAGING_CORRUPTED"
    USER_CHOSE_RESTART = "USER_CHOSE_RESTART"
    USER_CHOSE_ABANDON = "USER_CHOSE_ABANDON"
    STAGING_CLEARED = "STAGING_CLEARED"
    USER_CHOSE_STREAMING = "USER_CHOSE_STREAMING"
    USER_CHOSE_DROP_LARGEST = "USER_CHOSE_DROP_LARGEST"
    USER_CHOSE_CLEAR_SPACE = "USER_CHOSE_CLEAR_SPACE"
    CRC32_MISMATCH = "CRC32_MISMATCH"
    SHA256_MISMATCH = "SHA256_MISMATCH"
    RESTORE_FAILED = "RESTORE_FAILED"
    IO_ERROR = "IO_ERROR"
    INVALID_MANIFEST = "INVALID_MANIFEST"
    CANCELLED = "CANCELLED"
    PROBE_TIMEOUT = "PROBE_TIMEOUT"
    DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND"
    API_UNAVAILABLE = "API_UNAVAILABLE"
    USER_DECLINED = "USER_DECLINED"
    SOURCE_READ_FAILED = "SOURCE_READ_FAILED"
    TARGET_WRITE_FAILED = "TARGET_WRITE_FAILED"
    ADB_UNAVAILABLE = "ADB_UNAVAILABLE"
    ADB_COMMAND_FAILED = "ADB_COMMAND_FAILED"
    DEVICE_UNAUTHORIZED = "DEVICE_UNAUTHORIZED"
    DEVICE_OFFLINE = "DEVICE_OFFLINE"


class FailureStage(StrEnum):
    PROBE = "PROBE"
    PLAN = "PLAN"
    MANIFEST = "MANIFEST"
    TRANSFER = "TRANSFER"
    VERIFY = "VERIFY"
    RESTORE = "RESTORE"
    POST_VERIFY = "POST_VERIFY"


class ReceiptFailureStage(StrEnum):
    RECEIVE = "RECEIVE"
    CRC32_VERIFY = "CRC32_VERIFY"
    SHA256_VERIFY = "SHA256_VERIFY"
    RESTORE = "RESTORE"
    POST_VERIFY = "POST_VERIFY"


CATEGORY_TIER: dict[CategoryId, Tier] = {
    CategoryId.PHOTOS: Tier.A,
    CategoryId.VIDEOS: Tier.A,
    CategoryId.AUDIO: Tier.A,
    CategoryId.DOCUMENTS: Tier.A,
    CategoryId.DOWNLOADS: Tier.A,
    CategoryId.CONTACTS: Tier.B,
    CategoryId.CALENDAR: Tier.B,
    CategoryId.SMS: Tier.C,
    CategoryId.CALL_LOG: Tier.C,
    CategoryId.APKS: Tier.D,
    CategoryId.SETTINGS: Tier.E,
    CategoryId.APP_DATA: Tier.F,
    CategoryId.WIFI_PASSWORDS: Tier.G,
    CategoryId.LAUNCHER_LAYOUT: Tier.G,
    CategoryId.ACCOUNTS: Tier.G,
    CategoryId.DRM: Tier.G,
    CategoryId.BANKING_APPS: Tier.G,
    CategoryId.TWO_FACTOR_TOKENS: Tier.G,
}


def format_utc_timestamp(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() != timezone.utc.utcoffset(value):
        raise ValueError("timestamp must be timezone-aware UTC")
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", use_enum_values=False)

    @field_serializer("*", when_used="json", check_fields=False)
    def serialize_utc_timestamps(self, value: Any) -> Any:
        if isinstance(value, datetime):
            return format_utc_timestamp(value)
        return value


class DeviceInfo(ContractModel):
    manufacturer: str = Field(min_length=1, max_length=128, pattern=r"\S")
    model: str = Field(min_length=1, max_length=128, pattern=r"\S")
    sdk: int = Field(ge=26, le=2_147_483_647)
    fingerprint: str = Field(min_length=1, max_length=512, pattern=r"\S")


class ManifestItem(ContractModel):
    id: str = Field(min_length=1, max_length=512, pattern=r"\S")
    size: int = Field(ge=0, le=9_007_199_254_740_991)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("metadata")
    @classmethod
    def metadata_is_json_and_bounded(cls, value: dict[str, Any]) -> dict[str, Any]:
        try:
            encoded = canonical_json_bytes(value)
        except (TypeError, ValueError) as exc:
            raise ValueError("metadata must contain canonical JSON values") from exc
        if len(encoded) > 64 * 1024:
            raise ValueError("metadata exceeds the 64 KiB per-item limit")
        return value


class ManifestCategory(ContractModel):
    provider: str = Field(min_length=1, max_length=128, pattern=r"\S")
    method: str = Field(min_length=1, max_length=128, pattern=r"\S")
    items: list[ManifestItem]

    @model_validator(mode="after")
    def unique_item_ids(self) -> ManifestCategory:
        ids = [item.id for item in self.items]
        if len(ids) != len(set(ids)):
            raise ValueError("item IDs must be unique within a category")
        return self


class TransferManifest(ContractModel):
    version: Literal["1.0"]
    transfer_id: UUID
    created_at: datetime
    source_device: DeviceInfo
    categories: dict[CategoryId, ManifestCategory] = Field(min_length=1)

    @field_validator("created_at", mode="before")
    @classmethod
    def manifest_timestamp_has_canonical_precision(cls, value: Any) -> Any:
        if isinstance(value, datetime):
            return value
        if not isinstance(value, str) or re.fullmatch(
            r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{6})?Z",
            value,
        ) is None:
            raise ValueError("created_at must use canonical UTC seconds or six-digit microseconds")
        return value

    @field_validator("transfer_id", mode="before")
    @classmethod
    def transfer_id_must_be_canonical(cls, value: Any) -> UUID:
        if isinstance(value, UUID):
            return value
        if not isinstance(value, str):
            raise ValueError("transfer_id must be a canonical lowercase UUID")
        try:
            parsed = UUID(value)
        except ValueError as exc:
            raise ValueError("transfer_id must be a canonical lowercase UUID") from exc
        if str(parsed) != value:
            raise ValueError("transfer_id must be a canonical lowercase UUID")
        return parsed

    @field_validator("created_at")
    @classmethod
    def created_at_must_be_utc(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() != timezone.utc.utcoffset(value):
            raise ValueError("created_at must be an RFC 3339 UTC timestamp")
        return value

    @model_validator(mode="after")
    def wire_item_ids_must_be_collision_free(self) -> TransferManifest:
        # Keep sender-side validation aligned with the receiver's 32-bit wire IDs.
        from .integrity import id_hash

        seen: set[bytes] = set()
        for category_id, category in self.categories.items():
            category_hash = id_hash(category_id.value)
            for item in category.items:
                wire_key = category_hash + id_hash(item.id)
                if wire_key in seen:
                    raise ValueError("manifest contains a wire ID hash collision")
                seen.add(wire_key)
        return self


class CapabilityEntry(ContractModel):
    tier: Tier
    status: CapabilityStatus
    method: str = Field(min_length=1, max_length=256)
    permission_required: list[str] = Field(default_factory=list)
    fallback: str | None = Field(default=None, max_length=512)
    reason: ReasonCode
    evidence: list[str] = Field(default_factory=list, max_length=64)
    quirk: str | None = Field(default=None, max_length=512)

    @field_validator("permission_required")
    @classmethod
    def unique_permissions(cls, value: list[str]) -> list[str]:
        if len(value) != len(set(value)):
            raise ValueError("permission_required must not contain duplicates")
        return value


class CapabilityManifest(ContractModel):
    version: Literal["1.0"]
    generated_at: datetime
    device: DeviceInfo
    probe_duration_ms: int = Field(ge=0)
    categories: dict[CategoryId, CapabilityEntry]

    @field_validator("generated_at")
    @classmethod
    def generated_at_must_be_utc(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() != timezone.utc.utcoffset(value):
            raise ValueError("generated_at must be an RFC 3339 UTC timestamp")
        return value

    @model_validator(mode="after")
    def complete_and_tier_consistent(self) -> CapabilityManifest:
        expected = set(CategoryId)
        actual = set(self.categories)
        if actual != expected:
            missing = sorted(item.value for item in expected - actual)
            extra = sorted(item.value for item in actual - expected)
            raise ValueError(f"capability categories incomplete; missing={missing}, extra={extra}")
        for category_id, entry in self.categories.items():
            if entry.tier != CATEGORY_TIER[category_id]:
                raise ValueError(f"wrong tier for category {category_id.value}")
        return self


class PlanEntry(ContractModel):
    category_id: CategoryId
    tier: Tier
    source_status: CapabilityStatus
    target_status: CapabilityStatus
    source_reason: ReasonCode
    target_reason: ReasonCode
    status: PlanStatus
    reason: ReasonCode
    source_method: str = Field(min_length=1, max_length=256)
    target_method: str = Field(min_length=1, max_length=256)
    method: str = Field(min_length=1, max_length=520)
    source_permissions: list[str] = Field(default_factory=list, max_length=16)
    target_permissions: list[str] = Field(default_factory=list, max_length=16)
    preflight_notes: list[str] = Field(default_factory=list, max_length=8)
    user_action: str | None = Field(default=None, max_length=512)

    @model_validator(mode="after")
    def tier_matches_category(self) -> PlanEntry:
        if self.tier != CATEGORY_TIER[self.category_id]:
            raise ValueError("tier does not match category_id")
        return self


class TransferPlan(ContractModel):
    version: Literal["1.0"]
    created_at: datetime
    source_device: DeviceInfo
    target_device: DeviceInfo
    categories: dict[CategoryId, PlanEntry]

    @field_validator("created_at")
    @classmethod
    def created_at_must_be_utc(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() != timezone.utc.utcoffset(value):
            raise ValueError("created_at must be an RFC 3339 UTC timestamp")
        return value

    @model_validator(mode="after")
    def complete_and_key_consistent(self) -> TransferPlan:
        expected = set(CategoryId)
        actual = set(self.categories)
        if actual != expected:
            missing = sorted(item.value for item in expected - actual)
            extra = sorted(item.value for item in actual - expected)
            raise ValueError(f"transfer plan categories incomplete; missing={missing}, extra={extra}")
        for category_id, entry in self.categories.items():
            if entry.category_id != category_id:
                raise ValueError(f"plan entry key does not match {category_id.value}")
        return self


class ReportFailure(ContractModel):
    item_id: str = Field(min_length=1, max_length=512)
    stage: FailureStage
    reason: ReasonCode
    display_message: str | None = Field(default=None, max_length=512)


class ReportCategory(ContractModel):
    category_id: CategoryId
    tier: Tier
    source_count: int = Field(ge=0)
    manifest_count: int = Field(ge=0)
    transferred_count: int = Field(ge=0)
    verified_count: int = Field(ge=0)
    restored_count: int = Field(ge=0)
    deduplicated_count: int = Field(default=0, ge=0)
    deduplicated_bytes: int = Field(default=0, ge=0, le=9_007_199_254_740_991)
    plan_status: PlanStatus
    status: ReportStatus
    reason: ReasonCode
    failures: list[ReportFailure] = Field(default_factory=list)

    @model_validator(mode="after")
    def counts_and_tier_are_consistent(self) -> ReportCategory:
        if self.tier != CATEGORY_TIER[self.category_id]:
            raise ValueError("tier does not match category_id")
        if self.manifest_count > self.source_count:
            raise ValueError("manifest_count cannot exceed source_count")
        if self.transferred_count > self.manifest_count:
            raise ValueError("transferred_count cannot exceed manifest_count")
        if self.deduplicated_count > self.manifest_count - self.transferred_count:
            raise ValueError("deduplicated_count exceeds items not transferred")
        if self.verified_count > self.transferred_count:
            raise ValueError("verified_count cannot exceed transferred_count")
        if self.restored_count > self.verified_count:
            raise ValueError("restored_count cannot exceed verified_count")
        if self.status == ReportStatus.FAILED_INTEGRITY and not any(
            failure.reason == ReasonCode.SHA256_MISMATCH for failure in self.failures
        ):
            raise ValueError("FAILED_INTEGRITY requires a SHA256_MISMATCH failure")
        if self.status != ReportStatus.FAILED_INTEGRITY and any(
            failure.reason == ReasonCode.SHA256_MISMATCH for failure in self.failures
        ):
            raise ValueError("SHA256_MISMATCH requires FAILED_INTEGRITY status")
        return self


class ReportDecision(ContractModel):
    reason: ReasonCode
    item_ids: list[str] = Field(default_factory=list, max_length=100_000)
    category_id: CategoryId | None = None


class ReportSummary(ContractModel):
    total_categories: int = Field(ge=0)
    transferable: int = Field(ge=0)
    partial: int = Field(ge=0)
    unavailable: int = Field(ge=0)
    failed_integrity: int = Field(ge=0)


class TransferReport(ContractModel):
    version: Literal["1.0"]
    transfer_id: UUID
    started_at: datetime
    finished_at: datetime
    mode: Literal["quick", "full", "professional"]
    source_device: DeviceInfo
    target_device: DeviceInfo
    categories: list[ReportCategory]
    summary: ReportSummary
    decisions: list[ReportDecision] = Field(default_factory=list)

    @field_validator("started_at", "finished_at")
    @classmethod
    def report_times_must_be_utc(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() != timezone.utc.utcoffset(value):
            raise ValueError("report timestamps must be RFC 3339 UTC timestamps")
        return value

    @model_validator(mode="after")
    def summary_matches_categories(self) -> TransferReport:
        if self.finished_at < self.started_at:
            raise ValueError("finished_at must not precede started_at")
        if self.summary.total_categories != len(self.categories):
            raise ValueError("summary.total_categories does not match categories")
        expected = {
            PlanStatus.TRANSFERABLE: sum(row.plan_status == PlanStatus.TRANSFERABLE for row in self.categories),
            PlanStatus.PARTIAL: sum(row.plan_status == PlanStatus.PARTIAL for row in self.categories),
            PlanStatus.UNAVAILABLE: sum(row.plan_status == PlanStatus.UNAVAILABLE for row in self.categories),
        }
        if (self.summary.transferable, self.summary.partial, self.summary.unavailable) != (
            expected[PlanStatus.TRANSFERABLE],
            expected[PlanStatus.PARTIAL],
            expected[PlanStatus.UNAVAILABLE],
        ):
            raise ValueError("summary plan counts do not match category plan_status values")
        failed_integrity = sum(row.status == ReportStatus.FAILED_INTEGRITY for row in self.categories)
        if self.summary.failed_integrity != failed_integrity:
            raise ValueError("summary.failed_integrity does not match category results")
        return self


_RECEIPT_TO_REPORT_STAGE = {
    ReceiptFailureStage.RECEIVE: FailureStage.TRANSFER,
    ReceiptFailureStage.CRC32_VERIFY: FailureStage.VERIFY,
    ReceiptFailureStage.SHA256_VERIFY: FailureStage.VERIFY,
    ReceiptFailureStage.RESTORE: FailureStage.RESTORE,
    ReceiptFailureStage.POST_VERIFY: FailureStage.POST_VERIFY,
}


def validate_report_receipt_consistency(report: TransferReport, receipt: TransferReceipt) -> None:
    """Check that a final report preserves receiver receipt counts and failures.

    Destination restoration can add report failures/counts that do not belong in
    the receive receipt; every receipt failure, however, must remain visible in
    the report with its mapped stage and original reason.
    """
    if report.transfer_id != receipt.transfer_id:
        raise ValueError("report and receipt transfer_id differ")

    report_rows = {}
    for row in report.categories:
        if row.category_id in report_rows:
            raise ValueError(f"report contains duplicate category {row.category_id.value}")
        report_rows[row.category_id] = row

    for category_id, receipt_row in receipt.categories.items():
        report_row = report_rows.get(category_id)
        if report_row is None:
            raise ValueError(f"report omits receipt category {category_id.value}")
        if report_row.transferred_count != receipt_row.items_received:
            raise ValueError(f"transferred_count does not match receipt for {category_id.value}")
        if report_row.verified_count != receipt_row.items_verified:
            raise ValueError(f"verified_count does not match receipt for {category_id.value}")
        report_failures = {
            (failure.item_id, failure.stage, failure.reason)
            for failure in report_row.failures
        }
        for receipt_failure in receipt_row.failures:
            expected = (
                receipt_failure.item_id,
                _RECEIPT_TO_REPORT_STAGE[receipt_failure.stage],
                receipt_failure.reason,
            )
            if expected not in report_failures:
                raise ValueError(
                    f"report omits receipt failure {receipt_failure.item_id!r} in {category_id.value}"
                )

    for category_id, report_row in report_rows.items():
        if report_row.transferred_count and category_id not in receipt.categories:
            raise ValueError(f"receipt omits transferred category {category_id.value}")


class ReceiptFailure(ContractModel):
    item_id: str = Field(min_length=1, max_length=512)
    stage: ReceiptFailureStage
    reason: ReasonCode


class CategoryReceipt(ContractModel):
    items_received: int = Field(ge=0)
    items_verified: int = Field(ge=0)
    items_failed: int = Field(ge=0)
    failures: list[ReceiptFailure] = Field(default_factory=list)

    @model_validator(mode="after")
    def counters_fit_received(self) -> CategoryReceipt:
        if self.items_verified + self.items_failed != self.items_received:
            raise ValueError("a final receipt must classify every received item as verified or failed")
        failure_ids = [failure.item_id for failure in self.failures]
        if len(failure_ids) != len(set(failure_ids)):
            raise ValueError("receipt failure item IDs must be unique")
        if self.items_failed != len(self.failures):
            raise ValueError("a final receipt must include one failure record for every failed item")
        return self


class TransferReceipt(ContractModel):
    version: Literal["1.0"]
    transfer_id: UUID
    received_at: datetime
    categories: dict[CategoryId, CategoryReceipt]

    @field_validator("received_at")
    @classmethod
    def received_at_must_be_utc(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() != timezone.utc.utcoffset(value):
            raise ValueError("received_at must be an RFC 3339 UTC timestamp")
        return value


class PartialCheckpointItem(ContractModel):
    item_id: str = Field(min_length=1, max_length=512)
    next_expected_chunk: int = Field(ge=1, le=4_294_967_295)
    total_chunks: int = Field(ge=1, le=4_294_967_295)
    next_byte_offset: int = Field(ge=1, le=9_007_199_254_740_991)

    @model_validator(mode="after")
    def checkpoint_is_partial(self) -> PartialCheckpointItem:
        if self.next_expected_chunk >= self.total_chunks:
            raise ValueError("a partial checkpoint must have an unreceived chunk")
        return self


class CheckpointCategory(ContractModel):
    items_total: int = Field(ge=0, le=9_007_199_254_740_991)
    items_verified: int = Field(ge=0, le=9_007_199_254_740_991)
    items_failed: int = Field(ge=0, le=9_007_199_254_740_991)
    items_pending: int = Field(ge=0, le=9_007_199_254_740_991)
    items_partial: list[PartialCheckpointItem]

    @model_validator(mode="after")
    def counters_match_items(self) -> CheckpointCategory:
        classified = self.items_verified + self.items_failed + self.items_pending + len(self.items_partial)
        if classified != self.items_total:
            raise ValueError("items_total must equal verified + failed + pending + partial item count")
        item_ids = [item.item_id for item in self.items_partial]
        if len(item_ids) != len(set(item_ids)):
            raise ValueError("partial checkpoint item IDs must be unique within a category")
        return self


class CheckpointState(ContractModel):
    version: Literal["1.0"]
    transfer_id: UUID
    manifest_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    source_fingerprint: str = Field(pattern=r"^[a-f0-9]{64}$")
    target_fingerprint: str = Field(pattern=r"^[a-f0-9]{64}$")
    protocol_version: str = Field(pattern=r"^[1-9][0-9]{0,4}$")
    source_app_version: str = Field(min_length=1, max_length=64)
    target_app_version: str = Field(min_length=1, max_length=64)
    updated_at: datetime
    categories: dict[CategoryId, CheckpointCategory] = Field(min_length=1)

    @field_validator("updated_at")
    @classmethod
    def updated_at_must_be_utc(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() != timezone.utc.utcoffset(value):
            raise ValueError("updated_at must be an RFC 3339 UTC timestamp")
        return value


class ResumePlanItem(ContractModel):
    category_id: CategoryId
    item_id: str = Field(min_length=1, max_length=512)
    start_chunk: int = Field(ge=0, le=4_294_967_295)
    byte_offset: int = Field(ge=0, le=9_007_199_254_740_991)
    total_chunks: int = Field(ge=1, le=4_294_967_295)

    @model_validator(mode="after")
    def start_is_inside_item(self) -> ResumePlanItem:
        if self.start_chunk >= self.total_chunks:
            raise ValueError("start_chunk must be less than total_chunks")
        if (self.start_chunk == 0) != (self.byte_offset == 0):
            raise ValueError("byte_offset must be zero exactly when start_chunk is zero")
        return self


class ResumePlan(ContractModel):
    version: Literal["1.0"]
    transfer_id: UUID
    manifest_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    items: list[ResumePlanItem] = Field(min_length=1)
    requires_user_confirmation: Literal[True]

    @model_validator(mode="after")
    def resume_items_are_unique(self) -> ResumePlan:
        identities = [(item.category_id, item.item_id) for item in self.items]
        if len(identities) != len(set(identities)):
            raise ValueError("resume plan item identities must be unique")
        return self


class ChunkHeader(ContractModel):
    version: Literal[1]
    transfer_id: UUID
    category_id_hash: bytes = Field(min_length=4, max_length=4)
    item_id_hash: bytes = Field(min_length=4, max_length=4)
    chunk_index: int = Field(ge=0, le=4_294_967_295)
    total_chunks: int = Field(ge=1, le=4_294_967_295)
    chunk_size: int = Field(ge=0, le=8 * 1024 * 1024)
    payload_crc32: int = Field(ge=0, le=4_294_967_295)

    @model_validator(mode="after")
    def index_is_in_range(self) -> ChunkHeader:
        if self.chunk_index >= self.total_chunks:
            raise ValueError("chunk_index must be less than total_chunks")
        return self


def _schema_registry(schema_dir: Path) -> tuple[dict[str, dict[str, Any]], Registry]:
    documents: dict[str, dict[str, Any]] = {}
    for schema_path in sorted(schema_dir.glob("*.schema.json")):
        document = json.loads(schema_path.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(document)
        documents[schema_path.name] = document
    registry = Registry().with_resources(
        (document["$id"], Resource.from_contents(document))
        for document in documents.values()
        if isinstance(document.get("$id"), str)
    )
    return documents, registry


def validate_document(document: dict[str, Any], schema_name: str) -> ContractModel | None:
    """Validate a JSON payload against a bundled shared schema.

    Raises ValueError with stable, path-qualified validation messages.
    """
    if Path(schema_name).name != schema_name or not schema_name.endswith(".schema.json"):
        raise ValueError("schema_name must be a local *.schema.json filename")
    schema_dir = Path(__file__).resolve().parents[1] / "shared"
    documents, registry = _schema_registry(schema_dir)
    try:
        schema = documents[schema_name]
    except KeyError as exc:
        raise ValueError(f"unknown schema: {schema_name}") from exc
    validator = Draft202012Validator(
        schema,
        registry=registry,
        format_checker=FormatChecker(),
    )
    errors = sorted(validator.iter_errors(document), key=lambda error: list(error.absolute_path))
    if errors:
        rendered = []
        for error in errors:
            path = ".".join(str(part) for part in error.absolute_path) or "$"
            rendered.append(f"{path}: {error.message}")
        raise ValueError("; ".join(rendered))
    semantic_models: dict[str, type[ContractModel]] = {
        "manifest.schema.json": TransferManifest,
        "capability.schema.json": CapabilityManifest,
        "report.schema.json": TransferReport,
        "receipt.schema.json": TransferReceipt,
        "checkpoint-state.schema.json": CheckpointState,
        "resume-plan.schema.json": ResumePlan,
    }
    semantic_model = semantic_models.get(schema_name)
    if semantic_model is None:
        return None
    try:
        return semantic_model.model_validate(document)
    except ValidationError as exc:
        errors = []
        for error in exc.errors():
            path = ".".join(str(part) for part in error["loc"]) or "$"
            errors.append(f"{path}: {error['msg']}")
        raise ValueError("; ".join(errors)) from exc


def _json_safe(value: Any) -> Any:
    if isinstance(value, BaseModel):
        return _json_safe(value.model_dump(mode="python", exclude_none=False))
    if isinstance(value, datetime):
        return format_utc_timestamp(value)
    if isinstance(value, UUID):
        return str(value)
    if isinstance(value, Enum):
        return _json_safe(value.value)
    if isinstance(value, dict):
        if any(not isinstance(key, str) for key in value):
            raise TypeError("canonical JSON object keys must be strings")
        return {key: _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return value


def canonical_json_bytes(value: BaseModel | dict[str, Any] | list[Any]) -> bytes:
    """Serialize contract data as compact, key-sorted UTF-8 JSON without a BOM."""
    payload = _json_safe(value)
    return json.dumps(
        payload,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
