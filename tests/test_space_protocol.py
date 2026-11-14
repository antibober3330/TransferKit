"""Space Preflight wire framing and versioned ItemResult ACK tests."""

import io
import json
import unittest
from pathlib import Path
from uuid import UUID

from transferkit.contracts import SpaceDecision, SpaceReport
from transferkit.space_protocol import (
    MAX_SPACE_FRAME_BYTES,
    SPACE_DECISION_MAGIC,
    SPACE_FRAME_HEADER,
    SPACE_FRAME_VERSION,
    SPACE_REPORT_MAGIC,
    decode_space_decision_frame,
    decode_space_report_frame,
    encode_space_decision_frame,
    encode_space_report_frame,
    read_space_decision_frame,
    read_space_report_frame,
)
from transferkit.transport.usb_adb import (
    ACK_KIND_ITEM_RESULT,
    ACK_ITEM_RESULT_PROTOCOL_VERSION,
    ACK_ITEM_RESULT_SENTINEL,
    ACK_STRUCT,
    ITEM_RESULT_FAILED_INTEGRITY,
    ITEM_RESULT_RESTORED_POST_VERIFIED,
    ITEM_RESULT_RESTORE_FAILED,
    ItemResultAcknowledgement,
    decode_item_result_ack,
    encode_item_result_ack,
)


TRANSFER_ID = UUID("00000000-0000-4000-8000-000000000001")
VECTORS = json.loads((Path(__file__).parents[1] / "shared" / "protocol-vectors.json").read_text("utf-8"))
REPORT = {
    "version": "1.0",
    "protocol_version": "2",
    "transfer_id": str(TRANSFER_ID),
    "available_bytes": None,
    "allocatable_bytes": None,
    "safety_margin_bytes": None,
    "safe_available_bytes": None,
    "staging_leftover_bytes": 0,
    "total_manifest_bytes": 0,
    "largest_item_bytes": 0,
    "batch_peak_bytes": None,
    "streaming_peak_bytes": None,
    "estimates_complete": False,
    "unknown_item_ids": [],
    "strategy": "insufficient",
    "decision_required": True,
    "kept_item_ids": [],
    "skipped_item_ids": [],
}
DECISION = {
    "version": "1.0",
    "protocol_version": "2",
    "transfer_id": str(TRANSFER_ID),
    "strategy": "insufficient",
    "user_confirmed": False,
    "kept_item_ids": [],
    "skipped_item_ids": [],
}


