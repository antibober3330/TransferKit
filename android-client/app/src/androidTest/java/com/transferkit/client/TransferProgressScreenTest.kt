package com.transferkit.client

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

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

    @Test
    fun keepAwakeAndDimmerAreOptInAndRestoreOriginalWindowAttributes() {
        lateinit var activity: Activity
        composeRule.setContent {
            var visible by remember { mutableStateOf(true) }
            activity = androidx.compose.ui.platform.LocalContext.current as Activity
            MaterialTheme {
                if (visible) {
                    TransferProgressScreen(
                        onBack = { visible = false },
                        onPause = {},
                        onCancel = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val original = readWindowAttributes(activity)
        val keepScreenOnFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        assertEquals(0, original.first and keepScreenOnFlag)

        composeRule.onAllNodes(isToggleable())[0].performClick()
        composeRule.waitForIdle()
        assertNotEquals(0, readWindowAttributes(activity).first and keepScreenOnFlag)

        composeRule.onAllNodes(isToggleable())[1].performClick()
        composeRule.waitForIdle()
        assertEquals(0.15f, readWindowAttributes(activity).second, 0.001f)

        composeRule.onNodeWithText("Назад").performClick()
        composeRule.waitForIdle()

        val restored = readWindowAttributes(activity)
        assertEquals(original.first and keepScreenOnFlag, restored.first and keepScreenOnFlag)
        assertEquals(original.second, restored.second, 0.001f)
    }

    private fun readWindowAttributes(activity: Activity): Pair<Int, Float> {
        var attributes: Pair<Int, Float>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val window = activity.window
            attributes = window.attributes.flags to window.attributes.screenBrightness
        }
        return requireNotNull(attributes)
    }
}
