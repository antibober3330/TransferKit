"""Conservative, side-effect-free storage planning for restore candidates.

Provider adapters supply per-item staging and restore estimates. Unknown values
are never treated as zero: a plan with incomplete estimates cannot authorize a
transfer and is returned for explicit handling by a later UI/protocol layer.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from heapq import heappop, heappush
from pathlib import Path
from shutil import disk_usage
from typing import Callable, Iterable, NamedTuple

from .transport.backup_relay import (
    ENCRYPTED_RELAY_ENVELOPE_OVERHEAD_BYTES,
    MAX_ARCHIVE_OVERHEAD_BYTES,
)


MIB = 1024 * 1024
DEFAULT_MINIMUM_SAFETY_MARGIN_BYTES = 100 * MIB
DEFAULT_SAFETY_MARGIN_DIVISOR = 20
RELAY_FALLBACK_MINIMUM_MARGIN_BYTES = 200 * MIB
RELAY_FALLBACK_MARGIN_DIVISOR = 10


class SpaceStrategy(StrEnum):
    BATCH = "batch"
    STREAMING = "streaming"
    DROP_LARGEST = "drop-largest"
    INSUFFICIENT = "insufficient"


@dataclass(frozen=True, slots=True)
class SpaceItemEstimate:
    """Storage required for one item; ``None`` means the provider is unsure."""

    item_id: str
    staging_bytes: int | None
    restore_bytes: int | None

    def __post_init__(self) -> None:
        if not isinstance(self.item_id, str) or not self.item_id.strip():
            raise ValueError("item_id must be a non-empty string")
        for field_name, value in (
            ("staging_bytes", self.staging_bytes),
            ("restore_bytes", self.restore_bytes),
        ):
            if value is not None and (
                isinstance(value, bool) or not isinstance(value, int) or value < 0
            ):
                raise ValueError(f"{field_name} must be a non-negative integer or None")


@dataclass(frozen=True, slots=True)
class SpacePreflightResult:
    strategy: SpaceStrategy | None
    allocatable_bytes: int
    safety_margin_bytes: int
    safe_available_bytes: int
    total_staging_bytes: int | None
    total_restore_bytes: int | None
    batch_peak_bytes: int | None
    streaming_peak_bytes: int | None
    kept_item_ids: tuple[str, ...]
    skipped_item_ids: tuple[str, ...]
    unknown_item_ids: tuple[str, ...]
    requires_user_decision: bool


class RelayDiskUsage(NamedTuple):
    total: int
    used: int
    free: int


@dataclass(frozen=True, slots=True)
class RelayExportSpaceEstimate:
    verified_payload_bytes: int | None
    estimated_archive_bytes: int | None
    available_bytes: int
    safety_margin_bytes: int
    safe_available_bytes: int
    fits: bool | None
    unknown_item_count: int


def default_safety_margin(allocatable_bytes: int) -> int:
    """Return the project default: max(100 MiB, 5% of allocatable bytes)."""

    _validate_byte_count("allocatable_bytes", allocatable_bytes)
    return max(
        DEFAULT_MINIMUM_SAFETY_MARGIN_BYTES,
        allocatable_bytes // DEFAULT_SAFETY_MARGIN_DIVISOR,
    )


def estimate_backup_relay_export_space(
    destination_directory: str | Path,
    verified_item_sizes: Iterable[int | None],
    *,
    encrypted: bool = False,
    disk_usage_probe: Callable[[str | Path], RelayDiskUsage] = disk_usage,
) -> RelayExportSpaceEstimate:
    """Estimate Windows Relay output space without creating files.

    ZIP payload entries are stored without compression, so their declared byte
    sizes are a direct payload estimate. The existing relay format caps
    metadata/container overhead at 128 MiB; encrypted containers add their
    fixed header and GCM tag. Windows free-space data is not an app allocation
    guarantee, so this path reserves at least 200 MiB or 10% of free space.
    """

    directory = Path(destination_directory)
    if not directory.is_dir():
        raise ValueError("destination_directory must be an existing directory")
    usage = disk_usage_probe(directory)
    available = usage.free
    _validate_byte_count("available_bytes", available)
    margin = max(RELAY_FALLBACK_MINIMUM_MARGIN_BYTES, available // RELAY_FALLBACK_MARGIN_DIVISOR)
    safe_available = max(0, available - margin)

    sizes = tuple(verified_item_sizes)
    unknown_count = sum(size is None for size in sizes)
    for size in sizes:
        if size is not None:
            _validate_byte_count("verified_item_size", size)
    if not sizes:
        raise ValueError("at least one verified item size is required")
    if unknown_count:
        return RelayExportSpaceEstimate(
            verified_payload_bytes=None,
            estimated_archive_bytes=None,
            available_bytes=available,
            safety_margin_bytes=margin,
            safe_available_bytes=safe_available,
            fits=None,
            unknown_item_count=unknown_count,
        )

    payload_bytes = sum(size for size in sizes if size is not None)
    archive_bytes = payload_bytes + MAX_ARCHIVE_OVERHEAD_BYTES
    if encrypted:
        archive_bytes += ENCRYPTED_RELAY_ENVELOPE_OVERHEAD_BYTES
    return RelayExportSpaceEstimate(
        verified_payload_bytes=payload_bytes,
        estimated_archive_bytes=archive_bytes,
        available_bytes=available,
        safety_margin_bytes=margin,
        safe_available_bytes=safe_available,
        fits=archive_bytes <= safe_available,
        unknown_item_count=0,
    )


def calculate_space_preflight(
    items: Iterable[SpaceItemEstimate],
    *,
    allocatable_bytes: int,
    safety_margin_bytes: int | None = None,
) -> SpacePreflightResult:
    """Choose a feasible strategy without touching storage or item data.

    Batch peak is the sum of all staging and restore estimates. Streaming peak
    is all restored output plus the largest single staging item, as specified
    for the shared-volume case. The caller may pass a provider-derived safety
    margin; ``None`` selects the documented 100 MiB / 5% default.
    """

    _validate_byte_count("allocatable_bytes", allocatable_bytes)
    if safety_margin_bytes is None:
        margin = default_safety_margin(allocatable_bytes)
    else:
        _validate_byte_count("safety_margin_bytes", safety_margin_bytes)
        margin = safety_margin_bytes
    safe_available = max(0, allocatable_bytes - margin)

    item_list = tuple(items)
    ids = [item.item_id for item in item_list]
    if len(ids) != len(set(ids)):
        raise ValueError("item_id values must be unique")

    unknown_ids = tuple(
        item.item_id
        for item in item_list
        if item.staging_bytes is None or item.restore_bytes is None
    )
    if unknown_ids:
        return SpacePreflightResult(
            strategy=None,
            allocatable_bytes=allocatable_bytes,
            safety_margin_bytes=margin,
            safe_available_bytes=safe_available,
            total_staging_bytes=None,
            total_restore_bytes=None,
            batch_peak_bytes=None,
            streaming_peak_bytes=None,
            kept_item_ids=(),
            skipped_item_ids=(),
            unknown_item_ids=unknown_ids,
            requires_user_decision=True,
        )

    total_staging = sum(item.staging_bytes for item in item_list if item.staging_bytes is not None)
    total_restore = sum(item.restore_bytes for item in item_list if item.restore_bytes is not None)
    batch_peak = total_staging + total_restore
    largest_staging = max((item.staging_bytes or 0 for item in item_list), default=0)
    streaming_peak = total_restore + largest_staging
    all_ids = tuple(item.item_id for item in item_list)

    if batch_peak <= safe_available:
        return _result(
            SpaceStrategy.BATCH, allocatable_bytes, margin, safe_available,
            total_staging, total_restore, batch_peak, streaming_peak,
            all_ids, (), (), False,
        )

    if streaming_peak <= safe_available:
        return _result(
            SpaceStrategy.STREAMING, allocatable_bytes, margin, safe_available,
            total_staging, total_restore, batch_peak, streaming_peak,
            all_ids, (), (), True,
        )

    kept = select_drop_largest_subset(item_list, safe_available_bytes=safe_available)
    if kept:
        kept_ids = {item.item_id for item in kept}
        ordered_kept = tuple(item_id for item_id in all_ids if item_id in kept_ids)
        skipped = tuple(item_id for item_id in all_ids if item_id not in kept_ids)
        return _result(
            SpaceStrategy.DROP_LARGEST, allocatable_bytes, margin, safe_available,
            total_staging, total_restore, batch_peak, streaming_peak,
            ordered_kept, skipped, (), True,
        )

    return _result(
        SpaceStrategy.INSUFFICIENT, allocatable_bytes, margin, safe_available,
        total_staging, total_restore, batch_peak, streaming_peak,
        (), (), (), True,
    )


def select_drop_largest_subset(
    items: Iterable[SpaceItemEstimate], *, safe_available_bytes: int
) -> tuple[SpaceItemEstimate, ...]:
    """Select the largest item count satisfying restore-sum + largest staging.

    It sweeps largest-staging thresholds in ascending order while maintaining
    the cheapest restore estimates in a max-heap. This finds a maximum-count
    subset in O(n log n) time instead of rescanning every possible subset. Ties
    are deterministic by item ID; output preserves manifest/input order.
    Unknown estimates fail closed.
    """

    _validate_byte_count("safe_available_bytes", safe_available_bytes)
    item_list = tuple(items)
    ids = [item.item_id for item in item_list]
    if len(ids) != len(set(ids)):
        raise ValueError("item_id values must be unique")
    if any(item.staging_bytes is None or item.restore_bytes is None for item in item_list):
        raise ValueError("drop-largest requires known staging and restore estimates")

    ordered = sorted(
        item_list,
        key=lambda item: (item.staging_bytes, item.item_id),
    )
    max_count = _drop_largest_max_count(ordered, safe_available_bytes)
    if max_count == 0:
        return ()

    selected = _drop_largest_selection_at_count(
        ordered, safe_available_bytes, target_count=max_count
    )
    selected_ids = {item.item_id for item in selected}
    return tuple(item for item in item_list if item.item_id in selected_ids)


def _drop_largest_max_count(
    ordered: list[SpaceItemEstimate], safe_available_bytes: int
) -> int:
    selected: list[tuple[int, str, SpaceItemEstimate]] = []
    restore_sum = 0
    max_count = 0
    cursor = 0
    while cursor < len(ordered):
        threshold = ordered[cursor].staging_bytes
        assert threshold is not None
        while cursor < len(ordered) and ordered[cursor].staging_bytes == threshold:
            item = ordered[cursor]
            assert item.restore_bytes is not None
            heappush(selected, (-item.restore_bytes, item.item_id, item))
            restore_sum += item.restore_bytes
            cursor += 1
        while selected and restore_sum + threshold > safe_available_bytes:
            _, _, removed = heappop(selected)
            assert removed.restore_bytes is not None
            restore_sum -= removed.restore_bytes
        max_count = max(max_count, len(selected))
    return max_count


def _drop_largest_selection_at_count(
    ordered: list[SpaceItemEstimate],
    safe_available_bytes: int,
    *,
    target_count: int,
) -> tuple[SpaceItemEstimate, ...]:
    selected: list[tuple[int, str, SpaceItemEstimate]] = []
    restore_sum = 0
    cursor = 0
    while cursor < len(ordered):
        threshold = ordered[cursor].staging_bytes
        assert threshold is not None
        while cursor < len(ordered) and ordered[cursor].staging_bytes == threshold:
            item = ordered[cursor]
            assert item.restore_bytes is not None
            heappush(selected, (-item.restore_bytes, item.item_id, item))
            restore_sum += item.restore_bytes
            cursor += 1
        while selected and restore_sum + threshold > safe_available_bytes:
            _, _, removed = heappop(selected)
            assert removed.restore_bytes is not None
            restore_sum -= removed.restore_bytes
        if len(selected) == target_count:
            return tuple(entry[2] for entry in selected)
    return ()


def _result(
    strategy: SpaceStrategy,
    allocatable_bytes: int,
    margin: int,
    safe_available: int,
    total_staging: int,
    total_restore: int,
    batch_peak: int,
    streaming_peak: int,
    kept: tuple[str, ...],
    skipped: tuple[str, ...],
    unknown: tuple[str, ...],
    requires_user_decision: bool,
) -> SpacePreflightResult:
    return SpacePreflightResult(
        strategy=strategy,
        allocatable_bytes=allocatable_bytes,
        safety_margin_bytes=margin,
        safe_available_bytes=safe_available,
        total_staging_bytes=total_staging,
        total_restore_bytes=total_restore,
        batch_peak_bytes=batch_peak,
        streaming_peak_bytes=streaming_peak,
        kept_item_ids=kept,
        skipped_item_ids=skipped,
        unknown_item_ids=unknown,
        requires_user_decision=requires_user_decision,
    )


def _validate_byte_count(name: str, value: int) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{name} must be a non-negative integer")
