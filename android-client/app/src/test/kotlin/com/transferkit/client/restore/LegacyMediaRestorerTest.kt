package com.transferkit.client.restore

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMediaRestorerTest {
    private val fixture = Fixture()

    @After
    fun cleanFixture() {
        fixture.root.deleteRecursively()
    }

    @Test
    fun restoresVerifiedItemToManagedFolderAndRetryDoesNotDuplicateIt() {
        val item = fixture.item()
        val approvalGate = RestoreApprovalGate()
        val environment = FakeEnvironment(fixture.publicRoot)
        val restorer = LegacyMediaRestorer(environment, approvalGate, MEDIA_SCAN_TIMEOUT_MILLIS)

        assertEquals(RestoreItemStatus.RESTORED, restorer.restore(item, fixture.approval(approvalGate, item)).status)
        val target = fixture.destination(item)
        assertNotNull(target)
        assertArrayEquals(fixture.payloadBytes, target!!.readBytes())
        assertEquals(listOf(target), environment.scannedFiles)
        assertEquals(listOf("image/jpeg"), environment.scannedMimeTypes)
        assertTrue(target.name.startsWith("tk_"))
        assertTrue(target.name.endsWith("_synthetic.jpg"))
        assertTrue(target.parentFile!!.listFiles()!!.none { it.name.startsWith(".pending_") })

        assertEquals(RestoreItemStatus.RESTORED, restorer.restore(item, fixture.approval(approvalGate, item)).status)
        assertArrayEquals(fixture.payloadBytes, target.readBytes())
        assertEquals(2, environment.scannedFiles.size)
        assertEquals(1, target.parentFile!!.listFiles()!!.count { it.name.startsWith("tk_") })
    }

    @Test
    fun exactPermissionGateDoesNotConsumeApprovalBeforeUserGrantsAccess() {
        val item = fixture.item()
        val gate = RestoreApprovalGate()
        val approval = fixture.approval(gate, item)
        val environment = FakeEnvironment(fixture.publicRoot, hasPermission = false)
        val restorer = LegacyMediaRestorer(environment, gate, MEDIA_SCAN_TIMEOUT_MILLIS)

        val denied = restorer.restore(item, approval)
        assertEquals(RestoreItemStatus.USER_ACTION_REQUIRED, denied.status)
        assertEquals("WRITE_EXTERNAL_STORAGE_REQUIRED", denied.errorReason)
        assertFalse(fixture.publicRoot.exists())

        environment.hasPermission = true
        assertEquals(RestoreItemStatus.RESTORED, restorer.restore(item, approval).status)
    }

    @Test
    fun sourceBytesThatMismatchManifestNeverPublishPendingFile() {
        val item = fixture.item()
        val tampered = fixture.payloadBytes.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        item.payloadFile.writeBytes(tampered)
        val gate = RestoreApprovalGate()
        val environment = FakeEnvironment(fixture.publicRoot)
        val restorer = LegacyMediaRestorer(environment, gate, MEDIA_SCAN_TIMEOUT_MILLIS)

        val result = restorer.restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.FAILED_INTEGRITY, result.status)
        assertEquals("SHA256_MISMATCH", result.errorReason)
        assertFalse(fixture.destination(item)!!.exists())
        assertTrue(fixture.destination(item)!!.parentFile!!.listFiles()!!.isEmpty())
        assertTrue(environment.scannedFiles.isEmpty())
    }

    @Test
    fun existingManagedFilenameWithDifferentBytesIsNeverOverwritten() {
        val item = fixture.item()
        val target = fixture.destination(item)!!
        target.parentFile!!.mkdirs()
        val existingBytes = "user-created collision".toByteArray()
        target.writeBytes(existingBytes)
        val gate = RestoreApprovalGate()
        val environment = FakeEnvironment(fixture.publicRoot)
        val restorer = LegacyMediaRestorer(environment, gate, MEDIA_SCAN_TIMEOUT_MILLIS)

        val result = restorer.restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.FAILED_INTEGRITY, result.status)
        assertArrayEquals(existingBytes, target.readBytes())
        assertTrue(environment.scannedFiles.isEmpty())
    }

    @Test
    fun unavailableExternalStorageFailsWithoutCreatingDestination() {
        val item = fixture.item()
        val gate = RestoreApprovalGate()
        val environment = FakeEnvironment(fixture.publicRoot, isMounted = false)
        val restorer = LegacyMediaRestorer(environment, gate, MEDIA_SCAN_TIMEOUT_MILLIS)

        val result = restorer.restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.FAILED, result.status)
        assertEquals("TARGET_WRITE_FAILED", result.errorReason)
        assertFalse(fixture.publicRoot.exists())
    }

    @Test
    fun scanFailureIsReportedAndRetryIndexesTheAlreadyVerifiedFile() {
        val item = fixture.item()
        val gate = RestoreApprovalGate()
        val environment = FakeEnvironment(fixture.publicRoot, scanResult = false)
        val restorer = LegacyMediaRestorer(environment, gate, MEDIA_SCAN_TIMEOUT_MILLIS)

        val first = restorer.restore(item, fixture.approval(gate, item))
        assertEquals(RestoreItemStatus.FAILED, first.status)
        assertEquals("MEDIA_SCAN_FAILED", first.errorReason)
        val target = fixture.destination(item)!!
        assertArrayEquals(fixture.payloadBytes, target.readBytes())

        environment.scanResult = true
        val retry = restorer.restore(item, fixture.approval(gate, item))
        assertEquals(RestoreItemStatus.RESTORED, retry.status)
        assertEquals(2, environment.scannedFiles.size)
        assertEquals(1, target.parentFile!!.listFiles()!!.count { it.name.startsWith("tk_") })
    }

    @Test
    fun rejectsApi29AndUnsupportedCategoriesBeforeTouchingFilesystem() {
        val gate = RestoreApprovalGate()
        val api29 = LegacyMediaRestorer(
            FakeEnvironment(fixture.publicRoot, sdkInt = 29),
            gate,
            MEDIA_SCAN_TIMEOUT_MILLIS,
        )
        val item = fixture.item()
        assertEquals("API_UNAVAILABLE", api29.restore(item, fixture.approval(gate, item)).errorReason)

        val api28 = LegacyMediaRestorer(FakeEnvironment(fixture.publicRoot), gate, MEDIA_SCAN_TIMEOUT_MILLIS)
        val unsupportedItem = fixture.item(categoryId = "contacts")
        assertEquals("UNSUPPORTED", api28.restore(unsupportedItem, null).errorReason)
        assertFalse(fixture.publicRoot.exists())
    }

    private class FakeEnvironment(
        private val root: File,
        override val sdkInt: Int = 28,
        var hasPermission: Boolean = true,
        var isMounted: Boolean = true,
        var scanResult: Boolean = true,
    ) : LegacyMediaEnvironment {
        val scannedFiles = mutableListOf<File>()
        val scannedMimeTypes = mutableListOf<String>()

        override fun hasWriteExternalStoragePermission(): Boolean = hasPermission

        override fun isExternalStorageMounted(): Boolean = isMounted

        override fun externalStorageRoot(): File = root

        override fun scan(file: File, mimeType: String, timeoutMillis: Long): Boolean {
            assertEquals(MEDIA_SCAN_TIMEOUT_MILLIS, timeoutMillis)
            scannedFiles += file
            scannedMimeTypes += mimeType
            return scanResult
        }
    }

    private class Fixture {
        val root: File = Files.createTempDirectory("transferkit-legacy-restore-").toFile()
        val publicRoot: File = File(root, "public")
        val payloadBytes = "deterministic synthetic legacy restore payload".toByteArray()

        fun item(categoryId: String = "photos"): VerifiedRestoreItem {
            val payload = File(root, "payload-$categoryId.bin").apply { writeBytes(payloadBytes) }
            return VerifiedRestoreItem(
                transferId = "00000000-0000-4000-8000-000000000145",
                manifestSha256 = "a".repeat(64),
                categoryId = categoryId,
                itemId = "synthetic-$categoryId-item",
                sizeBytes = payloadBytes.size.toLong(),
                sha256 = sha256(payloadBytes),
                metadata = mapOf("path" to "camera/synthetic.jpg", "mime_type" to "image/jpeg"),
                payloadFile = payload,
            )
        }

        fun destination(item: VerifiedRestoreItem): File? {
            val policy = LegacyMediaDestination.from(item) ?: return null
            return File(File(publicRoot, policy.relativeDirectory), policy.displayName)
        }

        fun approval(gate: RestoreApprovalGate, item: VerifiedRestoreItem): RestoreApproval =
            gate.approveAfterUserConfirmation(
                item.transferId,
                item.manifestSha256,
                listOf(item),
                confirmedByUser = true,
            )!!
    }

    private companion object {
        const val MEDIA_SCAN_TIMEOUT_MILLIS = 1234L

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
