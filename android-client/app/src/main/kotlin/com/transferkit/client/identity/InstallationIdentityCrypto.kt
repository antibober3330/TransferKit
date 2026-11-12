package com.transferkit.client.identity

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Pure-JCA helpers shared by the Android Keystore adapter and JVM unit tests. */
object InstallationIdentityCrypto {
    private const val MAX_PUBLIC_KEY_BYTES = 1024
    private const val MAX_PUBLIC_KEY_BASE64_CHARS = 4 * ((MAX_PUBLIC_KEY_BYTES + 2) / 3)
    private const val MAX_SIGNED_MESSAGE_BYTES = 64 * 1024
    private const val MAX_SIGNATURE_BYTES = 256
    private val fingerprintPattern = Regex("^[a-f0-9]{64}$")
    private val hexAlphabet = "0123456789abcdef".toCharArray()

    fun fingerprint(publicKeyEncoded: ByteArray): String {
        require(publicKeyEncoded.isNotEmpty() && publicKeyEncoded.size <= MAX_PUBLIC_KEY_BYTES) {
            "Public key encoding has an invalid length"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hexAlphabet[value ushr 4])
                append(hexAlphabet[value and 0x0f])
            }
        }
    }

    fun verifySignature(
        publicKeyBase64: String,
        expectedFingerprint: String,
        message: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean {
        if (!fingerprintPattern.matches(expectedFingerprint)) return false
        if (publicKeyBase64.isEmpty() || publicKeyBase64.length > MAX_PUBLIC_KEY_BASE64_CHARS) return false
        if (message.isEmpty() || message.size > MAX_SIGNED_MESSAGE_BYTES) return false
        if (signatureBytes.isEmpty() || signatureBytes.size > MAX_SIGNATURE_BYTES) return false

        return try {
            val encodedKey = Base64.getDecoder().decode(publicKeyBase64)
            if (encodedKey.isEmpty() || encodedKey.size > MAX_PUBLIC_KEY_BYTES) return false
            val actualFingerprint = MessageDigest.getInstance("SHA-256").digest(encodedKey)
            val expectedBytes = decodeFingerprint(expectedFingerprint)
            if (!MessageDigest.isEqual(expectedBytes, actualFingerprint)) return false

            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encodedKey))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(message)
                verify(signatureBytes)
            }
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    private fun decodeFingerprint(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        val high = hexAlphabet.indexOf(value[index * 2])
        val low = hexAlphabet.indexOf(value[index * 2 + 1])
        ((high shl 4) or low).toByte()
    }
}
