package com.transferkit.client

import android.provider.Settings
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.transferkit.client.protocol.ProtocolCodec
import com.transferkit.client.receiver.AndroidTransferReceiver
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production Inbox -> WRITE_SETTINGS Activity Result -> review route. */
@RunWith(AndroidJUnit4::class)
class SettingsRestoreInboxFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsOnlyTransferReturnsFromSystemAccessToConfirmationWithoutWriting() {
        val context = composeRule.activity.applicationContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transferId = UUID.randomUUID()
        val itemId = "synthetic_settings_${transferId.toString().replace("-", "")}"
        val originalValue = Settings.System.getString(
            context.contentResolver,
            Settings.System.SOUND_EFFECTS_ENABLED,
        ) ?: "0"
        val settingsBytes = canonicalSettingsBytes(
            mapOf(Settings.System.SOUND_EFFECTS_ENABLED to originalValue),
        )
        val manifestBytes = manifest(transferId, itemId, settingsBytes)
        val receiver = AndroidTransferReceiver(context)

        assertFalse("Fresh test installation must not have WRITE_SETTINGS access", Settings.System.canWrite(context))
        try {
            assertEquals(0, receiver.acceptManifest(manifestBytes).status)
            assertEquals(
                0,
                receiver.acceptChunk(
                    ProtocolCodec.encodeChunk(transferId, "settings", itemId, 0, 1, settingsBytes),
                ).status,
            )
            receiver.buildFinalReceipt(transferId)
            assertEquals(1, receiver.verifiedRestoreItems(transferId.toString()).size)

            composeRule.onNodeWithText("Отримані передачі та відновлення").performClick()
            val settingsCount = "Наборів публічних системних параметрів для відновлення: 1"
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(settingsCount).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(settingsCount).assertIsDisplayed()

            composeRule.onNodeWithText("Переглянути й відновити файли, медіа, контакти, календар і параметри")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            // The production intent opens Settings and removes this app's Compose hierarchy.
            // Grant the test-only app-op, then return from the real Settings Activity.
            shell(instrumentation, "appops set com.transferkit.client WRITE_SETTINGS allow")
            shell(instrumentation, "input keyevent 4")
            composeRule.waitUntil(10_000) { Settings.System.canWrite(context) }
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText("Підтвердити відновлення").fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("Підтвердити відновлення").assertIsDisplayed()
            composeRule.onNodeWithText(
                "Буде відновлено до 1 дозволених публічних параметрів Settings.System. Інші системні, Secure/Global, облікові та мережеві налаштування не змінюються.",
                substring = true,
            ).assertIsDisplayed()
            assertTrue("Activity Result must observe the granted special access", Settings.System.canWrite(context))
            assertEquals(
                originalValue,
                Settings.System.getString(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED),
            )

            composeRule.onNodeWithText("Скасувати").performClick()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(settingsCount).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(
                "Canceling review must not modify the synthetic setting",
                originalValue,
                Settings.System.getString(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED),
            )
        } finally {
            runCatching { shell(instrumentation, "input keyevent 4") }
            runCatching { shell(instrumentation, "appops reset com.transferkit.client") }
            val bytes = receiver.pendingTransferCleanupBytes(transferId.toString())
            if (bytes != null) {
                val approval = receiver.approveAbandonAfterUserConfirmation(
                    transferId.toString(),
                    bytes,
                    confirmedByUser = true,
                )
                receiver.abandonTransfer(transferId.toString(), approval)
            }
            receiver.close()
        }
    }

    private fun manifest(transferId: UUID, itemId: String, payload: ByteArray): ByteArray {
        val digest = sha256(payload)
        return """{"version":"1.0","transfer_id":"$transferId","created_at":"2026-11-14T12:00:00Z","source_device":{"manufacturer":"TransferKit test","model":"Synthetic fixture","sdk":34,"fingerprint":"test-only"},"categories":{"settings":{"provider":"android.provider.Settings.System","method":"read_allowlist_v1","items":[{"id":"$itemId","size":${payload.size},"sha256":"$digest","metadata":{"format":"${SettingsSnapshotPolicy.MANIFEST_FORMAT}","version":${SettingsSnapshotPolicy.VERSION},"setting_count":1}}]}}}""".toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun shell(instrumentation: android.app.Instrumentation, command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
}
