package com.transferkit.client.restore

import android.content.Context
import android.provider.Settings
import com.transferkit.client.SettingsSnapshotPolicy
import java.security.MessageDigest

/** Restores only the small, explicitly allowlisted public Settings.System snapshot. */
class SettingsSystemRestorer(
    context: Context,
    private val approvalGate: RestoreApprovalGate,
) : CategoryRestorer {
    private val appContext = context.applicationContext

    override val categoryIds: Set<String> = setOf("settings")

    override fun restore(item: VerifiedRestoreItem, approval: RestoreApproval?): RestoreItemResult {
        if (item.categoryId !in categoryIds) return failure(item, "UNSUPPORTED")
        if (!Settings.System.canWrite(appContext)) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "WRITE_SETTINGS_REQUIRED")
        }
        if (item.metadata["format"] != SettingsSnapshotPolicy.MANIFEST_FORMAT ||
            (item.metadata["version"] as? Number)?.toInt() != SettingsSnapshotPolicy.VERSION ||
            item.sizeBytes !in 1..SettingsSnapshotPolicy.MAX_PAYLOAD_BYTES.toLong() ||
            !SHA256_PATTERN.matches(item.sha256) || !item.payloadFile.isFile || item.payloadFile.length() != item.sizeBytes
        ) return failure(item, "INVALID_MANIFEST")

        val values = try {
            val bytes = item.payloadFile.readBytes()
            if (bytes.size.toLong() != item.sizeBytes || sha256(bytes) != item.sha256) return integrityFailure(item)
            SettingsSnapshotPolicy.parseCanonicalPayload(bytes) ?: return failure(item, "INVALID_SETTINGS_PAYLOAD")
        } catch (_: Exception) {
            return failure(item, "INVALID_SETTINGS_PAYLOAD")
        }
        val declaredCount = (item.metadata["setting_count"] as? Number)?.toInt()
        if (declaredCount != values.size || values.isEmpty()) return failure(item, "INVALID_MANIFEST")
        if (!approvalGate.consume(approval, item)) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "RESTORE_CONFIRMATION_REQUIRED")
        }

        val previousValues = linkedMapOf<String, String?>()
        val attemptedWrites = mutableListOf<String>()
        try {
            values.forEach { (key, expected) ->
                previousValues[key] = Settings.System.getString(appContext.contentResolver, key)
                attemptedWrites += key
                if (!Settings.System.putString(appContext.contentResolver, key, expected)) {
                    throw SettingsWriteException()
                }
                if (Settings.System.getString(appContext.contentResolver, key) != expected) {
                    throw SettingsPostVerifyException()
                }
            }
            return RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
        } catch (_: SecurityException) {
            return rollbackOrFail(item, values, previousValues, attemptedWrites, "PERMISSION_DENIED")
        } catch (_: SettingsPostVerifyException) {
            return rollbackOrFail(item, values, previousValues, attemptedWrites, "POST_VERIFY_MISMATCH", integrityFailure = true)
        } catch (_: Exception) {
            return rollbackOrFail(item, values, previousValues, attemptedWrites, "TARGET_WRITE_FAILED")
        }
    }

    private fun rollbackOrFail(
        item: VerifiedRestoreItem,
        writtenValues: Map<String, String>,
        previousValues: Map<String, String?>,
        attemptedWrites: List<String>,
        failureReason: String,
        integrityFailure: Boolean = false,
    ): RestoreItemResult {
        var rollbackComplete = true
        attemptedWrites.asReversed().forEach { key ->
            val previous = previousValues[key]
            val current = runCatching { Settings.System.getString(appContext.contentResolver, key) }.getOrNull()
            when {
                current == previous -> Unit // The failed write did not change this key.
                current != writtenValues[key] -> rollbackComplete = false // Never overwrite a concurrent change.
                else -> {
                    val restored = runCatching {
                        Settings.System.putString(appContext.contentResolver, key, previous) &&
                            Settings.System.getString(appContext.contentResolver, key) == previous
                    }.getOrDefault(false)
                    if (!restored) rollbackComplete = false
                }
            }
        }
        return when {
            !rollbackComplete -> RestoreItemResult(item.itemId, RestoreItemStatus.FAILED_INTEGRITY, "ROLLBACK_INCOMPLETE")
            integrityFailure -> RestoreItemResult(item.itemId, RestoreItemStatus.FAILED_INTEGRITY, failureReason)
            else -> failure(item, failureReason)
        }
    }

    private fun failure(item: VerifiedRestoreItem, reason: String) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED, reason)

    private fun integrityFailure(item: VerifiedRestoreItem) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")

    private class SettingsWriteException : Exception()
    private class SettingsPostVerifyException : Exception()

    private companion object {
        val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
