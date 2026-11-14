package com.transferkit.client.restore

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Rechecks the receiver-owned staging payload immediately before a category adapter can mutate
 * the destination. Reads with a fixed-size buffer so large media items are never loaded in memory.
 * Adapters still verify while writing and verify the destination after publication.
 */
object StagingPayloadVerifier {
    private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

    /** Returns null for a verified payload, otherwise the item-level failure to report. */
    fun verify(item: VerifiedRestoreItem): RestoreItemResult? {
        if (item.sizeBytes < 0L || !SHA256_PATTERN.matches(item.sha256)) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.FAILED, "INVALID_MANIFEST")
        }

        val payload = item.payloadFile
        if (!payload.isFile || payload.length() != item.sizeBytes) return integrityFailure(item)

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            var size = 0L
            payload.inputStream().buffered(BUFFER_BYTES).use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size = Math.addExact(size, count.toLong())
                    digest.update(buffer, 0, count)
                }
            }
            if (size != item.sizeBytes || payload.length() != item.sizeBytes ||
                digest.digest().toHex() != item.sha256
            ) {
                integrityFailure(item)
            } else {
                null
            }
        } catch (_: IOException) {
            RestoreItemResult(item.itemId, RestoreItemStatus.FAILED, "SOURCE_READ_FAILED")
        } catch (_: SecurityException) {
            RestoreItemResult(item.itemId, RestoreItemStatus.FAILED, "SOURCE_READ_FAILED")
        } catch (_: ArithmeticException) {
            integrityFailure(item)
        }
    }

    private fun integrityFailure(item: VerifiedRestoreItem) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private const val BUFFER_BYTES = 64 * 1024
}