class SpaceProtocolTests(unittest.TestCase):
    def test_report_frame_round_trip_and_stream_read(self):
        frame = encode_space_report_frame(REPORT)
        self.assertEqual(SPACE_REPORT_MAGIC, frame[:4])
        self.assertEqual(SPACE_FRAME_VERSION, frame[4])
        vector = VECTORS["space_report_frame"]
        self.assertEqual(vector["frame_hex"], frame.hex())
        self.assertEqual(vector["payload_utf8"].encode(), frame[SPACE_FRAME_HEADER.size :])
        self.assertEqual(decode_space_report_frame(frame), SpaceReport.model_validate(REPORT))
        self.assertEqual(read_space_report_frame(io.BytesIO(frame)), SpaceReport.model_validate(REPORT))

    def test_decision_frame_round_trip_and_stream_read(self):
        frame = encode_space_decision_frame(DECISION)
        self.assertEqual(SPACE_DECISION_MAGIC, frame[:4])
        vector = VECTORS["space_decision_frame"]
        self.assertEqual(vector["frame_hex"], frame.hex())
        self.assertEqual(vector["payload_utf8"].encode(), frame[SPACE_FRAME_HEADER.size :])
        self.assertEqual(decode_space_decision_frame(frame), SpaceDecision.model_validate(DECISION))
        self.assertEqual(read_space_decision_frame(io.BytesIO(frame)), SpaceDecision.model_validate(DECISION))

    def test_rejects_wrong_magic_version_length_and_trailing_bytes(self):
        valid = encode_space_report_frame(REPORT)
        cases = (
            b"TKSD" + valid[4:],
            valid[:4] + bytes((SPACE_FRAME_VERSION + 1,)) + valid[5:],
            valid[:-1],
            valid + b"x",
        )
        for frame in cases:
            with self.subTest(frame_prefix=frame[:5]):
                with self.assertRaises(ValueError):
                    decode_space_report_frame(frame)

    def test_rejects_oversized_declaration_before_reading_payload(self):
        header = SPACE_FRAME_HEADER.pack(SPACE_REPORT_MAGIC, SPACE_FRAME_VERSION, MAX_SPACE_FRAME_BYTES + 1)
        with self.assertRaises(ValueError):
            read_space_report_frame(io.BytesIO(header))

    def test_rejects_noncanonical_json_and_wrong_negotiated_version(self):
        canonical = encode_space_report_frame(REPORT)
        payload = canonical[SPACE_FRAME_HEADER.size :]
        document = json.loads(payload)
        noncanonical = json.dumps(document, ensure_ascii=False).encode()
        frame = SPACE_FRAME_HEADER.pack(SPACE_REPORT_MAGIC, SPACE_FRAME_VERSION, len(noncanonical)) + noncanonical
        with self.assertRaisesRegex(ValueError, "canonical"):
            decode_space_report_frame(frame)

        wrong_protocol = dict(REPORT, protocol_version="1")
        with self.assertRaises(ValueError):
            encode_space_report_frame(wrong_protocol)

    def test_item_result_ack_uses_v2_kind_and_reserved_sentinel(self):
        ack = ItemResultAcknowledgement(
            transfer_id=TRANSFER_ID,
            category_id_hash=bytes.fromhex("84a6dae4"),
            item_id_hash=bytes.fromhex("4ce1c689"),
            status=ITEM_RESULT_RESTORED_POST_VERIFIED,
        )
        frame = encode_item_result_ack(ack)
        self.assertEqual(VECTORS["item_result_ack"]["frame_hex"], frame.hex())
        magic, version, kind, _, _, _, sentinel, crc, status = ACK_STRUCT.unpack(frame)
        self.assertEqual((b"TKAK", ACK_ITEM_RESULT_PROTOCOL_VERSION, ACK_KIND_ITEM_RESULT), (magic, version, kind))
        self.assertEqual((ACK_ITEM_RESULT_SENTINEL, 0, ITEM_RESULT_RESTORED_POST_VERIFIED), (sentinel, crc, status))
        self.assertEqual(ack, decode_item_result_ack(frame))

    def test_item_result_ack_allows_only_defined_terminal_statuses(self):
        for status in (ITEM_RESULT_RESTORED_POST_VERIFIED, ITEM_RESULT_RESTORE_FAILED, ITEM_RESULT_FAILED_INTEGRITY):
            ack = ItemResultAcknowledgement(TRANSFER_ID, b"1234", b"abcd", status)
            self.assertEqual(ack, decode_item_result_ack(encode_item_result_ack(ack)))
        with self.assertRaises(ValueError):
            encode_item_result_ack(ItemResultAcknowledgement(TRANSFER_ID, b"1234", b"abcd", 3))

    def test_item_result_ack_rejects_legacy_version_bad_sentinel_and_crc(self):
        ack = ItemResultAcknowledgement(TRANSFER_ID, b"1234", b"abcd", ITEM_RESULT_RESTORE_FAILED)
        valid = bytearray(encode_item_result_ack(ack))
        legacy_version = bytearray(valid)
        legacy_version[4] = 1
        bad_sentinel = bytearray(valid)
        bad_sentinel[30:34] = (0).to_bytes(4, "big")
        bad_crc = bytearray(valid)
        bad_crc[34:38] = (1).to_bytes(4, "big")
        for frame in (bytes(valid[:-1]), bytes(legacy_version), bytes(bad_sentinel), bytes(bad_crc)):
            with self.subTest(frame=frame.hex()):
                with self.assertRaises(ValueError):
                    decode_item_result_ack(frame)


if __name__ == "__main__":
    unittest.main()
