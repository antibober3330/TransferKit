package com.transferkit.client.identity

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationIdentityCryptoTest {
    @Test
    fun fingerprintIsStableLowercaseSha256OfEncodedPublicKey() {
        val pair = newKeyPair()
        val first = InstallationIdentityCrypto.fingerprint(pair.public.encoded)
        val second = InstallationIdentityCrypto.fingerprint(pair.public.encoded.copyOf())

        assertTrue(first.matches(Regex("^[a-f0-9]{64}$")))
        assertTrue(first == second)
    }

    @Test
    fun verifiesSignedChallengeOnlyForMatchingKeyAndMessage() {
        val pair = newKeyPair()
        val message = "TransferKit recovery challenge".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }
        val identity = InstallationIdentity(
            fingerprint = InstallationIdentityCrypto.fingerprint(pair.public.encoded),
            publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded),
        )

        assertTrue(InstallationIdentityCrypto.verifySignature(
            identity.publicKeyBase64,
            identity.fingerprint,
            message,
            signature,
        ))
        assertFalse(InstallationIdentityCrypto.verifySignature(
            identity.publicKeyBase64,
            identity.fingerprint,
            "different challenge".toByteArray(),
            signature,
        ))
        assertFalse(InstallationIdentityCrypto.verifySignature(
            identity.publicKeyBase64,
            "0".repeat(64),
            message,
            signature,
        ))
    }

    @Test
    fun malformedOrOversizedProofInputsAreRejected() {
        assertFalse(InstallationIdentityCrypto.verifySignature("!", "0".repeat(64), byteArrayOf(1), byteArrayOf(1)))
        assertFalse(InstallationIdentityCrypto.verifySignature("", "0".repeat(64), byteArrayOf(1), byteArrayOf(1)))
        assertFalse(InstallationIdentityCrypto.verifySignature("A".repeat(1400), "0".repeat(64), byteArrayOf(1), byteArrayOf(1)))
    }

    private fun newKeyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
}
