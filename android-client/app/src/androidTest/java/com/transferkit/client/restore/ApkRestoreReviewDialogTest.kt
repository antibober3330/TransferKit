package com.transferkit.client.restore

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class ApkRestoreReviewDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val item = VerifiedRestoreItem(
        transferId = "transfer-1",
        manifestSha256 = "a".repeat(64),
        categoryId = "apks",
        itemId = "apk-1",
        sizeBytes = 1024L,
        sha256 = "b".repeat(64),
        metadata = mapOf("package_name" to "org.example.app", "version_code" to 7L),
        payloadFile = File("unused-fixture.apk"),
    )

    @Test
    fun listsVerifiedApkAndSelectsOnlyAfterExplicitTap() {
        var selected: VerifiedRestoreItem? = null
        composeRule.setContent {
            MaterialTheme {
                ApkRestoreReviewDialog(
                    items = listOf(item),
                    onInstall = { selected = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("org.example.app").assertIsDisplayed()
        assertEquals(null, selected)
        composeRule.onNodeWithText("org.example.app").performClick()
        composeRule.runOnIdle { assertEquals(item, selected) }
    }
}
