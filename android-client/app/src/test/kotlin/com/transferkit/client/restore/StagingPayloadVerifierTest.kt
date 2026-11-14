package com.transferkit.client.restore

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StagingPayloadVerifierTest {
    private val root: File = Files.createTempDirectory("transferkit-staging-verifier-").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun acceptsCorrectPayloadLargerThanReadBuffer() {
        val bytes = ByteArray(192 * 1024 + 17) { (it % 251).toByte() }
        val item = item(bytes)
        item.payloadFile.writeBytes(bytes)

        assertNull(StagingPayloadVerifier.verify(item))
    }

    @Test
    fun rejectsSameSizePayloadWithDifferentDigestAsIntegrityFailure() {
        val expected = "source bytes".toByteArray()
        val item = item(expected)
        item.payloadFile.writeBytes("target bytes".toByteArray())

        assertResult(StagingPayloadVerifier.verify(item), RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")
    }

    @Test
    fun rejectsMissingAndWrongSizePayloadAsIntegrityFailure() {
        val expected = "checkpoint payload".toByteArray()
        val missing = item(expected, File(root, "missing.bin"))
        assertResult(StagingPayloadVerifier.verify(missing), RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")

        val truncatedFile = File(root, "truncated.bin").apply { writeBytes(expected.copyOf(expected.size - 1)) }
        val truncated = item(expected, truncatedFile)
        assertResult(StagingPayloadVerifier.verify(truncated), RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")
    }

    @Test
    fun rejectsInvalidManifestDigestBeforeReadingPayload() {
        val file = File(root, "payload.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val item = item(byteArrayOf(1, 2, 3), file).copy(sha256 = "A".repeat(64))

        assertResult(StagingPayloadVerifier.verify(item), RestoreItemStatus.FAILED, "INVALID_MANIFEST")
    }

    @Test
    fun acceptsEmptyPayloadWithCanonicalEmptyDigest() {
        val item = item(byteArrayOf())
        item.payloadFile.writeBytes(byteArrayOf())

        assertNull(StagingPayloadVerifier.verify(item))
    }

    private fun item(bytes: ByteArray, file: File = File(root, "payload-${System.nanoTime()}.bin")) =
        VerifiedRestoreItem(
            transferId = "transfer-1",
            manifestSha256 = "b".repeat(64),
            categoryId = "documents",
            itemId = "item-1",
            sizeBytes = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
            metadata = emptyMap(),
            payloadFile = file,
        )

    private fun assertResult(result: RestoreItemResult?, status: RestoreItemStatus, reason: String) {
        requireNotNull(result)
        assertEquals(status, result.status)
        assertEquals(reason, result.errorReason)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
