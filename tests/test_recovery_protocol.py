import io
import json
import unittest
from pathlib import Path

from transferkit.recovery_protocol import (
    MAX_RECOVERY_PAYLOAD_BYTES,
    RecoveryFrameKind,
    decode_recovery_frame,
    encode_recovery_frame,
    read_recovery_frame,
)


class RecoveryProtocolFrameTests(unittest.TestCase):
    def test_frame_uses_canonical_bounded_json_and_round_trips(self) -> None:
        payload = {"version": "1.0"}
        frame = encode_recovery_frame(RecoveryFrameKind.HELLO, payload)
        vectors = json.loads((Path(__file__).resolve().parents[1] / "shared" / "protocol-vectors.json").read_text())
        self.assertEqual(frame.hex(), vectors["recovery_hello_frame"]["frame_hex"])
        decoded = decode_recovery_frame(frame, expected_kind=RecoveryFrameKind.HELLO)
        self.assertEqual(decoded.kind, RecoveryFrameKind.HELLO)
        self.assertEqual(decoded.document, payload)

    def test_stream_reader_handles_short_reads(self) -> None:
        frame = encode_recovery_frame(RecoveryFrameKind.CHECKPOINT, {"z": 1, "a": "ok"})

        class ShortReadStream(io.BytesIO):
            def read(self, size=-1):
                return super().read(min(size, 2))

        decoded = read_recovery_frame(ShortReadStream(frame), expected_kind=RecoveryFrameKind.CHECKPOINT)
        self.assertEqual(decoded.document, {"a": "ok", "z": 1})

    def test_rejects_wrong_magic_version_length_and_kind(self) -> None:
        valid = encode_recovery_frame(RecoveryFrameKind.HELLO, {"version": "1.0"})
        bad_magic = b"BAD!" + valid[4:]
        bad_version = valid[:4] + b"\x02" + valid[5:]
        bad_size = valid[:5] + (1).to_bytes(4, "big") + valid[9:]
        for frame in (bad_magic, bad_version, bad_size):
            with self.subTest(frame=frame[:5]):
                with self.assertRaises(ValueError):
                    decode_recovery_frame(frame)
        with self.assertRaisesRegex(ValueError, "Unexpected"):
            decode_recovery_frame(valid, expected_kind=RecoveryFrameKind.PROOF)

    def test_rejects_noncanonical_invalid_and_nonobject_json(self) -> None:
        for payload in (b'{"b":1,"a":2}', b"[]", b"{", b'{"x": NaN}'):
            frame = b"TKRH\x01" + len(payload).to_bytes(4, "big") + payload
            with self.subTest(payload=payload):
                with self.assertRaises(ValueError):
                    decode_recovery_frame(frame)

    def test_rejects_empty_oversized_payload_and_truncated_stream(self) -> None:
        with self.assertRaises(ValueError):
            decode_recovery_frame(b"TKRH\x01\x00\x00\x00\x00")
        too_large = b"TKRH\x01" + (MAX_RECOVERY_PAYLOAD_BYTES + 1).to_bytes(4, "big")
        with self.assertRaises(ValueError):
            decode_recovery_frame(too_large)
        with self.assertRaises(EOFError):
            read_recovery_frame(io.BytesIO(b"TKRH\x01\x00\x00\x00\x05{}"))

    def test_stream_reader_rejects_invalid_header_before_reading_payload(self) -> None:
        class CountingStream(io.BytesIO):
            bytes_read = 0

            def read(self, size=-1):
                data = super().read(size)
                self.bytes_read += len(data)
                return data

        large_payload_size = MAX_RECOVERY_PAYLOAD_BYTES
        invalid_magic = CountingStream(b"BAD!\x01" + large_payload_size.to_bytes(4, "big"))
        with self.assertRaisesRegex(ValueError, "Unknown Recovery frame magic"):
            read_recovery_frame(invalid_magic)
        self.assertEqual(invalid_magic.bytes_read, 9)

        wrong_kind = CountingStream(b"TKRH\x01" + large_payload_size.to_bytes(4, "big"))
        with self.assertRaisesRegex(ValueError, "Unexpected Recovery frame kind"):
            read_recovery_frame(wrong_kind, expected_kind=RecoveryFrameKind.PROOF)
        self.assertEqual(wrong_kind.bytes_read, 9)

        oversized = CountingStream(
            b"TKRH\x01" + (MAX_RECOVERY_PAYLOAD_BYTES + 1).to_bytes(4, "big")
        )
        with self.assertRaisesRegex(ValueError, "payload size"):
            read_recovery_frame(oversized)
        self.assertEqual(oversized.bytes_read, 9)


if __name__ == "__main__":
    unittest.main()
