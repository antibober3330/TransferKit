package com.transferkit.client.restore

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.transferkit.client.receiver.AndroidTransferReceiver
import java.util.concurrent.Executors

/** Receives the system installer result and records restore only after package/version checks. */
class ApkInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ticket = intent.toTicket() ?: return
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) ==
            PackageInstaller.STATUS_PENDING_USER_ACTION
        ) {
            val userAction = intent.userActionIntent()
            if (userAction == null) {
                notifyResult(context, "Android не надав екран підтвердження встановлення.")
            } else {
                userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(userAction) }
                    .onFailure { notifyResult(context, "Не вдалося відкрити підтвердження Android.") }
            }
            return
        }

        val appContext = context.applicationContext
        val pending = goAsync()
        executor.execute {
            try {
                val result = ApkInstallCoordinator(appContext, ApkInstallApprovalGate()).complete(ticket, intent)
                val itemResult = result.itemResult
                val message = if (itemResult?.status == RestoreItemStatus.RESTORED) {
                    val recorded = runCatching {
                        AndroidTransferReceiver(appContext).use { receiver ->
                            val item = receiver.verifiedRestoreItems(ticket.transferId).firstOrNull {
                                it.itemId == ticket.itemId &&
                                    it.manifestSha256 == ticket.manifestSha256 &&
                                    it.sha256 == ticket.expectedSha256
                            }
                            item != null && receiver.recordPostVerifiedRestore(item)
                        }
                    }.getOrDefault(false)
                    if (recorded) "APK встановлено, версію перевірено; тимчасовий payload прибрано."
                    else "APK встановлено, але TransferKit не підтвердив receipt; тимчасовий payload збережено."
                } else {
                    "Android не встановив APK (${itemResult?.errorReason ?: "RESTORE_FAILED"}); дані передачі збережені."
                }
                notifyResult(appContext, message)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESULT = "com.transferkit.client.action.APK_INSTALL_RESULT"
        const val EXTRA_RESULT_TEXT = "com.transferkit.client.extra.APK_INSTALL_RESULT_TEXT"
        private const val RESULT_PREFERENCES = "apk_install_result"
        private const val RESULT_KEY = "latest_result"
        private val executor = Executors.newSingleThreadExecutor()

        fun takePendingResult(context: Context): String? {
            val preferences = context.applicationContext.getSharedPreferences(RESULT_PREFERENCES, Context.MODE_PRIVATE)
            val message = preferences.getString(RESULT_KEY, null)
            if (message != null) preferences.edit().remove(RESULT_KEY).apply()
            return message
        }

        fun createStatusPendingIntent(context: Context, ticket: ApkInstallTicket): PendingIntent {
            val callback = Intent(context, ApkInstallStatusReceiver::class.java).apply {
                action = ACTION_RESULT
                data = Uri.Builder()
                    .scheme("transferkit")
                    .authority("apk-install-status")
                    .appendPath(ticket.transferId)
                    .appendPath(ticket.itemId)
                    .build()
                putExtra(EXTRA_SESSION_ID, ticket.sessionId)
                putExtra(EXTRA_TRANSFER_ID, ticket.transferId)
                putExtra(EXTRA_MANIFEST_SHA256, ticket.manifestSha256)
                putExtra(EXTRA_ITEM_ID, ticket.itemId)
                putExtra(EXTRA_PACKAGE_NAME, ticket.packageName)
                putExtra(EXTRA_VERSION_CODE, ticket.expectedVersionCode)
                putExtra(EXTRA_SHA256, ticket.expectedSha256)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, ticket.sessionId, callback, flags)
        }

        private const val EXTRA_SESSION_ID = "com.transferkit.client.extra.APK_SESSION_ID"
        private const val EXTRA_TRANSFER_ID = "com.transferkit.client.extra.APK_TRANSFER_ID"
        private const val EXTRA_MANIFEST_SHA256 = "com.transferkit.client.extra.APK_MANIFEST_SHA256"
        private const val EXTRA_ITEM_ID = "com.transferkit.client.extra.APK_ITEM_ID"
        private const val EXTRA_PACKAGE_NAME = "com.transferkit.client.extra.APK_PACKAGE_NAME"
        private const val EXTRA_VERSION_CODE = "com.transferkit.client.extra.APK_VERSION_CODE"
        private const val EXTRA_SHA256 = "com.transferkit.client.extra.APK_SHA256"

        private fun Intent.toTicket(): ApkInstallTicket? {
            val transferId = getStringExtra(EXTRA_TRANSFER_ID) ?: return null
            val manifestSha256 = getStringExtra(EXTRA_MANIFEST_SHA256) ?: return null
            val itemId = getStringExtra(EXTRA_ITEM_ID) ?: return null
            val packageName = getStringExtra(EXTRA_PACKAGE_NAME) ?: return null
            val sha256 = getStringExtra(EXTRA_SHA256) ?: return null
            val versionCode = getLongExtra(EXTRA_VERSION_CODE, -1L)
            val sessionId = getIntExtra(EXTRA_SESSION_ID, -1)
            if (sessionId < 0 || versionCode < 0L) return null
            return ApkInstallTicket(sessionId, transferId, manifestSha256, itemId, packageName, versionCode, sha256)
        }

        @Suppress("DEPRECATION")
        private fun Intent.userActionIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_INTENT)
        }

        private fun notifyResult(context: Context, text: String) {
            context.applicationContext
                .getSharedPreferences(RESULT_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(RESULT_KEY, text)
                .apply()
            context.sendBroadcast(
                Intent(ACTION_RESULT)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_RESULT_TEXT, text),
            )
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}
