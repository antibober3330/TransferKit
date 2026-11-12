import base64
import hashlib
import unittest
from datetime import datetime, timedelta, timezone
from uuid import UUID

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from transferkit.contracts import ReasonCode
from transferkit.recovery import (
    RecoveryHello,
    RecoveryTransferSnapshot,
    recovery_block_reason,
    recovery_proof_transcript,
    verify_recovery_peer_signature,
)


TRANSFER_ID = UUID("00000000-0000-4000-8000-000000000001")
MANIFEST_SHA = "a" * 64
SOURCE_IDENTITY = "b" * 64
TARGET_IDENTITY = "c" * 64
PROTOCOL_VERSION = "1"
NOW = datetime(2026, 11, 12, 12, 0, tzinfo=timezone.utc)


def snapshot(**updates) -> RecoveryTransferSnapshot:
    values = {
        "transfer_id": TRANSFER_ID,
        "manifest_sha256": MANIFEST_SHA,
        "source_identity_fingerprint": SOURCE_IDENTITY,
        "target_identity_fingerprint": TARGET_IDENTITY,
        "protocol_version": PROTOCOL_VERSION,
        "status": "PAUSED",
        "expires_at": NOW + timedelta(days=1),
    }
    values.update(updates)
    return RecoveryTransferSnapshot(**values)


def hello(**updates) -> RecoveryHello:
    values = {
        "transfer_id": TRANSFER_ID,
        "manifest_sha256": MANIFEST_SHA,
        "source_identity_fingerprint": SOURCE_IDENTITY,
        "target_identity_fingerprint": TARGET_IDENTITY,
        "protocol_version": PROTOCOL_VERSION,
    }
    values.update(updates)
    return RecoveryHello(**values)


class RecoveryHandshakeTests(unittest.TestCase):
    def test_matching_hello_is_eligible(self) -> None:
        self.assertIsNone(recovery_block_reason(snapshot(), hello(), now=NOW))

    def test_checkpoint_expiry_blocks_resume_before_other_mismatches(self) -> None:
        reason = recovery_block_reason(
            snapshot(expires_at=NOW, protocol_version="2"),
            hello(manifest_sha256="d" * 64),
            now=NOW,
        )
        self.assertEqual(reason, ReasonCode.CHECKPOINT_EXPIRED)

    def test_manifest_protocol_and_device_mismatches_have_specific_reasons(self) -> None:
        cases = (
            (hello(manifest_sha256="d" * 64), ReasonCode.SOURCE_MANIFEST_CHANGED),
            (hello(protocol_version="2"), ReasonCode.INCOMPATIBLE_PROTOCOL_VERSION),
            (hello(source_identity_fingerprint="d" * 64), ReasonCode.SOURCE_DEVICE_MISMATCH),
            (hello(target_identity_fingerprint="d" * 64), ReasonCode.TARGET_DEVICE_MISMATCH),
        )
        for peer, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(recovery_block_reason(snapshot(), peer, now=NOW), expected)

    def test_missing_identity_legacy_record_and_wrong_transfer_are_not_resumable(self) -> None:
        self.assertEqual(
            recovery_block_reason(snapshot(source_identity_fingerprint=None), hello(), now=NOW),
            ReasonCode.RESUME_NOT_POSSIBLE,
        )
        self.assertEqual(
            recovery_block_reason(snapshot(), hello(transfer_id=UUID(int=2)), now=NOW),
            ReasonCode.RESUME_NOT_POSSIBLE,
        )
        self.assertEqual(
            recovery_block_reason(snapshot(status="COMPLETED"), hello(), now=NOW),
            ReasonCode.RESUME_NOT_POSSIBLE,
        )

    def test_transcript_is_canonical_and_binds_both_fresh_nonces(self) -> None:
        transcript = recovery_proof_transcript(
            transfer_id=TRANSFER_ID,
            manifest_sha256=MANIFEST_SHA,
            protocol_version=PROTOCOL_VERSION,
            source_identity_fingerprint=SOURCE_IDENTITY,
            target_identity_fingerprint=TARGET_IDENTITY,
            source_nonce=bytes(range(32)),
            target_nonce=bytes(range(32, 64)),
        )
        self.assertEqual(
            transcript,
            b'{"manifest_sha256":"' + MANIFEST_SHA.encode() + b'","protocol_version":"1",'
            b'"source_identity_fingerprint":"' + SOURCE_IDENTITY.encode() + b'","source_nonce":"'
            + bytes(range(32)).hex().encode() + b'","target_identity_fingerprint":"' + TARGET_IDENTITY.encode()
            + b'","target_nonce":"' + bytes(range(32, 64)).hex().encode()
            + b'","transfer_id":"00000000-0000-4000-8000-000000000001","version":"1.0"}',
        )
        with self.assertRaisesRegex(ValueError, "must be different"):
            recovery_proof_transcript(
                transfer_id=TRANSFER_ID,
                manifest_sha256=MANIFEST_SHA,
                protocol_version=PROTOCOL_VERSION,
                source_identity_fingerprint=SOURCE_IDENTITY,
                target_identity_fingerprint=TARGET_IDENTITY,
                source_nonce=b"x" * 32,
                target_nonce=b"x" * 32,
            )

    def test_peer_signature_verifies_only_for_the_pinned_key_and_exact_transcript(self) -> None:
        private_key = ec.generate_private_key(ec.SECP256R1())
        public_bytes = private_key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        fingerprint = hashlib.sha256(public_bytes).hexdigest()
        encoded_public_key = base64.b64encode(public_bytes).decode("ascii")
        transcript = b"TransferKit recovery proof test"
        signature = private_key.sign(transcript, ec.ECDSA(hashes.SHA256()))

        self.assertTrue(
            verify_recovery_peer_signature(
                public_key_base64=encoded_public_key,
                expected_fingerprint=fingerprint,
                transcript=transcript,
                signature=signature,
            )
        )
        self.assertFalse(
            verify_recovery_peer_signature(
                public_key_base64=encoded_public_key,
                expected_fingerprint="0" * 64,
                transcript=transcript,
                signature=signature,
            )
        )
        self.assertFalse(
            verify_recovery_peer_signature(
                public_key_base64=encoded_public_key,
                expected_fingerprint=fingerprint,
                transcript=transcript + b" altered",
                signature=signature,
            )
        )


if __name__ == "__main__":
    unittest.main()
