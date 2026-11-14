package com.transferkit.client.restore

import android.content.IntentSender
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallCoordinatorTest {
    private val tempDirectory = Files.createTempDirectory("transferkit-apk-install-test").toFile()
    private val payload = "synthetic-test-apk-payload".toByteArray()
    private val payloadFile = File(tempDirectory, "synthetic.apk").apply { writeBytes(payload) }
    private val item = VerifiedRestoreItem(
        transferId = "transfer-test",
        manifestSha256 = "a".repeat(64),
        categoryId = "apks",
        itemId = "apk-test",
        sizeBytes = payload.size.toLong(),
        sha256 = payload.sha256(),
        metadata = mapOf("package_name" to "org.example.synthetic", "version_code" to 7L),
        payloadFile = payloadFile,
    )

    @After
    fun cleanUp() {
        tempDirectory.deleteRecursively()
    }

    @Test
    fun prepareCopiesExactVerifiedBytesAndFsyncsSessionBeforeReturningTicket() {
        val platform = FakeApkInstallPlatform()
        val (coordinator, approval) = approvedCoordinator(platform)

        val result = coordinator.prepare(item, approval)

        assertTrue(result is ApkPreparationResult.Ready)
        assertArrayEquals(payload, platform.storedBytes)
        assertEquals(1, platform.createdSessionCount)
        assertEquals(1, platform.fsyncCount)
        assertFalse(platform.abandoned)
    }

    @Test
    fun storedReadbackMismatchFailsIntegrityAndAbandonsSession() {
        val platform = FakeApkInstallPlatform(corruptReadback = true)
        val (coordinator, approval) = approvedCoordinator(platform)

        val result = coordinator.prepare(item, approval)

        assertFailure(result, RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")
        assertTrue(platform.abandoned)
    }

    @Test
    fun missingUnknownSourcePermissionKeepsApprovalForRetry() {
        val platform = FakeApkInstallPlatform(canInstall = false)
        val (coordinator, approval) = approvedCoordinator(platform)

        val first = coordinator.prepare(item, approval)
        assertTrue(first is ApkPreparationResult.NeedsUserAction)
        assertEquals(0, platform.createdSessionCount)

        platform.canInstall = true
        val retried = coordinator.prepare(item, approval)
        assertTrue(retried is ApkPreparationResult.Ready)
        assertEquals(1, platform.createdSessionCount)
    }

    @Test
    fun absentOrMismatchedConsentNeverCreatesPackageInstallerSession() {
        val platform = FakeApkInstallPlatform()
        val coordinator = ApkInstallCoordinator(platform, ApkInstallApprovalGate())
        assertFailure(coordinator.prepare(item, null), RestoreItemStatus.FAILED, "USER_DECLINED")

        val approval = ApkInstallApprovalGate().approveAfterUserConfirmation(item, confirmedByUser = true)!!
        assertFailure(
            coordinator.prepare(item.copy(itemId = "different-item"), approval),
            RestoreItemStatus.FAILED,
            "USER_DECLINED",
        )
        assertEquals(0, platform.createdSessionCount)
    }

    @Test
    fun manifestSizeMismatchFailsIntegrityBeforeCreatingSession() {
        val platform = FakeApkInstallPlatform()
        val mismatched = item.copy(sizeBytes = item.sizeBytes + 1)
        val gate = ApkInstallApprovalGate()
        val approval = gate.approveAfterUserConfirmation(mismatched, confirmedByUser = true)!!
        val coordinator = ApkInstallCoordinator(platform, gate)

        assertFailure(coordinator.prepare(mismatched, approval), RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")
        assertEquals(0, platform.createdSessionCount)
    }

    @Test
    fun packageNameIsValidatedBeforeCreatingSession() {
        val platform = FakeApkInstallPlatform()
        val invalidItem = item.copy(metadata = item.metadata + ("package_name" to "../not-a-package"))
        val gate = ApkInstallApprovalGate()
        val approval = gate.approveAfterUserConfirmation(invalidItem, confirmedByUser = true)!!
        val coordinator = ApkInstallCoordinator(platform, gate)

        assertFailure(coordinator.prepare(invalidItem, approval), RestoreItemStatus.FAILED, "INVALID_MANIFEST")
        assertEquals(0, platform.createdSessionCount)
    }

    @Test
    fun packageInstallerWriteFailureIsReportedAndSessionIsAbandoned() {
        val platform = FakeApkInstallPlatform(failOpenWrite = true)
        val (coordinator, approval) = approvedCoordinator(platform)

        assertFailure(coordinator.prepare(item, approval), RestoreItemStatus.FAILED, "TARGET_WRITE_FAILED")
        assertTrue(platform.abandoned)
    }

    @Test
    fun changedPayloadBytesFailIntegrityAndAbandonSession() {
        val platform = FakeApkInstallPlatform()
        val (coordinator, approval) = approvedCoordinator(platform)
        payloadFile.writeBytes(payload.copyOf().apply { this[0] = (this[0].toInt() xor 0xff).toByte() })

        assertFailure(coordinator.prepare(item, approval), RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")
        assertTrue(platform.abandoned)
    }

    @Test
    fun packageInstallerSessionCreationSecurityFailureIsNotTreatedAsPermissionPromptWhenPermissionExists() {
        val platform = FakeApkInstallPlatform(failCreateSession = true)
        val (coordinator, approval) = approvedCoordinator(platform)

        assertFailure(coordinator.prepare(item, approval), RestoreItemStatus.FAILED, "PERMISSION_DENIED")
        assertEquals(1, platform.createdSessionCount)
        assertFalse(platform.abandoned)
    }

    @Test
    fun successfulInstallerCallbackRequiresMatchingInstalledVersion() {
        val platform = FakeApkInstallPlatform(installedVersion = 7L)
        val (coordinator, ticket) = preparedTicket(platform)

        val result = coordinator.complete(ticket, android.content.pm.PackageInstaller.STATUS_SUCCESS)

        assertEquals(RestoreItemStatus.RESTORED, result.itemResult?.status)
        assertEquals("apk-test", result.itemResult?.itemId)
    }

    @Test
    fun successfulInstallerCallbackWithWrongInstalledVersionFailsClosed() {
        val platform = FakeApkInstallPlatform(installedVersion = 6L)
        val (coordinator, ticket) = preparedTicket(platform)

        val result = coordinator.complete(ticket, android.content.pm.PackageInstaller.STATUS_SUCCESS)

        assertEquals(RestoreItemStatus.FAILED, result.itemResult?.status)
        assertEquals("RESTORE_FAILED", result.itemResult?.errorReason)
    }

    @Test
    fun installerFailureNeverReportsRestored() {
        val platform = FakeApkInstallPlatform(installedVersion = 7L)
        val (coordinator, ticket) = preparedTicket(platform)

        val result = coordinator.complete(ticket, android.content.pm.PackageInstaller.STATUS_FAILURE)

        assertEquals(RestoreItemStatus.FAILED, result.itemResult?.status)
        assertEquals("RESTORE_FAILED", result.itemResult?.errorReason)
        assertEquals(0, platform.installedVersionLookups)
    }

    @Test
    fun pendingUserActionDoesNotReportInstallCompletion() {
        val platform = FakeApkInstallPlatform(installedVersion = 7L)
        val (coordinator, ticket) = preparedTicket(platform)

        val result = coordinator.complete(ticket, android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION)

        assertEquals(null, result.itemResult)
        assertEquals(null, result.userActionIntent)
        assertEquals(0, platform.installedVersionLookups)
    }

    @Test
    fun unsupportedCategoryIsRejectedBeforePermissionCheck() {
        val platform = FakeApkInstallPlatform()
        val coordinator = ApkInstallCoordinator(platform, ApkInstallApprovalGate())

        assertFailure(coordinator.prepare(item.copy(categoryId = "photos"), null), RestoreItemStatus.FAILED, "UNSUPPORTED")
        assertEquals(0, platform.permissionChecks)
        assertEquals(0, platform.createdSessionCount)
    }

    private fun approvedCoordinator(platform: FakeApkInstallPlatform): Pair<ApkInstallCoordinator, ApkInstallApproval> {
        val gate = ApkInstallApprovalGate()
        val approval = gate.approveAfterUserConfirmation(item, confirmedByUser = true)!!
        return ApkInstallCoordinator(platform, gate) to approval
    }

    private fun preparedTicket(platform: FakeApkInstallPlatform): Pair<ApkInstallCoordinator, ApkInstallTicket> {
        val (coordinator, approval) = approvedCoordinator(platform)
        val result = coordinator.prepare(item, approval)
        assertTrue("Expected a prepared APK install ticket", result is ApkPreparationResult.Ready)
        return coordinator to (result as ApkPreparationResult.Ready).ticket
    }

    private fun assertFailure(result: ApkPreparationResult, status: RestoreItemStatus, reason: String) {
        assertTrue("Expected Failed but got $result", result is ApkPreparationResult.Failed)
        val failure = (result as ApkPreparationResult.Failed).result
        assertEquals(status, failure.status)
        assertEquals(reason, failure.errorReason)
    }

    private class FakeApkInstallPlatform(
        var canInstall: Boolean = true,
        private val corruptReadback: Boolean = false,
        private val failOpenWrite: Boolean = false,
        private val failCreateSession: Boolean = false,
        private val installedVersion: Long? = null,
    ) : ApkInstallPlatform {
        private val sessionId = 23
        var permissionChecks = 0
            private set
        var createdSessionCount = 0
            private set
        var fsyncCount = 0
            private set
        var abandoned = false
            private set
        var storedBytes = byteArrayOf()
            private set
        var installedVersionLookups = 0
            private set

        override fun canRequestPackageInstalls(): Boolean {
            permissionChecks++
            return canInstall
        }

        override fun createSession(packageName: String, sizeBytes: Long): Int {
            createdSessionCount++
            if (failCreateSession) throw SecurityException("synthetic session creation failure")
            return sessionId
        }

        override fun openSession(sessionId: Int): ApkInstallSession {
            check(sessionId == this.sessionId)
            return object : ApkInstallSession {
                override fun openWrite(name: String, offsetBytes: Long, lengthBytes: Long): OutputStream {
                    if (failOpenWrite) throw IOException("synthetic session write failure")
                    return object : ByteArrayOutputStream() {
                        override fun close() {
                            storedBytes = toByteArray()
                            super.close()
                        }
                    }
                }

                override fun openRead(name: String): InputStream {
                    val data = storedBytes.copyOf()
                    if (corruptReadback && data.isNotEmpty()) data[0] = (data[0].toInt() xor 0xff).toByte()
                    return ByteArrayInputStream(data)
                }

                override fun fsync(output: OutputStream) {
                    fsyncCount++
                }

                override fun commit(statusReceiver: IntentSender) = Unit
                override fun abandon() { abandoned = true }
                override fun close() = Unit
            }
        }

        override fun installedVersionCode(packageName: String): Long? {
            installedVersionLookups++
            return installedVersion
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
