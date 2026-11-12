package com.transferkit.client.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Byte-exact authentication frames for the local-only, ADB-forwarded USB profile. */
object UsbAdbAuthProtocol {
    const val TOKEN_SIZE = 32
    const val NONCE_SIZE = 32
    const val OFFER_SIZE = 53
    const val OFFER_ACK_SIZE = 22
    const val CHALLENGE_SIZE = 53
    const val PROOF_SIZE = 37
    const val AUTH_RESULT_SIZE = 22
    private val domain = "TransferKit USB ADB v1\u0000".toByteArray(StandardCharsets.US_ASCII)

    data class Offer(val transferId: UUID, val token: ByteArray)
    data class Challenge(val transferId: UUID, val nonce: ByteArray)
    data class Result(val transferId: UUID, val status: Int)

    fun encodeOffer(transferId: UUID, token: ByteArray): ByteArray {
        require(token.size == TOKEN_SIZE) { "Session token must be 32 bytes" }
        return ByteBuffer.allocate(OFFER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putAscii("TKSO").put(1).putUuid(transferId).put(token).array()
    }

    fun decodeOffer(frame: ByteArray): Offer {
        require(frame.size == OFFER_SIZE) { "Invalid token offer length" }
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        require(buffer.readAscii(4) == "TKSO" && buffer.get().toInt() and 0xff == 1) {
            "Invalid token offer frame"
        }
        return Offer(buffer.getUuid(), ByteArray(TOKEN_SIZE).also(buffer::get))
    }

    fun encodeOfferAck(transferId: UUID, status: Int): ByteArray = encodeResult("TKOA", transferId, status)

    fun encodeChallenge(transferId: UUID, nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_SIZE) { "Challenge nonce must be 32 bytes" }
        return ByteBuffer.allocate(CHALLENGE_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putAscii("TKHC").put(1).putUuid(transferId).put(nonce).array()
    }

    fun decodeChallenge(frame: ByteArray): Challenge {
        require(frame.size == CHALLENGE_SIZE) { "Invalid challenge length" }
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        require(buffer.readAscii(4) == "TKHC" && buffer.get().toInt() and 0xff == 1) {
            "Invalid challenge frame"
        }
        return Challenge(buffer.getUuid(), ByteArray(NONCE_SIZE).also(buffer::get))
    }

    fun encodeProof(transferId: UUID, token: ByteArray, nonce: ByteArray): ByteArray {
        require(token.size == TOKEN_SIZE && nonce.size == NONCE_SIZE)
        return ByteBuffer.allocate(PROOF_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putAscii("TKHA").put(1).put(proof(transferId, token, nonce)).array()
    }

    fun decodeProof(frame: ByteArray): ByteArray {
        require(frame.size == PROOF_SIZE) { "Invalid authentication proof length" }
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        require(buffer.readAscii(4) == "TKHA" && buffer.get().toInt() and 0xff == 1) {
            "Invalid authentication proof frame"
        }
        return ByteArray(32).also(buffer::get)
    }

    fun proof(transferId: UUID, token: ByteArray, nonce: ByteArray): ByteArray {
        require(token.size == TOKEN_SIZE && nonce.size == NONCE_SIZE)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token, "HmacSHA256"))
        mac.update(domain)
        mac.update(uuidBytes(transferId))
        return mac.doFinal(nonce)
    }

    fun constantTimeEquals(expected: ByteArray, actual: ByteArray): Boolean =
        MessageDigest.isEqual(expected, actual)

    fun encodeAuthResult(transferId: UUID, status: Int): ByteArray = encodeResult("TKOK", transferId, status)

    fun decodeResult(frame: ByteArray, expectedMagic: String): Result {
        require(frame.size == AUTH_RESULT_SIZE) { "Invalid authentication result length" }
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        require(buffer.readAscii(4) == expectedMagic && buffer.get().toInt() and 0xff == 1) {
            "Invalid authentication result frame"
        }
        val status = buffer.get().toInt() and 0xff
        return Result(buffer.getUuid(), status)
    }

    private fun encodeResult(magic: String, transferId: UUID, status: Int): ByteArray {
        require(status in 0..255)
        return ByteBuffer.allocate(AUTH_RESULT_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putAscii(magic).put(1).put(status.toByte()).putUuid(transferId).array()
    }

    private fun uuidBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(16).putUuid(uuid).array()
    private fun ByteBuffer.putAscii(value: String) = put(value.toByteArray(StandardCharsets.US_ASCII))
    private fun ByteBuffer.readAscii(size: Int) = String(ByteArray(size).also(::get), StandardCharsets.US_ASCII)
    private fun ByteBuffer.putUuid(value: UUID) = putLong(value.mostSignificantBits).putLong(value.leastSignificantBits)
    private fun ByteBuffer.getUuid() = UUID(long, long)
}
