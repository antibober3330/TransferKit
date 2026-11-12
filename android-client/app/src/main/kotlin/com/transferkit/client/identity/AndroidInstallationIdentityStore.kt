package com.transferkit.client.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Installation-scoped identity. The private key never leaves Android Keystore; only its
 * encoded public key and SHA-256 fingerprint are returned to protocol code.
 */
class AndroidInstallationIdentityStore(
    private val alias: String = DEFAULT_ALIAS,
) {
    init {
        require(alias.isNotBlank())
    }

    fun getOrCreateIdentity(): InstallationIdentity = synchronized(KEYSTORE_LOCK) {
        val keyStore = loadKeyStore()
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
        val certificate = keyStore.getCertificate(alias)
        if (privateKey == null && certificate == null) {
            generateKeyPair()
            return@synchronized readIdentity(loadKeyStore())
        }
        check(privateKey != null && certificate != null) {
            "Installation identity key is incomplete; recovery must be restarted"
        }
        identityFrom(certificate)
    }

    /** Signs a bounded recovery challenge so a peer can prove possession of this identity. */
    fun sign(message: ByteArray): ByteArray = synchronized(KEYSTORE_LOCK) {
        require(message.isNotEmpty() && message.size <= MAX_SIGNED_MESSAGE_BYTES) {
            "Identity proof message has an invalid length"
        }
        val keyStore = loadKeyStore()
        val certificate = keyStore.getCertificate(alias)
            ?: error("Installation identity is missing; recovery must be restarted")
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: error("Installation identity key is unavailable; recovery must be restarted")
        val identity = identityFrom(certificate)
        val proof = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(message)
            sign()
        }
        check(
            InstallationIdentityCrypto.verifySignature(
                identity.publicKeyBase64,
                identity.fingerprint,
                message,
                proof,
            ),
        ) { "Installation identity private/public key pair does not match" }
        proof
    }

    private fun generateKeyPair() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKeyPair()
    }

    private fun readIdentity(keyStore: KeyStore): InstallationIdentity {
        val certificate = keyStore.getCertificate(alias)
            ?: error("Android Keystore did not persist the installation identity")
        check(keyStore.getKey(alias, null) is PrivateKey) {
            "Android Keystore identity has no usable private key"
        }
        return identityFrom(certificate)
    }

    private fun identityFrom(certificate: Certificate): InstallationIdentity {
        val publicKey = certificate.publicKey.encoded
        return InstallationIdentity(
            fingerprint = InstallationIdentityCrypto.fingerprint(publicKey),
            publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey),
        )
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val DEFAULT_ALIAS = "transferkit.installation.identity.v1"
        private const val EC_CURVE = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val MAX_SIGNED_MESSAGE_BYTES = 64 * 1024
        private val KEYSTORE_LOCK = Any()

        fun verify(
            identity: InstallationIdentity,
            message: ByteArray,
            signature: ByteArray,
        ): Boolean = InstallationIdentityCrypto.verifySignature(
            identity.publicKeyBase64,
            identity.fingerprint,
            message,
            signature,
        )
    }
}

data class InstallationIdentity(
    val fingerprint: String,
    val publicKeyBase64: String,
) {
    override fun toString(): String = "InstallationIdentity(fingerprint=<redacted>, publicKey=<redacted>)"
}
