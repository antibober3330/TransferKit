from __future__ import annotations

import hashlib
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

from transferkit.contracts import (
    CategoryId,
    CategoryReceipt,
    DeviceInfo,
    ManifestCategory,
    ManifestItem,
    TransferManifest,
    TransferReceipt,
)
from transferkit.transport.backup_relay import (
    BackupRelayError,
    ENCRYPTED_ARCHIVE_MAGIC,
    extract_backup_archive,
    write_backup_archive,
)


TRANSFER_ID = UUID("00000000-0000-4000-8000-000000000001")
PAYLOAD = b"private test payload"
PASSWORD = "correct horse battery staple"


def fixtures(root: Path) -> tuple[TransferManifest, TransferReceipt, dict[tuple[CategoryId, str], Path]]:
    source = root / "source.bin"
    source.write_bytes(PAYLOAD)
    digest = hashlib.sha256(PAYLOAD).hexdigest()
    device = DeviceInfo(manufacturer="Test", model="Fixture", sdk=34, fingerprint="fixture")
    manifest = TransferManifest(
        version="1.0",
        transfer_id=TRANSFER_ID,
        created_at=datetime.now(timezone.utc),
        source_device=device,
        categories={
            CategoryId.PHOTOS: ManifestCategory(
                provider="test.provider",
                method="fixture",
                items=[ManifestItem(id="photo-1", size=len(PAYLOAD), sha256=digest, metadata={})],
            )
        },
    )
    receipt = TransferReceipt(
        version="1.0",
        transfer_id=TRANSFER_ID,
        received_at=datetime.now(timezone.utc),
        categories={CategoryId.PHOTOS: CategoryReceipt(items_received=1, items_verified=1, items_failed=0)},
    )
    return manifest, receipt, {(CategoryId.PHOTOS, "photo-1"): source}


class BackupRelayEncryptionTests(unittest.TestCase):
    def test_password_archive_round_trips_and_contains_no_plaintext(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            root = Path(folder)
            manifest, receipt, items = fixtures(root)
            archive = write_backup_archive(manifest, receipt, items, root / "encrypted.tka", password=PASSWORD)

            encoded = archive.read_bytes()
            self.assertTrue(encoded.startswith(ENCRYPTED_ARCHIVE_MAGIC))
            self.assertNotIn(PAYLOAD, encoded)
            self.assertNotIn(b"manifest.json", encoded)

            temp_dir = Path(tempfile.gettempdir())
            temporary_before = {path.name for path in temp_dir.glob(".transferkit-decrypt-*.zip")}
            restored = extract_backup_archive(
                archive,
                root / "restored",
                max_total_bytes=1024,
                password=PASSWORD,
            )
            self.assertEqual(PAYLOAD, restored.item_files[(CategoryId.PHOTOS, "photo-1")].read_bytes())
            self.assertEqual(temporary_before, {path.name for path in temp_dir.glob(".transferkit-decrypt-*.zip")})

    def test_wrong_password_and_ciphertext_tampering_leave_no_extracted_transfer(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            root = Path(folder)
            manifest, receipt, items = fixtures(root)
            archive = write_backup_archive(manifest, receipt, items, root / "encrypted.tka", password=PASSWORD)
            destination = root / "wrong-password"

            temp_dir = Path(tempfile.gettempdir())
            temporary_before = {path.name for path in temp_dir.glob(".transferkit-decrypt-*.zip")}
            with self.assertRaises(BackupRelayError):
                extract_backup_archive(archive, destination, max_total_bytes=1024, password="not-the-right-password")
            self.assertEqual([], list(destination.glob("TransferKit-*")))
            self.assertEqual(temporary_before, {path.name for path in temp_dir.glob(".transferkit-decrypt-*.zip")})

            tampered = root / "tampered.tka"
            damaged = bytearray(archive.read_bytes())
            damaged[-1] ^= 0x01
            tampered.write_bytes(damaged)
            with self.assertRaises(BackupRelayError):
                extract_backup_archive(tampered, root / "tampered-output", max_total_bytes=1024, password=PASSWORD)
            self.assertEqual([], list((root / "tampered-output").glob("TransferKit-*")))
            self.assertEqual(temporary_before, {path.name for path in temp_dir.glob(".transferkit-decrypt-*.zip")})

    def test_plain_zip_remains_supported_and_password_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            root = Path(folder)
            manifest, receipt, items = fixtures(root)
            archive = write_backup_archive(manifest, receipt, items, root / "plain.zip")
            self.assertFalse(archive.read_bytes().startswith(ENCRYPTED_ARCHIVE_MAGIC))
            restored = extract_backup_archive(archive, root / "plain-output", max_total_bytes=1024)
            self.assertEqual(PAYLOAD, restored.item_files[(CategoryId.PHOTOS, "photo-1")].read_bytes())

            with self.assertRaises(BackupRelayError):
                extract_backup_archive(archive, root / "password-on-plain", max_total_bytes=1024, password=PASSWORD)

    def test_empty_and_overlong_passwords_are_rejected_without_output_file(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            root = Path(folder)
            manifest, receipt, items = fixtures(root)
            for password in ("", "short", "x" * 1025):
                destination = root / f"invalid-{len(password)}.tka"
                with self.assertRaises(BackupRelayError):
                    write_backup_archive(manifest, receipt, items, destination, password=password)
                self.assertFalse(destination.exists())
                self.assertEqual([], list(root.glob(f".{destination.name}.*.tmp")))


if __name__ == "__main__":
    unittest.main()
