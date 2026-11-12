"""Recovery eligibility and installation-identity proof primitives.

The source-side transfer history is only a mirror. Callers must compare a signed
peer hello with the target's durable transfer record before creating a ResumePlan.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from .contracts import ReasonCode


_SHA256 = re.compile(r"^[a-f0-9]{64}$")
_ACTIVE_TARGET_STATUSES = frozenset({"RUNNING", "PAUSED", "RECONNECTING", "PAUSED_AWAITING_USER"})
_NONCE_BYTES = 32


@dataclass(frozen=True, slots=True)
class RecoveryTransferSnapshot:
    transfer_id: UUID
    manifest_sha256: str
    source_identity_fingerprint: str | None
    target_identity_fingerprint: str | None
    protocol_version: str | None
    status: str
    expires_at: datetime | None


@dataclass(frozen=True, slots=True)
class RecoveryHello:
    transfer_id: UUID
    manifest_sha256: str
    source_identity_fingerprint: str
    target_identity_fingerprint: str
    protocol_version: str


def recovery_block_reason(
    snapshot: RecoveryTransferSnapshot,
    hello: RecoveryHello,
    *,
    now: datetime | None = None,
) -> ReasonCode | None:
    """Return the first stable PAUSED_BLOCKED reason, or None when fields match.

    This is an eligibility check only; callers must also verify both signatures
    over ``recovery_proof_transcript`` before binding identities or accepting data.
    """
    if hello.transfer_id != snapshot.transfer_id or snapshot.status not in _ACTIVE_TARGET_STATUSES:
        return ReasonCode.RESUME_NOT_POSSIBLE
    if (
        snapshot.source_identity_fingerprint is None
        or snapshot.target_identity_fingerprint is None
        or snapshot.protocol_version is None
        or snapshot.expires_at is None
    ):
        return ReasonCode.RESUME_NOT_POSSIBLE

    current_time = now or datetime.now(timezone.utc)
    if current_time.tzinfo is None or current_time.utcoffset() != timezone.utc.utcoffset(current_time):
        raise ValueError("now must be timezone-aware UTC")
    expiry = snapshot.expires_at
    if expiry.tzinfo is None or expiry.utcoffset() != timezone.utc.utcoffset(expiry):
        raise ValueError("expires_at must be timezone-aware UTC")
    if expiry <= current_time:
        return ReasonCode.CHECKPOINT_EXPIRED
    if hello.manifest_sha256 != snapshot.manifest_sha256:
        return ReasonCode.SOURCE_MANIFEST_CHANGED
    if hello.protocol_version != snapshot.protocol_version:
        return ReasonCode.INCOMPATIBLE_PROTOCOL_VERSION
    if hello.source_identity_fingerprint != snapshot.source_identity_fingerprint:
        return ReasonCode.SOURCE_DEVICE_MISMATCH
    if hello.target_identity_fingerprint != snapshot.target_identity_fingerprint:
        return ReasonCode.TARGET_DEVICE_MISMATCH
    return None


def recovery_proof_transcript(
    *,
    transfer_id: UUID,
    manifest_sha256: str,
    protocol_version: str,
    source_identity_fingerprint: str,
    target_identity_fingerprint: str,
    source_nonce: bytes,
    target_nonce: bytes,
) -> bytes:
    """Create canonical cross-language bytes signed by both installation keys."""
    if not _SHA256.fullmatch(manifest_sha256):
        raise ValueError("manifest_sha256 must be a lowercase SHA-256 digest")
    if not _SHA256.fullmatch(source_identity_fingerprint):
        raise ValueError("source identity fingerprint must be a lowercase SHA-256 digest")
    if not _SHA256.fullmatch(target_identity_fingerprint):
        raise ValueError("target identity fingerprint must be a lowercase SHA-256 digest")
    if not re.fullmatch(r"[1-9][0-9]{0,4}", protocol_version):
        raise ValueError("protocol_version must be a positive decimal version")
    if len(source_nonce) != _NONCE_BYTES or len(target_nonce) != _NONCE_BYTES:
        raise ValueError("recovery nonces must each contain exactly 32 bytes")
    if hmac.compare_digest(source_nonce, target_nonce):
        raise ValueError("source and target recovery nonces must be different")

    document = {
        "version": "1.0",
        "transfer_id": str(transfer_id),
        "manifest_sha256": manifest_sha256,
        "protocol_version": protocol_version,
        "source_identity_fingerprint": source_identity_fingerprint,
        "target_identity_fingerprint": target_identity_fingerprint,
        "source_nonce": source_nonce.hex(),
        "target_nonce": target_nonce.hex(),
    }
    return json.dumps(document, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def verify_recovery_peer_signature(
    *,
    public_key_base64: str,
    expected_fingerprint: str,
    transcript: bytes,
    signature: bytes,
) -> bool:
    """Verify a P-256/SHA-256 proof and pin its public key to a stored fingerprint."""
    if (
        not _SHA256.fullmatch(expected_fingerprint)
        or not public_key_base64
        or len(public_key_base64) > 1368
        or not transcript
        or len(transcript) > 4096
        or not signature
        or len(signature) > 256
    ):
        return False
    try:
        encoded_key = base64.b64decode(public_key_base64, validate=True)
        if not encoded_key or len(encoded_key) > 1024:
            return False
        actual_fingerprint = hashlib.sha256(encoded_key).hexdigest()
        if not hmac.compare_digest(actual_fingerprint, expected_fingerprint):
            return False
        public_key = serialization.load_der_public_key(encoded_key)
        if not isinstance(public_key, ec.EllipticCurvePublicKey):
            return False
        if not isinstance(public_key.curve, ec.SECP256R1):
            return False
        public_key.verify(signature, transcript, ec.ECDSA(hashes.SHA256()))
        return True
    except (ValueError, TypeError, InvalidSignature):
        return False
