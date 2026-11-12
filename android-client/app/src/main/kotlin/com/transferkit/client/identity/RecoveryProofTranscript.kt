package com.transferkit.client.identity

import java.util.UUID

/** Canonical signed bytes shared with the Python Recovery reference implementation. */
object RecoveryProofTranscript {
    private val digestPattern = Regex("^[a-f0-9]{64}$")
    private val protocolPattern = Regex("^[1-9][0-9]{0,4}$")

    fun encode(
        transferId: UUID,
        manifestSha256: String,
        protocolVersion: String,
        sourceIdentityFingerprint: String,
        targetIdentityFingerprint: String,
        sourceNonce: ByteArray,
        targetNonce: ByteArray,
    ): ByteArray {
        require(digestPattern.matches(manifestSha256)) { "manifest_sha256 must be a lowercase SHA-256 digest" }
        require(digestPattern.matches(sourceIdentityFingerprint)) { "source identity fingerprint is invalid" }
        require(digestPattern.matches(targetIdentityFingerprint)) { "target identity fingerprint is invalid" }
        require(protocolPattern.matches(protocolVersion)) { "protocol_version must be a positive decimal version" }
        require(sourceNonce.size == NONCE_BYTES && targetNonce.size == NONCE_BYTES) {
            "recovery nonces must each contain exactly 32 bytes"
        }
        require(!sourceNonce.contentEquals(targetNonce)) { "source and target recovery nonces must be different" }

        // All values are constrained to lowercase hex, canonical UUID, or decimal text, so no
        // JSON escaping is needed. Property order is Unicode code point order.
        val json = buildString {
            append("{\"manifest_sha256\":\"").append(manifestSha256)
            append("\",\"protocol_version\":\"").append(protocolVersion)
            append("\",\"source_identity_fingerprint\":\"").append(sourceIdentityFingerprint)
            append("\",\"source_nonce\":\"").append(sourceNonce.toHex())
            append("\",\"target_identity_fingerprint\":\"").append(targetIdentityFingerprint)
            append("\",\"target_nonce\":\"").append(targetNonce.toHex())
            append("\",\"transfer_id\":\"").append(transferId.toString().lowercase())
            append("\",\"version\":\"1.0\"}")
        }
        return json.toByteArray(Charsets.UTF_8)
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private const val NONCE_BYTES = 32
    private const val HEX = "0123456789abcdef"
}
