package com.transferkit.client.restore

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingRestorePipelineTest {
    private val root: File = Files.createTempDirectory("transferkit-staging-pipeline-").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun integrityFailureDoesNotCallAdapterOrReleaseStaging() {
        val item = item("expected".toByteArray()).also { it.payloadFile.writeBytes("corrupt!".toByteArray()) }
        var adapterCalled = false
        var releaseCalled = false

        val outcome = StagingRestorePipeline.restoreOne(
            item,
            restore = { adapterCalled = true; restored(it) },
            releaseAfterPostVerification = { releaseCalled = true; true },
        )

        assertEquals(RestoreItemStatus.FAILED_INTEGRITY, outcome.result.status)
        assertEquals("SHA256_MISMATCH", outcome.result.errorReason)
        assertFalse(adapterCalled)
        assertFalse(releaseCalled)
        assertFalse(outcome.stagingCleanupPending)
    }

    @Test
    fun releasesStagingOnlyAfterAdapterReportsPostVerifiedRestore() {
        val item = item("verified".toByteArray()).also { it.payloadFile.writeBytes("verified".toByteArray()) }
        val events = mutableListOf<String>()

        val outcome = StagingRestorePipeline.restoreOne(
            item,
            restore = { events += "restore"; restored(it) },
            releaseAfterPostVerification = { events += "release"; true },
        )

        assertEquals(RestoreItemStatus.RESTORED, outcome.result.status)
        assertEquals(listOf("restore", "release"), events)
        assertFalse(outcome.stagingCleanupPending)
    }

    @Test
    fun failedRestoreKeepsStagingAndDoesNotRequestRelease() {
        val item = item("verified".toByteArray()).also { it.payloadFile.writeBytes("verified".toByteArray()) }
        var releaseCalled = false

        val outcome = StagingRestorePipeline.restoreOne(
            item,
            restore = { RestoreItemResult(it.itemId, RestoreItemStatus.FAILED, "TARGET_WRITE_FAILED") },
            releaseAfterPostVerification = { releaseCalled = true; true },
        )

        assertEquals(RestoreItemStatus.FAILED, outcome.result.status)
        assertFalse(releaseCalled)
        assertFalse(outcome.stagingCleanupPending)
        assertTrue(item.payloadFile.exists())
    }

    @Test
    fun restoreExceptionBecomesFailureAndKeepsStaging() {
        val item = item("verified".toByteArray()).also { it.payloadFile.writeBytes("verified".toByteArray()) }
        var releaseCalled = false

        val outcome = StagingRestorePipeline.restoreOne(
            item,
            restore = { error("synthetic adapter failure") },
            releaseAfterPostVerification = { releaseCalled = true; true },
        )

        assertEquals(RestoreItemStatus.FAILED, outcome.result.status)
        assertEquals("RESTORE_FAILED", outcome.result.errorReason)
        assertFalse(releaseCalled)
        assertTrue(item.payloadFile.exists())
    }

    @Test
    fun cleanupFailureDoesNotUndoVerifiedRestoreButIsReported() {
        val item = item("verified".toByteArray()).also { it.payloadFile.writeBytes("verified".toByteArray()) }

        val outcome = StagingRestorePipeline.restoreOne(
            item,
            restore = ::restored,
            releaseAfterPostVerification = { false },
        )

        assertEquals(RestoreItemStatus.RESTORED, outcome.result.status)
        assertTrue(outcome.stagingCleanupPending)
    }

    private fun item(bytes: ByteArray) = VerifiedRestoreItem(
        transferId = "transfer-1",
        manifestSha256 = "b".repeat(64),
        categoryId = "documents",
        itemId = "item-1",
        sizeBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
        metadata = emptyMap(),
        payloadFile = File(root, "payload-${System.nanoTime()}.bin"),
    )

    private fun restored(item: VerifiedRestoreItem) =
        RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
