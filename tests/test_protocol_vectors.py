"""Shared wire vectors keep the Python reference codec aligned with Android Kotlin."""

import json
import unittest
from pathlib import Path
from uuid import UUID

from transferkit.integrity import decode_chunk, encode_chunk
from transferkit.transport.usb_adb import (
    ACK_KIND_CHUNK,
    ACK_KIND_MANIFEST,
    ACK_MAGIC,
    ACK_STRUCT,
    CONTROL_VERSION,
    MANIFEST_HEADER,
    MANIFEST_MAGIC,
    AUTH_RESULT_STRUCT,
    CHALLENGE_STRUCT,
    FINISH_REQUEST_STRUCT,
    OFFER_ACK_STRUCT,
    OFFER_STRUCT,
    PROOF_STRUCT,
    RECEIPT_RESPONSE_HEADER,
    SOURCE_IDENTITY_STRUCT,
    usb_adb_auth_proof,
)


VECTORS = json.loads((Path(__file__).parents[1] / "shared" / "protocol-vectors.json").read_text("utf-8"))


class ProtocolVectorTests(unittest.TestCase):
    def test_chunk_frame_matches_shared_vector(self):
        vector = VECTORS["chunk"]
        frame = encode_chunk(
            UUID(vector["transfer_id"]),
            vector["category_id"],
            vector["item_id"],
            vector["chunk_index"],
            vector["total_chunks"],
            bytes.fromhex(vector["payload_hex"]),
        )
        self.assertEqual(frame.hex(), vector["frame_hex"])
        decoded = decode_chunk(frame)
        self.assertEqual(decoded.header.category_id_hash.hex(), vector["category_id_hash_hex"])
        self.assertEqual(decoded.header.item_id_hash.hex(), vector["item_id_hash_hex"])
        self.assertEqual(f"{decoded.header.payload_crc32:08x}", vector["crc32_hex"])

    def test_manifest_frame_matches_shared_vector(self):
        vector = VECTORS["manifest_frame"]
        payload = bytes.fromhex(vector["payload_hex"])
        frame = MANIFEST_HEADER.pack(MANIFEST_MAGIC, CONTROL_VERSION, len(payload)) + payload
        self.assertEqual(frame.hex(), vector["frame_hex"])

    def test_source_identity_matches_shared_vector(self):
        vector = VECTORS["source_identity"]
        frame = SOURCE_IDENTITY_STRUCT.pack(
            b"TKSI", CONTROL_VERSION, UUID(vector["transfer_id"]).bytes
        )
        self.assertEqual(frame.hex(), vector["frame_hex"])

    def test_ack_frames_match_shared_vectors(self):
        for key, expected_kind in (("chunk_ack", ACK_KIND_CHUNK), ("manifest_ack", ACK_KIND_MANIFEST)):
            vector = VECTORS[key]
            self.assertEqual(vector["kind"], expected_kind)
            frame = ACK_STRUCT.pack(
                ACK_MAGIC,
                CONTROL_VERSION,
                vector["kind"],
                UUID(vector["transfer_id"]).bytes,
                bytes.fromhex(vector["category_id_hash_hex"]),
                bytes.fromhex(vector["item_id_hash_hex"]),
                vector["chunk_index"],
                int(vector["payload_crc32_hex"], 16),
                vector["status"],
            )
            self.assertEqual(frame.hex(), vector["frame_hex"])

    def test_usb_adb_authentication_matches_shared_vectors(self):
        vector = VECTORS["usb_adb_auth"]
        transfer_id = UUID(vector["transfer_id"])
        token = bytes.fromhex(vector["token_hex"])
        nonce = bytes.fromhex(vector["nonce_hex"])
        self.assertEqual(
            OFFER_STRUCT.pack(b"TKSO", CONTROL_VERSION, transfer_id.bytes, token).hex(),
            vector["offer_hex"],
        )
        self.assertEqual(
            OFFER_ACK_STRUCT.pack(b"TKOA", CONTROL_VERSION, 0, transfer_id.bytes).hex(),
            vector["offer_ack_hex"],
        )
        self.assertEqual(
            CHALLENGE_STRUCT.pack(b"TKHC", CONTROL_VERSION, transfer_id.bytes, nonce).hex(),
            vector["challenge_hex"],
        )
        proof = usb_adb_auth_proof(transfer_id, token, nonce)
        self.assertEqual(PROOF_STRUCT.pack(b"TKHA", CONTROL_VERSION, proof).hex(), vector["proof_hex"])
        self.assertEqual(
            AUTH_RESULT_STRUCT.pack(b"TKOK", CONTROL_VERSION, 0, transfer_id.bytes).hex(),
            vector["auth_result_hex"],
        )
        self.assertEqual(
            FINISH_REQUEST_STRUCT.pack(b"TKFN", CONTROL_VERSION, transfer_id.bytes).hex(),
            vector["finish_request_hex"],
        )
        receipt_payload = b"{}"
        response = RECEIPT_RESPONSE_HEADER.pack(
            b"TKRC", CONTROL_VERSION, 0, transfer_id.bytes, len(receipt_payload)
        ) + receipt_payload
        self.assertEqual(response.hex(), vector["receipt_response_hex"])


if __name__ == "__main__":
    unittest.main()
