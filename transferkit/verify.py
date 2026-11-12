"""Post-transfer verification for target files described by a manifest."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

from .contracts import ItemStatus, ManifestItem, ReasonCode


@dataclass(frozen=True, slots=True)
class FileVerification:
    item_id: str
    status: ItemStatus
    reason: ReasonCode
    expected_size: int
    actual_size: int | None
    expected_sha256: str
    actual_sha256: str | None


def verify_target_file(item: ManifestItem, target_path: str | Path) -> FileVerification:
    """Stream a target file once and fail closed on size or digest mismatch."""
    path = Path(target_path)
    try:
        before = path.stat()
        if not path.is_file():
            raise OSError("target is not a regular file")
        digest = hashlib.sha256()
        actual_size = 0
        with path.open("rb") as target:
            while True:
                block = target.read(1024 * 1024)
                if not block:
                    break
                digest.update(block)
                actual_size += len(block)
        after = path.stat()
    except FileNotFoundError:
        return FileVerification(
            item_id=item.id,
            status=ItemStatus.FAILED,
            reason=ReasonCode.TARGET_WRITE_FAILED,
            expected_size=item.size,
            actual_size=None,
            expected_sha256=item.sha256,
            actual_sha256=None,
        )
    except OSError:
        return FileVerification(
            item_id=item.id,
            status=ItemStatus.FAILED,
            reason=ReasonCode.IO_ERROR,
            expected_size=item.size,
            actual_size=None,
            expected_sha256=item.sha256,
            actual_sha256=None,
        )

    actual_sha256 = digest.hexdigest()
    changed_while_reading = (
        before.st_size != after.st_size
        or before.st_mtime_ns != after.st_mtime_ns
        or actual_size != after.st_size
    )
    if changed_while_reading or actual_size != item.size or actual_sha256 != item.sha256:
        return FileVerification(
            item_id=item.id,
            status=ItemStatus.FAILED_INTEGRITY,
            reason=ReasonCode.SHA256_MISMATCH,
            expected_size=item.size,
            actual_size=actual_size,
            expected_sha256=item.sha256,
            actual_sha256=actual_sha256,
        )
    return FileVerification(
        item_id=item.id,
        status=ItemStatus.VERIFIED,
        reason=ReasonCode.OK,
        expected_size=item.size,
        actual_size=actual_size,
        expected_sha256=item.sha256,
        actual_sha256=actual_sha256,
    )
