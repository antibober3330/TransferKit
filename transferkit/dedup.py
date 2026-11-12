"""Conservative exact-content deduplication preflight for file/media transfers.

This module only plans skips. It never deletes, overwrites, or mutates target
data. An item is skippable only when its category scan is explicitly complete
and a target item has the same category, byte length, and SHA-256 digest.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from .contracts import CategoryId, ManifestItem, ReasonCode, TransferManifest


_SHA256 = re.compile(r"^[a-f0-9]{64}$")
_SUPPORTED_CATEGORIES = frozenset(
    {
        CategoryId.PHOTOS,
        CategoryId.VIDEOS,
        CategoryId.AUDIO,
        CategoryId.DOCUMENTS,
        CategoryId.DOWNLOADS,
    }
)


@dataclass(frozen=True, slots=True)
class TargetInventoryItem:
    """One target-side content digest from the current inventory scan.

    ``item_id`` is an opaque scan-local reference (for example, a MediaStore
    row key). It must not be logged as a path or exposed as a hardware ID.
    """

    category_id: CategoryId
    item_id: str
    size: int
    sha256: str

    def __post_init__(self) -> None:
        try:
            category_id = CategoryId(self.category_id)
        except ValueError as exc:
            raise ValueError("target inventory category_id is unsupported") from exc
        object.__setattr__(self, "category_id", category_id)
        if category_id not in _SUPPORTED_CATEGORIES:
            raise ValueError("target inventory supports only file/media categories")
        if not self.item_id or len(self.item_id) > 512:
            raise ValueError("target inventory item_id must contain 1..512 characters")
        if not 0 <= self.size <= 9_007_199_254_740_991:
            raise ValueError("target inventory size is outside the safe integer range")
        if not _SHA256.fullmatch(self.sha256):
            raise ValueError("target inventory sha256 must be lowercase SHA-256")


@dataclass(frozen=True, slots=True)
class TargetInventory:
    """A snapshot plus the categories whose target scan completed fully."""

    items: tuple[TargetInventoryItem, ...]
    complete_categories: frozenset[CategoryId]

    def __post_init__(self) -> None:
        complete = frozenset(CategoryId(category) for category in self.complete_categories)
        object.__setattr__(self, "complete_categories", complete)
        if not complete <= _SUPPORTED_CATEGORIES:
            raise ValueError("complete_categories contains an unsupported category")
        keys = [(item.category_id, item.item_id) for item in self.items]
        if len(keys) != len(set(keys)):
            raise ValueError("target inventory item references must be unique per category")


@dataclass(frozen=True, slots=True)
class ExactDuplicate:
    category_id: CategoryId
    source_item_id: str
    target_item_id: str
    size: int
    sha256: str
    reason: ReasonCode = ReasonCode.ALREADY_PRESENT


@dataclass(frozen=True, slots=True)
class ExactDedupPlan:
    """Decision for every source item, preserving manifest order."""

    transfer_items: tuple[tuple[CategoryId, ManifestItem], ...]
    skipped_duplicates: tuple[ExactDuplicate, ...]
    bytes_to_transfer: int
    bytes_skipped: int

    @property
    def item_count_to_transfer(self) -> int:
        return len(self.transfer_items)

    @property
    def item_count_skipped(self) -> int:
        return len(self.skipped_duplicates)


def build_exact_dedup_plan(manifest: TransferManifest, target: TargetInventory) -> ExactDedupPlan:
    """Skip only exact duplicates from completely inventoried categories.

    Filename, path, timestamp, MIME type, and metadata are deliberately not
    treated as proof of identity. Hashes are indexed by (category, size, SHA),
    then one stable target reference is recorded for the skip explanation.
    """
    target_by_digest: dict[tuple[CategoryId, int, str], list[str]] = {}
    for existing in target.items:
        if existing.category_id in target.complete_categories:
            key = (existing.category_id, existing.size, existing.sha256)
            target_by_digest.setdefault(key, []).append(existing.item_id)
    for refs in target_by_digest.values():
        refs.sort()

    transfer_items: list[tuple[CategoryId, ManifestItem]] = []
    skipped: list[ExactDuplicate] = []
    bytes_to_transfer = 0
    bytes_skipped = 0

    for category_id, category in manifest.categories.items():
        if category_id not in _SUPPORTED_CATEGORIES:
            for item in category.items:
                transfer_items.append((category_id, item))
                bytes_to_transfer += item.size
            continue

        can_compare = category_id in target.complete_categories
        for item in category.items:
            key = (category_id, item.size, item.sha256)
            refs = target_by_digest.get(key, ()) if can_compare else ()
            if refs:
                skipped.append(
                    ExactDuplicate(
                        category_id=category_id,
                        source_item_id=item.id,
                        target_item_id=refs[0],
                        size=item.size,
                        sha256=item.sha256,
                    )
                )
                bytes_skipped += item.size
            else:
                transfer_items.append((category_id, item))
                bytes_to_transfer += item.size

    return ExactDedupPlan(
        transfer_items=tuple(transfer_items),
        skipped_duplicates=tuple(skipped),
        bytes_to_transfer=bytes_to_transfer,
        bytes_skipped=bytes_skipped,
    )
