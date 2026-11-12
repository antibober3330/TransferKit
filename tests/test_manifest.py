import json
import tempfile
import unittest
from pathlib import Path
from uuid import UUID

from transferkit.contracts import CategoryId, DeviceInfo
from transferkit.integrity import sha256_bytes
from transferkit.manifest import (
    ManifestBuildError,
    ManifestBuilder,
    load_manifest,
    manifest_json_bytes,
    parse_manifest_json,
)


DEVICE = DeviceInfo(
    manufacturer="Example",
    model="Android Test",
    sdk=34,
    fingerprint="example/test/fingerprint",
)


class ManifestTests(unittest.TestCase):
    def test_file_manifest_hashes_bytes_and_stores_only_relative_path(self) -> None:
        payload = b"TransferKit photo bytes"
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            root = Path(folder)
            source = root / "DCIM" / "Camera" / "photo.jpg"
            source.parent.mkdir(parents=True)
            source.write_bytes(payload)
            builder = ManifestBuilder(
                DEVICE,
                transfer_id=UUID("00000000-0000-4000-8000-000000000001"),
            )
            item = builder.add_file(
                CategoryId.PHOTOS,
                source,
                root=root,
                provider="MediaStore",
                method="ContentResolver",
            )
            manifest = builder.build()
            self.assertEqual(item.size, len(payload))
            self.assertEqual(item.sha256, sha256_bytes(payload))
            self.assertEqual(item.metadata["path"], "DCIM/Camera/photo.jpg")
            self.assertNotIn(str(root), item.metadata["path"])
            encoded = manifest_json_bytes(manifest)
            self.assertFalse(encoded.startswith(b"\xef\xbb\xbf"))
            self.assertEqual(parse_manifest_json(encoded), manifest)
            self.assertEqual(load_manifest(write_manifest_file(root, encoded)), manifest)

    def test_serialized_api_records_get_individual_size_and_hash(self) -> None:
        record = b"BEGIN:VCARD\nVERSION:4.0\nFN:Example\nEND:VCARD"
        builder = ManifestBuilder(DEVICE)
        item = builder.add_record(
            CategoryId.CONTACTS,
            "contact-42",
            record,
            provider="ContactsContract",
            method="vCard",
            metadata={"format": "vCard"},
        )
        manifest = builder.build()
        self.assertEqual(item.size, len(record))
        self.assertEqual(item.sha256, sha256_bytes(record))
        self.assertEqual(manifest.categories[CategoryId.CONTACTS].items[0].id, "contact-42")

    def test_outside_root_duplicate_keys_and_invalid_schema_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            root = Path(folder) / "root"
            root.mkdir()
            outside = Path(folder) / "outside.jpg"
            outside.write_bytes(b"outside")
            builder = ManifestBuilder(DEVICE)
            with self.assertRaises(ManifestBuildError):
                builder.add_file(
                    CategoryId.PHOTOS,
                    outside,
                    root=root,
                    provider="MediaStore",
                    method="ContentResolver",
                )
            self.assertEqual(builder._categories, {})

        with self.assertRaises(ValueError):
            parse_manifest_json('{"version":"1.0","version":"1.0"}')
        with self.assertRaises(ValueError):
            parse_manifest_json(json.dumps({"version": "1.0", "categories": {"unknown": {}}}))

    def test_category_cannot_mix_access_methods(self) -> None:
        builder = ManifestBuilder(DEVICE)
        builder.add_category(CategoryId.PHOTOS, "MediaStore", "ContentResolver")
        with self.assertRaises(ManifestBuildError):
            builder.add_category(CategoryId.PHOTOS, "MediaStore", "SAF")

    def test_record_metadata_is_limited_to_64_kib(self) -> None:
        builder = ManifestBuilder(DEVICE)
        with self.assertRaisesRegex(ManifestBuildError, "64 KiB"):
            builder.add_record(
                CategoryId.CONTACTS,
                "contact-oversized",
                b"record",
                provider="ContactsContract",
                method="vCard",
                metadata={"unexpected": "x" * (64 * 1024)},
            )


def write_manifest_file(root: Path, payload: bytes) -> Path:
    path = root / "manifest.json"
    path.write_bytes(payload)
    return path


if __name__ == "__main__":
    unittest.main()
