package com.transferkit.client

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Transfer-screen scaffold; navigation is connected when the actual sender flow is implemented. */
@Composable
fun TransferProgressScreen(
    status: String,
    progress: Float?,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    val activity = LocalContext.current as? Activity
    var keepScreenAwake by remember { mutableStateOf(false) }
    var dimWindow by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(0.15f) }

    DisposableEffect(activity, keepScreenAwake, dimWindow, brightness) {
        val window = activity?.window
        val previousFlags = window?.attributes?.flags ?: 0
        val previousBrightness = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window != null && keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val attributes = window.attributes
            attributes.screenBrightness = if (dimWindow) brightness else previousBrightness
            window.attributes = attributes
        }
        onDispose {
            if (window != null) {
                if (previousFlags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                val attributes = window.attributes
                attributes.screenBrightness = previousBrightness
                window.attributes = attributes
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Передача даних", style = MaterialTheme.typography.headlineMedium)
            Text(status)
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%")
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Не гасити екран поки ця сторінка відкрита")
                Switch(checked = keepScreenAwake, onCheckedChange = { keepScreenAwake = it })
            }
            if (keepScreenAwake) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Зменшити яскравість цього вікна")
                    Switch(checked = dimWindow, onCheckedChange = { dimWindow = it })
                }
                if (dimWindow) {
                    Text("Яскравість вікна: ${(brightness * 100).toInt()}%")
                    Slider(
                        value = brightness,
                        onValueChange = { brightness = it },
                        valueRange = 0.10f..0.30f,
                    )
                }
                Text("Екран може згаснути у фоні або після натискання кнопки живлення. Це не замінює фонове сповіщення та збереження чекпойнтів.")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPause) { Text("Пауза") }
                Button(onClick = onCancel) { Text("Скасувати") }
            }
        }
    }
}
