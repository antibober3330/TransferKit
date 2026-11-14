package com.transferkit.client.restore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ApkInstallStatusReceiverBindingTest {
    private val ticket = ApkInstallTicket(
        sessionId = 41,
        transferId = "transfer-1",
        manifestSha256 = "a".repeat(64),
        itemId = "shared-item-id",
        packageName = "org.example.safe",
        expectedVersionCode = 12L,
        expectedSha256 = "b".repeat(64),
    )

    private val exactApk = item(
        transferId = ticket.transferId,
        manifestSha256 = ticket.manifestSha256,
        categoryId = "apks",
        packageName = ticket.packageName,
        versionCode = ticket.expectedVersionCode,
    )

    @Test
    fun exactReceiptBackedApkMatchesAllTicketIdentityFields() {
        assertSame(exactApk, receiptBoundApkItem(ticket, listOf(exactApk)))
    }

    @Test
    fun sameIdManifestAndDigestFromAnotherCategoryIsNeverSelected() {
        val mediaCollision = exactApk.copy(categoryId = "photos")

        assertNull(receiptBoundApkItem(ticket, listOf(mediaCollision)))
    }

    @Test
    fun differentTransferOrManifestIdentityIsRejected() {
        val wrongTransfer = exactApk.copy(transferId = "transfer-2")
        val wrongManifest = exactApk.copy(manifestSha256 = "c".repeat(64))

        assertNull(receiptBoundApkItem(ticket, listOf(wrongTransfer, wrongManifest)))
    }

    @Test
    fun packageNameAndVersionMustMatchVerifiedManifestMetadata() {
        val wrongPackage = exactApk.copy(metadata = exactApk.metadata + ("package_name" to "org.example.other"))
        val wrongVersion = exactApk.copy(metadata = exactApk.metadata + ("version_code" to 11L))

        assertNull(receiptBoundApkItem(ticket, listOf(wrongPackage, wrongVersion)))
    }

    @Test
    fun missingOrNonNumericVersionFailsClosed() {
        val missingVersion = exactApk.copy(metadata = exactApk.metadata - "version_code")
        val textualVersion = exactApk.copy(metadata = exactApk.metadata + ("version_code" to "12"))

        assertNull(receiptBoundApkItem(ticket, listOf(missingVersion, textualVersion)))
    }

    private fun item(
        transferId: String,
        manifestSha256: String,
        categoryId: String,
        packageName: String,
        versionCode: Long,
    ) = VerifiedRestoreItem(
        transferId = transferId,
        manifestSha256 = manifestSha256,
        categoryId = categoryId,
        itemId = ticket.itemId,
        sizeBytes = 10,
        sha256 = ticket.expectedSha256,
        metadata = mapOf("package_name" to packageName, "version_code" to versionCode),
        payloadFile = File("synthetic-receipt-backed-apk"),
    )
}
