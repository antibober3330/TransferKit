package com.transferkit.client.restore

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.transferkit.client.SettingsSnapshotPolicy
import com.transferkit.client.canonicalSettingsBytes
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSystemRestorerInstrumentedTest {
    @Test
    fun missingWriteAccessDoesNotChangeSettingsOrConsumeApproval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse("This safety case requires WRITE_SETTINGS to remain disabled", Settings.System.canWrite(context))

        val key = Settings.System.SOUND_EFFECTS_ENABLED
        val original = Settings.System.getString(context.contentResolver, key)
        val target = if (original == "1") "0" else "1"
        val bytes = canonicalSettingsBytes(mapOf(key to target))
        val transferId = UUID.randomUUID().toString()
        val manifestSha = "d".repeat(64)
        val payloadFile = File(context.cacheDir, "settings-no-access-$transferId.json").apply { writeBytes(bytes) }
        val item = VerifiedRestoreItem(
            transferId = transferId,
            manifestSha256 = manifestSha,
            categoryId = "settings",
            itemId = "settings_system_no_access_fixture",
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            metadata = mapOf(
                "format" to SettingsSnapshotPolicy.MANIFEST_FORMAT,
                "version" to SettingsSnapshotPolicy.VERSION,
                "setting_count" to 1,
            ),
            payloadFile = payloadFile,
        )
        val gate = RestoreApprovalGate()
        val approval = requireNotNull(
            gate.approveAfterUserConfirmation(transferId, manifestSha, listOf(item), confirmedByUser = true),
        )
        try {
            val result = SettingsSystemRestorer(context, gate).restore(item, approval)
            assertEquals(RestoreItemStatus.USER_ACTION_REQUIRED, result.status)
            assertEquals("WRITE_SETTINGS_REQUIRED", result.errorReason)
            assertEquals(original, Settings.System.getString(context.contentResolver, key))
            assertTrue("The restorer must leave the approval available until system access is granted", gate.consume(approval, item))
        } finally {
            gate.revoke(approval)
            payloadFile.delete()
        }
    }

    @Test
    fun restoresOneSyntheticAllowlistedValueAndRestoresOriginalAfterTest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Grant the Android WRITE_SETTINGS special access before this acceptance test", Settings.System.canWrite(context))

        val key = Settings.System.SOUND_EFFECTS_ENABLED
        val original = Settings.System.getString(context.contentResolver, key)
        val target = if (original == "1") "0" else "1"
        val bytes = canonicalSettingsBytes(mapOf(key to target))
        val transferId = UUID.randomUUID().toString()
        val manifestSha = "c".repeat(64)
        val payloadFile = File(context.cacheDir, "settings-fixture-$transferId.json").apply { writeBytes(bytes) }
        val item = VerifiedRestoreItem(
            transferId = transferId,
            manifestSha256 = manifestSha,
            categoryId = "settings",
            itemId = "settings_system_fixture",
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            metadata = mapOf(
                "format" to SettingsSnapshotPolicy.MANIFEST_FORMAT,
                "version" to SettingsSnapshotPolicy.VERSION,
                "setting_count" to 1,
            ),
            payloadFile = payloadFile,
        )
        val gate = RestoreApprovalGate()
        val approval = requireNotNull(
            gate.approveAfterUserConfirmation(transferId, manifestSha, listOf(item), confirmedByUser = true),
        )
        try {
            val result = SettingsSystemRestorer(context, gate).restore(item, approval)
            assertEquals("status=${result.status}, reason=${result.errorReason}", RestoreItemStatus.RESTORED, result.status)
            assertEquals(target, Settings.System.getString(context.contentResolver, key))
        } finally {
            gate.revoke(approval)
            if (Settings.System.getString(context.contentResolver, key) == target) {
                assertEquals(true, Settings.System.putString(context.contentResolver, key, original))
                assertEquals(original, Settings.System.getString(context.contentResolver, key))
            }
            payloadFile.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
