import tempfile
import unittest
from pathlib import Path

from transferkit.contracts import ItemStatus, ManifestItem, ReasonCode
from transferkit.integrity import sha256_bytes
from transferkit.verify import verify_target_file


def item_for(payload: bytes) -> ManifestItem:
    return ManifestItem(
        id="photo-1",
        size=len(payload),
        sha256=sha256_bytes(payload),
        metadata={},
    )


class VerifyTests(unittest.TestCase):
    def test_matching_target_file_is_verified(self) -> None:
        payload = b"verified target bytes"
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            path = Path(folder) / "target.bin"
            path.write_bytes(payload)
            result = verify_target_file(item_for(payload), path)
        self.assertEqual(result.status, ItemStatus.VERIFIED)
        self.assertEqual(result.reason, ReasonCode.OK)
        self.assertEqual(result.actual_sha256, sha256_bytes(payload))

    def test_same_size_hash_mismatch_is_failed_integrity(self) -> None:
        expected = b"original"
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            path = Path(folder) / "target.bin"
            path.write_bytes(b"modified")
            result = verify_target_file(item_for(expected), path)
        self.assertEqual(result.status, ItemStatus.FAILED_INTEGRITY)
        self.assertEqual(result.reason, ReasonCode.SHA256_MISMATCH)

    def test_uppercase_manifest_digest_fails_integrity_closed(self) -> None:
        item = ManifestItem.model_construct(
            id="photo-1",
            size=5,
            sha256="A" * 64,
            metadata={},
        )
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            path = Path(folder) / "target.bin"
            path.write_bytes(b"hello")
            result = verify_target_file(item, path)
        self.assertEqual(result.status, ItemStatus.FAILED_INTEGRITY)
        self.assertEqual(result.reason, ReasonCode.SHA256_MISMATCH)

    def test_size_mismatch_is_failed_integrity(self) -> None:
        expected = b"original"
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            path = Path(folder) / "target.bin"
            path.write_bytes(b"short")
            result = verify_target_file(item_for(expected), path)
        self.assertEqual(result.status, ItemStatus.FAILED_INTEGRITY)
        self.assertEqual(result.reason, ReasonCode.SHA256_MISMATCH)
        self.assertEqual(result.actual_size, 5)

    def test_missing_target_file_is_failure_not_success_or_integrity_ok(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            path = Path(folder) / "missing.bin"
            result = verify_target_file(item_for(b"expected"), path)
        self.assertEqual(result.status, ItemStatus.FAILED)
        self.assertEqual(result.reason, ReasonCode.TARGET_WRITE_FAILED)
        self.assertIsNone(result.actual_sha256)


if __name__ == "__main__":
    unittest.main()
