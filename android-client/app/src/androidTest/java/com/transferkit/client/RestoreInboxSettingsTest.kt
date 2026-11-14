package com.transferkit.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.transferkit.client.receiver.ReceivedTransferSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class RestoreInboxSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsOnlyTransferCanOpenRestoreReview() {
        composeRule.setContent {
            MaterialTheme {
                RestoreInboxScreen(
                    transfers = listOf(
                        ReceivedTransferSummary(
                            transferId = "settings-only-transfer",
                            manifestSha256 = "a".repeat(64),
                            verifiedItemCount = 1,
                            restorableItemCount = 0,
                            restorableBytes = 0,
                            restoredItemCount = 0,
                            unavailableVerifiedItemCount = 0,
                            failedItemCount = 0,
                            restorableSettingsItemCount = 1,
                            restorableSettingsBytes = 256,
                        ),
                    ),
                    status = "synthetic fixture",
                    loading = false,
                    restoring = false,
                    onBack = {},
                    onRefresh = {},
                    onReview = {},
                    onReviewApks = {},
                )
            }
        }

        composeRule.onNodeWithText("Наборів публічних системних параметрів для відновлення: 1").assertIsDisplayed()
        composeRule.onNodeWithText("Переглянути й відновити файли, медіа, контакти, календар і параметри")
            .assertIsEnabled()
    }
}
