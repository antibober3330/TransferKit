package com.transferkit.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class TransferProgressScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleScreenDoesNotInventProgressAndLeavesUnsafeActionsDisabled() {
        composeRule.setContent {
            MaterialTheme {
                TransferProgressScreen(onBack = {}, onPause = {}, onCancel = {})
            }
        }

        composeRule.onNodeWithText("Передача зараз не активна.").assertIsDisplayed()
        composeRule.onNodeWithText("Точний прогрес з’явиться після першого durable checkpoint.").assertIsDisplayed()
        composeRule.onNodeWithText("Не гасити екран поки ця сторінка відкрита").assertIsDisplayed()
        composeRule.onNodeWithText("Пауза").assertIsNotEnabled()
        composeRule.onNodeWithText("Скасувати").assertIsNotEnabled()
    }
}
