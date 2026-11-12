package com.transferkit.client.identity

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecoveryProofTranscriptTest {
    @Test
    fun transcriptMatchesCanonicalCrossLanguageVector() {
        val transcript = RecoveryProofTranscript.encode(
            transferId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
            manifestSha256 = "a".repeat(64),
            protocolVersion = "1",
            sourceIdentityFingerprint = "b".repeat(64),
            targetIdentityFingerprint = "c".repeat(64),
            sourceNonce = ByteArray(32) { it.toByte() },
            targetNonce = ByteArray(32) { (it + 32).toByte() },
        )
        val expected = (
            "{\"manifest_sha256\":\"${"a".repeat(64)}\",\"protocol_version\":\"1\"," +
                "\"source_identity_fingerprint\":\"${"b".repeat(64)}\",\"source_nonce\":" +
                "\"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\"," +
                "\"target_identity_fingerprint\":\"${"c".repeat(64)}\",\"target_nonce\":" +
                "\"202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f\"," +
                "\"transfer_id\":\"00000000-0000-4000-8000-000000000001\",\"version\":\"1.0\"}"
            ).toByteArray(Charsets.UTF_8)

        assertArrayEquals(expected, transcript)
    }

    @Test
    fun rejectsInvalidIdentityAndReplayedNoncePair() {
        val transferId = UUID.fromString("00000000-0000-4000-8000-000000000001")
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryProofTranscript.encode(
                transferId = transferId,
                manifestSha256 = "a".repeat(64),
                protocolVersion = "1",
                sourceIdentityFingerprint = "INVALID",
                targetIdentityFingerprint = "c".repeat(64),
                sourceNonce = ByteArray(32),
                targetNonce = ByteArray(32) { 1 },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryProofTranscript.encode(
                transferId = transferId,
                manifestSha256 = "a".repeat(64),
                protocolVersion = "1",
                sourceIdentityFingerprint = "b".repeat(64),
                targetIdentityFingerprint = "c".repeat(64),
                sourceNonce = ByteArray(32) { 7 },
                targetNonce = ByteArray(32) { 7 },
            )
        }
    }
}
