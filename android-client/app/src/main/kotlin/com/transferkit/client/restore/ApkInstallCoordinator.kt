package com.transferkit.client.restore

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

sealed class ApkPreparationResult {
    data class Ready(val ticket: ApkInstallTicket) : ApkPreparationResult()
    data class NeedsUserAction(val reasonCode: String) : ApkPreparationResult()
    data class Failed(val result: RestoreItemResult) : ApkPreparationResult()
}

class ApkInstallTicket internal constructor(
    val sessionId: Int,
    val transferId: String,
    val itemId: String,
    val packageName: String,
    val expectedVersionCode: Long,
    val expectedSha256: String,
)

data class ApkInstallCallbackResult(
    val itemResult: RestoreItemResult?,
    val userActionIntent: Intent? = null,
)

/**
 * APK-only install primitive. The caller must obtain a distinct confirmation for every APK,
 * provide a status IntentSender, and wait for [complete] before recording RESTORED.
 */
class ApkInstallCoordinator(
    context: Context,
    private val approvalGate: ApkInstallApprovalGate,
) {
    private val appContext = context.applicationContext
    private val packageInstaller = appContext.packageManager.packageInstaller

    fun prepare(item: VerifiedRestoreItem, approval: ApkInstallApproval?): ApkPreparationResult {
        if (item.categoryId != "apks") return failed(item, "UNSUPPORTED")
        if (!approvalGate.consume(approval, item)) return failed(item, "USER_DECLINED")
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            return ApkPreparationResult.NeedsUserAction("INSTALL_CONFIRMATION_REQUIRED")
        }
        val packageName = item.metadata["package_name"] as? String
        val versionCode = (item.metadata["version_code"] as? Number)?.toLong()
        if (packageName == null || !PACKAGE_NAME.matches(packageName) || versionCode == null || versionCode < 0L) {
            return failed(item, "INVALID_MANIFEST")
        }
        if (!item.payloadFile.isFile || item.sizeBytes < 0L || !SHA256.matches(item.sha256)) {
            return failed(item, "INVALID_MANIFEST")
        }
        if (item.payloadFile.length() != item.sizeBytes) return failed(item, "SHA256_MISMATCH", integrity = true)

        var sessionId: Int? = null
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(packageName)
            params.setSize(item.sizeBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val createdSessionId = packageInstaller.createSession(params)
            sessionId = createdSessionId
            packageInstaller.openSession(createdSessionId).use { session ->
                val output = session.openWrite("base.apk", 0L, item.sizeBytes)
                val copied = item.payloadFile.inputStream().use { input ->
                    output.use { target -> copyAndDigest(input, target, session) }
                }
                if (copied.first != item.sizeBytes || copied.second != item.sha256) {
                    session.abandon()
                    return failed(item, "SHA256_MISMATCH", integrity = true)
                }
                val stored = session.openRead("base.apk").use(::digest)
                if (stored.first != item.sizeBytes || stored.second != item.sha256) {
                    session.abandon()
                    return failed(item, "SHA256_MISMATCH", integrity = true)
                }
            }
            ApkPreparationResult.Ready(
                ApkInstallTicket(
                    sessionId = createdSessionId,
                    transferId = item.transferId,
                    itemId = item.itemId,
                    packageName = packageName,
                    expectedVersionCode = versionCode,
                    expectedSha256 = item.sha256,
                ),
            )
        } catch (_: SecurityException) {
            sessionId?.let { runCatching { packageInstaller.openSession(it).use { session -> session.abandon() } } }
            ApkPreparationResult.NeedsUserAction("INSTALL_CONFIRMATION_REQUIRED")
        } catch (_: IOException) {
            sessionId?.let { runCatching { packageInstaller.openSession(it).use { session -> session.abandon() } } }
            failed(item, "TARGET_WRITE_FAILED")
        } catch (_: Exception) {
            sessionId?.let { runCatching { packageInstaller.openSession(it).use { session -> session.abandon() } } }
            failed(item, "RESTORE_FAILED")
        }
    }

    /** Commits only a previously hash-verified ticket; Android may still show its own confirmation UI. */
    fun commit(ticket: ApkInstallTicket, statusReceiver: IntentSender) {
        packageInstaller.openSession(ticket.sessionId).use { session -> session.commit(statusReceiver) }
    }

    /** Call from the app's explicit PackageInstaller status receiver. */
    fun complete(ticket: ApkInstallTicket, statusIntent: Intent): ApkInstallCallbackResult {
        val status = statusIntent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val userAction = statusIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            return ApkInstallCallbackResult(
                itemResult = null,
                userActionIntent = userAction,
            )
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            return ApkInstallCallbackResult(
                itemResult = RestoreItemResult(ticket.itemId, RestoreItemStatus.FAILED, "RESTORE_FAILED"),
            )
        }
        return try {
            @Suppress("DEPRECATION")
            val installed = appContext.packageManager.getPackageInfo(ticket.packageName, 0)
            val installedVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installed.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                installed.versionCode.toLong()
            }
            if (installedVersion != ticket.expectedVersionCode) {
                ApkInstallCallbackResult(
                    RestoreItemResult(ticket.itemId, RestoreItemStatus.FAILED, "RESTORE_FAILED"),
                )
            } else {
                ApkInstallCallbackResult(
                    RestoreItemResult(ticket.itemId, RestoreItemStatus.RESTORED),
                )
            }
        } catch (_: Exception) {
            ApkInstallCallbackResult(
                RestoreItemResult(ticket.itemId, RestoreItemStatus.FAILED, "RESTORE_FAILED"),
            )
        }
    }

    fun abandon(ticket: ApkInstallTicket) {
        runCatching { packageInstaller.openSession(ticket.sessionId).use { session -> session.abandon() } }
    }

    private fun failed(item: VerifiedRestoreItem, reason: String, integrity: Boolean = false) =
        ApkPreparationResult.Failed(
            RestoreItemResult(
                itemId = item.itemId,
                status = if (integrity) RestoreItemStatus.FAILED_INTEGRITY else RestoreItemStatus.FAILED,
                errorReason = reason,
            ),
        )

    private companion object {
        val PACKAGE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
        val SHA256 = Regex("^[a-f0-9]{64}$")

        fun copyAndDigest(input: InputStream, output: OutputStream, session: PackageInstaller.Session): Pair<Long, String> {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var size = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val value = input.read()
                    if (value < 0) break
                    output.write(value)
                    digest.update(value.toByte())
                    size = Math.addExact(size, 1L)
                } else {
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    size = Math.addExact(size, count.toLong())
                }
            }
            output.flush()
            session.fsync(output)
            return size to digest.digest().toHex()
        }

        fun digest(input: InputStream): Pair<Long, String> {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var size = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val value = input.read()
                    if (value < 0) break
                    digest.update(value.toByte())
                    size = Math.addExact(size, 1L)
                } else {
                    digest.update(buffer, 0, count)
                    size = Math.addExact(size, count.toLong())
                }
            }
            return size to digest.digest().toHex()
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
