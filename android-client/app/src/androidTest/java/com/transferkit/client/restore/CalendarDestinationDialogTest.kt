package com.transferkit.client.restore

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class CalendarDestinationDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun requiresExplicitCalendarSelectionBeforeContinuing() {
        var selected: Long? = null
        val destinations = listOf(
            CalendarDestination(11L, "Personal", "person@example.test", "com.google"),
            CalendarDestination(12L, "Work", "work@example.test", "com.google"),
        )
        composeRule.setContent {
            MaterialTheme {
                CalendarDestinationDialog(
                    destinations = destinations,
                    onSelected = { selected = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("TransferKit запише події лише у вибраний календар. Якщо він синхронізується з акаунтом, події можуть синхронізуватися за його налаштуваннями.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Вибрати").assertIsNotEnabled()
        assertEquals(null, selected)

        composeRule.onNodeWithText("Work").performClick()
        composeRule.onNodeWithText("Вибрати").performClick()
        composeRule.runOnIdle { assertEquals(12L, selected) }
    }
}
