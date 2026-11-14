import tempfile
import unittest
import hashlib
from pathlib import Path
from unittest.mock import patch
from uuid import UUID

from transferkit.integrity import (
    CHUNK_HEADER_SIZE,
    ChunkError,
    decode_chunk,
    encode_chunk,
    id_hash,
    iter_file_chunks,
    sha256_bytes,
    sha256_file,
    verify_sha256,
)


TRANSFER_ID = "00000000-0000-4000-8000-000000000001"


class IntegrityTests(unittest.TestCase):
    def test_header_layout_and_roundtrip(self) -> None:
        frame = encode_chunk(TRANSFER_ID, "photos", "photo-1", 0, 1, b"abc")
        decoded = decode_chunk(frame)
        self.assertEqual(CHUNK_HEADER_SIZE, 45)
        self.assertEqual(decoded.header.transfer_id, UUID(TRANSFER_ID))
        self.assertEqual(decoded.header.category_id_hash, id_hash("photos"))
        self.assertEqual(decoded.header.item_id_hash, id_hash("photo-1"))
        self.assertEqual(decoded.payload, b"abc")

    def test_empty_payload_is_valid_and_crc_corruption_is_rejected(self) -> None:
        frame = encode_chunk(TRANSFER_ID, "photos", "empty", 0, 1, b"")
        self.assertEqual(decode_chunk(frame).payload, b"")
        corrupted = frame[:-1] + b"x"
        with self.assertRaises(ChunkError):
            decode_chunk(corrupted)

    def test_invalid_frames_and_indexes_are_rejected(self) -> None:
        with self.assertRaises(ChunkError):
            decode_chunk(b"TKCH")
        valid = encode_chunk(TRANSFER_ID, "photos", "p1", 0, 1, b"x")
        with self.assertRaises(ChunkError):
            decode_chunk(b"NOPE" + valid[4:])
        with self.assertRaises(ChunkError):
            decode_chunk(valid[:-1])
        with self.assertRaises(ChunkError):
            encode_chunk(TRANSFER_ID, "photos", "p1", 1, 1, b"x")
        with self.assertRaises(ChunkError):
            encode_chunk(TRANSFER_ID, "unknown", "p1", 0, 1, b"x")

    def test_hash_verification_and_streamed_file_chunks(self) -> None:
        content = b"abcdefghij"
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            path = Path(folder) / "payload.bin"
            path.write_bytes(content)
            self.assertEqual(sha256_file(path), sha256_bytes(content))
            self.assertTrue(verify_sha256(content, sha256_bytes(content)))
            self.assertFalse(verify_sha256(content, "f" * 64))
            frames = list(iter_file_chunks(path, TRANSFER_ID, "photos", "p1", len(content), chunk_size=4))
        self.assertEqual([decode_chunk(frame).payload for frame in frames], [b"abcd", b"efgh", b"ij"])

    def test_hash_verification_requires_canonical_lowercase_hex(self) -> None:
        content = b"TransferKit canonical digest"
        digest = sha256_bytes(content)
        self.assertNotEqual(digest, digest.upper())
        self.assertTrue(verify_sha256(content, digest))
        self.assertFalse(verify_sha256(content, digest.upper()))
        malformed = ("g" * 64, " " + "a" * 63, "a" * 65, "", None, 123)
        with patch("transferkit.integrity.sha256_bytes", side_effect=AssertionError("must not hash invalid input")):
            for expected in malformed:
                with self.subTest(expected=expected):
                    self.assertFalse(verify_sha256(content, expected))

    def test_zero_byte_file_produces_one_empty_chunk(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            path = Path(folder) / "empty.bin"
            path.write_bytes(b"")
            frames = list(iter_file_chunks(path, TRANSFER_ID, "photos", "empty", 0, chunk_size=4))
        self.assertEqual(len(frames), 1)
        self.assertEqual(decode_chunk(frames[0]).payload, b"")

    def test_resume_chunk_iterator_skips_confirmed_frames_but_hashes_full_source(self) -> None:
        content = b"abcdefghij"
        digest = hashlib.sha256()
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            path = Path(folder) / "resume.bin"
            path.write_bytes(content)
            frames = list(
                iter_file_chunks(
                    path,
                    TRANSFER_ID,
                    "photos",
                    "p1",
                    len(content),
                    chunk_size=4,
                    start_chunk=1,
                    on_payload=digest.update,
                )
            )
        decoded = [decode_chunk(frame) for frame in frames]
        self.assertEqual([chunk.header.chunk_index for chunk in decoded], [1, 2])
        self.assertEqual([chunk.header.total_chunks for chunk in decoded], [3, 3])
        self.assertEqual([chunk.payload for chunk in decoded], [b"efgh", b"ij"])
        self.assertEqual(digest.hexdigest(), sha256_bytes(content))

    def test_resume_chunk_iterator_rejects_out_of_range_checkpoint(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            path = Path(folder) / "resume.bin"
            path.write_bytes(b"12345")
            with self.assertRaisesRegex(ValueError, "start_chunk"):
                list(iter_file_chunks(path, TRANSFER_ID, "photos", "p1", 5, chunk_size=4, start_chunk=3))


if __name__ == "__main__":
    unittest.main()
