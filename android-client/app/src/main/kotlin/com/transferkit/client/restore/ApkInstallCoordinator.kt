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
    val manifestSha256: String,
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
class ApkInstallCoordinator internal constructor(
    private val installPlatform: ApkInstallPlatform,
    private val approvalGate: ApkInstallApprovalGate,
) {
    constructor(context: Context, approvalGate: ApkInstallApprovalGate) : this(
        AndroidApkInstallPlatform(context.applicationContext),
        approvalGate,
    )

    fun prepare(item: VerifiedRestoreItem, approval: ApkInstallApproval?): ApkPreparationResult {
        if (item.categoryId != "apks") return failed(item, "UNSUPPORTED")
        when (approvalGate.consumeForInstall(
            approval = approval,
            item = item,
            canRequestPackageInstalls = installPlatform.canRequestPackageInstalls(),
        )) {
            ApkInstallApprovalDecision.INSTALL_PERMISSION_REQUIRED ->
                return ApkPreparationResult.NeedsUserAction("INSTALL_CONFIRMATION_REQUIRED")
            ApkInstallApprovalDecision.NOT_APPROVED -> return failed(item, "USER_DECLINED")
            ApkInstallApprovalDecision.APPROVED -> Unit
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
            val createdSessionId = installPlatform.createSession(packageName, item.sizeBytes)
            sessionId = createdSessionId
            installPlatform.openSession(createdSessionId).use { session ->
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
                    manifestSha256 = item.manifestSha256,
                    itemId = item.itemId,
                    packageName = packageName,
                    expectedVersionCode = versionCode,
                    expectedSha256 = item.sha256,
                ),
            )
        } catch (_: SecurityException) {
            sessionId?.let { runCatching { installPlatform.openSession(it).use { session -> session.abandon() } } }
            if (!runCatching { installPlatform.canRequestPackageInstalls() }.getOrDefault(false)) {
                ApkPreparationResult.NeedsUserAction("INSTALL_CONFIRMATION_REQUIRED")
            } else {
                failed(item, "PERMISSION_DENIED")
            }
        } catch (_: IOException) {
            sessionId?.let { runCatching { installPlatform.openSession(it).use { session -> session.abandon() } } }
            failed(item, "TARGET_WRITE_FAILED")
        } catch (_: Exception) {
            sessionId?.let { runCatching { installPlatform.openSession(it).use { session -> session.abandon() } } }
            failed(item, "RESTORE_FAILED")
        }
    }

    /** Commits only a previously hash-verified ticket; Android may still show its own confirmation UI. */
    fun commit(ticket: ApkInstallTicket, statusReceiver: IntentSender) {
        installPlatform.openSession(ticket.sessionId).use { session -> session.commit(statusReceiver) }
    }

    /** Call from the app's explicit PackageInstaller status receiver. */
    fun complete(ticket: ApkInstallTicket, statusIntent: Intent): ApkInstallCallbackResult {
        val status = statusIntent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val userAction = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            statusIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        } else {
            null
        }
        return complete(ticket, status, userAction)
    }

    /** Status policy is kept separate from Intent parsing so success/failure can be regression tested on the JVM. */
    internal fun complete(
        ticket: ApkInstallTicket,
        status: Int,
        userActionIntent: Intent? = null,
    ): ApkInstallCallbackResult {
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            return ApkInstallCallbackResult(
                itemResult = null,
                userActionIntent = userActionIntent,
            )
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            return ApkInstallCallbackResult(
                itemResult = RestoreItemResult(ticket.itemId, RestoreItemStatus.FAILED, "RESTORE_FAILED"),
            )
        }
        return try {
            val installedVersion = installPlatform.installedVersionCode(ticket.packageName)
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
        runCatching { installPlatform.openSession(ticket.sessionId).use { session -> session.abandon() } }
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

        fun copyAndDigest(input: InputStream, output: OutputStream, session: ApkInstallSession): Pair<Long, String> {
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

/** Narrow PackageInstaller boundary used to test the APK integrity/approval pipeline on the JVM. */
internal interface ApkInstallPlatform {
    fun canRequestPackageInstalls(): Boolean
    fun createSession(packageName: String, sizeBytes: Long): Int
    fun openSession(sessionId: Int): ApkInstallSession
    fun installedVersionCode(packageName: String): Long?
}

internal interface ApkInstallSession : AutoCloseable {
    fun openWrite(name: String, offsetBytes: Long, lengthBytes: Long): OutputStream
    fun openRead(name: String): InputStream
    fun fsync(output: OutputStream)
    fun commit(statusReceiver: IntentSender)
    fun abandon()
    override fun close()
}

private class AndroidApkInstallPlatform(context: Context) : ApkInstallPlatform {
    private val packageManager = context.packageManager
    private val packageInstaller = packageManager.packageInstaller

    override fun canRequestPackageInstalls(): Boolean = packageManager.canRequestPackageInstalls()

    override fun createSession(packageName: String, sizeBytes: Long): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        params.setSize(sizeBytes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        return packageInstaller.createSession(params)
    }

    override fun openSession(sessionId: Int): ApkInstallSession = AndroidApkInstallSession(
        packageInstaller.openSession(sessionId),
    )

    override fun installedVersionCode(packageName: String): Long? = try {
        @Suppress("DEPRECATION")
        val installed = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            installed.versionCode.toLong()
        }
    } catch (_: Exception) {
        null
    }
}

private class AndroidApkInstallSession(
    private val session: PackageInstaller.Session,
) : ApkInstallSession {
    override fun openWrite(name: String, offsetBytes: Long, lengthBytes: Long): OutputStream =
        session.openWrite(name, offsetBytes, lengthBytes)

    override fun openRead(name: String): InputStream = session.openRead(name)
    override fun fsync(output: OutputStream) = session.fsync(output)
    override fun commit(statusReceiver: IntentSender) = session.commit(statusReceiver)
    override fun abandon() = session.abandon()
    override fun close() = session.close()
}
