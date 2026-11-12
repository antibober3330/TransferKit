"""Safe manifest construction, canonical serialization and schema validation."""

from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from uuid import UUID, uuid4

from .contracts import (
    CategoryId,
    DeviceInfo,
    ManifestCategory,
    ManifestItem,
    TransferManifest,
    canonical_json_bytes,
    validate_document,
)
from .integrity import sha256_bytes


MAX_MANIFEST_BYTES = 64 * 1024 * 1024
MAX_ITEM_METADATA_BYTES = 64 * 1024
_RESERVED_FILE_METADATA = frozenset({"path", "mtime"})


class ManifestBuildError(ValueError):
    """The source cannot be represented safely by a manifest item."""


def _duplicate_safe_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def _check_metadata_size(metadata: dict[str, Any]) -> None:
    try:
        encoded = canonical_json_bytes(metadata)
    except (TypeError, ValueError) as exc:
        raise ManifestBuildError("item metadata must contain canonical JSON values") from exc
    if len(encoded) > MAX_ITEM_METADATA_BYTES:
        raise ManifestBuildError("item metadata exceeds the 64 KiB per-item limit")


def parse_manifest_json(payload: bytes | str) -> TransferManifest:
    if isinstance(payload, bytes):
        if payload.startswith(b"\xef\xbb\xbf"):
            raise ValueError("manifest JSON must not contain a UTF-8 BOM")
        raw_bytes = payload
        if len(raw_bytes) > MAX_MANIFEST_BYTES:
            raise ValueError("manifest exceeds the 64 MiB size limit")
        try:
            text = raw_bytes.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ValueError("manifest must be UTF-8 encoded") from exc
    else:
        text = payload
        if len(text.encode("utf-8")) > MAX_MANIFEST_BYTES:
            raise ValueError("manifest exceeds the 64 MiB size limit")
    try:
        document = json.loads(text, object_pairs_hook=_duplicate_safe_object)
    except (json.JSONDecodeError, ValueError) as exc:
        raise ValueError("manifest JSON is malformed or contains duplicate keys") from exc
    if not isinstance(document, dict):
        raise ValueError("manifest root must be a JSON object")
    validated = validate_document(document, "manifest.schema.json")
    if not isinstance(validated, TransferManifest):
        raise RuntimeError("manifest schema is missing its semantic model")
    return validated


def load_manifest(path: str | Path) -> TransferManifest:
    manifest_path = Path(path)
    try:
        size = manifest_path.stat().st_size
    except OSError as exc:
        raise ValueError("manifest file cannot be accessed") from exc
    if size > MAX_MANIFEST_BYTES:
        raise ValueError("manifest exceeds the 64 MiB size limit")
    try:
        payload = manifest_path.read_bytes()
    except OSError as exc:
        raise ValueError("manifest file cannot be read") from exc
    return parse_manifest_json(payload)


def manifest_json_bytes(manifest: TransferManifest) -> bytes:
    payload = canonical_json_bytes(manifest)
    validate_document(json.loads(payload), "manifest.schema.json")
    return payload


class ManifestBuilder:
    """Build a transfer manifest from selected files and serialized API records."""

    def __init__(
        self,
        source_device: DeviceInfo,
        *,
        transfer_id: UUID | None = None,
        created_at: datetime | None = None,
    ):
        self.source_device = source_device
        self.transfer_id = transfer_id or uuid4()
        self.created_at = created_at or datetime.now(timezone.utc)
        if self.created_at.tzinfo is None or self.created_at.utcoffset() != timezone.utc.utcoffset(self.created_at):
            raise ValueError("created_at must be a timezone-aware UTC timestamp")
        self._categories: dict[CategoryId, ManifestCategory] = {}

    def add_category(self, category_id: CategoryId | str, provider: str, method: str) -> None:
        category = CategoryId(category_id)
        current = self._categories.get(category)
        if current is None:
            self._categories[category] = ManifestCategory(provider=provider, method=method, items=[])
            return
        if current.provider != provider or current.method != method:
            raise ManifestBuildError("a category cannot mix provider or access method values")

    def add_file(
        self,
        category_id: CategoryId | str,
        path: str | Path,
        *,
        root: str | Path,
        provider: str,
        method: str,
        metadata: dict[str, Any] | None = None,
    ) -> ManifestItem:
        category = CategoryId(category_id)
        base = Path(root).resolve(strict=True)
        source = Path(path).resolve(strict=True)
        if not base.is_dir():
            raise ManifestBuildError("manifest root must be a directory")
        try:
            relative_path = source.relative_to(base)
        except ValueError as exc:
            raise ManifestBuildError("file is outside the selected manifest root") from exc
        if not source.is_file():
            raise ManifestBuildError("manifest items must be regular files")
        extra_metadata = dict(metadata or {})
        if _RESERVED_FILE_METADATA.intersection(extra_metadata):
            raise ManifestBuildError("metadata path and mtime are managed by ManifestBuilder")

        before = source.stat()
        digest = hashlib.sha256()
        size = 0
        try:
            with source.open("rb") as stream:
                while True:
                    block = stream.read(1024 * 1024)
                    if not block:
                        break
                    digest.update(block)
                    size += len(block)
        except OSError as exc:
            raise ManifestBuildError("source file could not be read") from exc
        after = source.stat()
        if size != before.st_size or after.st_size != before.st_size or after.st_mtime_ns != before.st_mtime_ns:
            raise ManifestBuildError("source file changed while its manifest digest was being computed")

        relative_text = relative_path.as_posix()
        metadata_value = {
            **extra_metadata,
            "mtime": int(before.st_mtime),
            "path": relative_text,
        }
        _check_metadata_size(metadata_value)
        item_id = "file_" + hashlib.sha256(relative_text.encode("utf-8")).hexdigest()[:32]
        item = ManifestItem(
            id=item_id,
            size=size,
            sha256=digest.hexdigest(),
            metadata=metadata_value,
        )
        self.add_category(category, provider, method)
        self._categories[category].items.append(item)
        return item

    def add_record(
        self,
        category_id: CategoryId | str,
        record_id: str,
        serialized_record: bytes,
        *,
        provider: str,
        method: str,
        metadata: dict[str, Any] | None = None,
    ) -> ManifestItem:
        category = CategoryId(category_id)
        if not record_id:
            raise ManifestBuildError("record_id must not be empty")
        metadata_value = dict(metadata or {})
        _check_metadata_size(metadata_value)
        item = ManifestItem(
            id=record_id,
            size=len(serialized_record),
            sha256=sha256_bytes(serialized_record),
            metadata=metadata_value,
        )
        self.add_category(category, provider, method)
        self._categories[category].items.append(item)
        return item

    def add_files(
        self,
        category_id: CategoryId | str,
        paths: Iterable[str | Path],
        *,
        root: str | Path,
        provider: str,
        method: str,
    ) -> list[ManifestItem]:
        return [
            self.add_file(category_id, path, root=root, provider=provider, method=method)
            for path in paths
        ]

    def build(self) -> TransferManifest:
        if not self._categories:
            raise ManifestBuildError("select at least one category before building a manifest")
        manifest = TransferManifest(
            version="1.0",
            transfer_id=self.transfer_id,
            created_at=self.created_at,
            source_device=self.source_device,
            categories=self._categories,
        )
        validate_document(manifest.model_dump(mode="json"), "manifest.schema.json")
        return manifest
