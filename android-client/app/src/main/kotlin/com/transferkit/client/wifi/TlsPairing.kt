package com.transferkit.client.wifi

import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLContext

/** One in-memory pairing offer. Its default string representation never reveals the QR token. */
class PairingOffer internal constructor(
    val host: String,
    val port: Int,
    val certificateSha256: String,
    val expiresAtElapsedRealtime: Long,
    /** Render this value into the QR only; do not log, persist, or include it in analytics. */
    val tokenForQr: String,
    internal val tokenVerifier: OneTimePairingToken,
) {
    override fun toString(): String =
        "PairingOffer(host=$host, port=$port, certificateSha256=$certificateSha256, " +
            "expiresAtElapsedRealtime=$expiresAtElapsedRealtime, tokenForQr=<redacted>)"
}

/** Short-lived, in-memory token for binding a TLS peer to the user-approved QR scan. */
class OneTimePairingToken internal constructor(
    tokenBytes: ByteArray,
    private val expiresAtElapsedRealtime: Long,
) {
    private var secret: ByteArray? = tokenBytes.copyOf()
    private var consumed = false

    @Synchronized
    fun consume(candidate: String, nowElapsedRealtime: Long = SystemClock.elapsedRealtime()): Boolean {
        val expected = secret ?: return false
        if (consumed || nowElapsedRealtime >= expiresAtElapsedRealtime) {
            consumed = true
            expected.fill(0)
            secret = null
            return false
        }
        val supplied = try {
            Base64.decode(candidate, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val matches = MessageDigest.isEqual(expected, supplied)
        supplied.fill(0)
        if (matches) {
            consumed = true
            expected.fill(0)
            secret = null
        }
        return matches
    }
}

object TlsPairing {
    private const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
    private const val MIN_TTL_MILLIS = 30 * 1000L
    private const val MAX_TTL_MILLIS = 10 * 60 * 1000L
    private val secureRandom = SecureRandom()
    private val fingerprintPattern = Regex("^[a-f0-9]{64}$")

    /** Runtime provider probe only; it does not weaken trust validation or create a socket. */
    fun isTls13Available(): Boolean = runCatching {
        SSLContext.getInstance("TLSv1.3").init(null, null, secureRandom)
        true
    }.getOrDefault(false)

    fun createOffer(
        host: String,
        port: Int,
        certificateSha256: String,
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
    ): PairingOffer {
        require(host.isNotBlank() && host.length <= 255 && '\n' !in host && '\r' !in host)
        require(port in 1..65535)
        require(fingerprintPattern.matches(certificateSha256))
        require(ttlMillis in MIN_TTL_MILLIS..MAX_TTL_MILLIS)

        val tokenBytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val expiresAt = SystemClock.elapsedRealtime() + ttlMillis
        val tokenText = Base64.encodeToString(
            tokenBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return PairingOffer(
            host = host,
            port = port,
            certificateSha256 = certificateSha256,
            expiresAtElapsedRealtime = expiresAt,
            tokenForQr = tokenText,
            tokenVerifier = OneTimePairingToken(tokenBytes, expiresAt),
        ).also { tokenBytes.fill(0) }
    }

    private const val TOKEN_BYTES = 32
}
