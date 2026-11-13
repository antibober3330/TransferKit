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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Shows only aggregate counters that the receiver has durably checkpointed. */
@Composable
fun TransferProgressScreen(
    onBack: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var snapshot by remember { mutableStateOf(TransferForegroundService.progressSnapshot()) }
    var keepScreenAwake by remember { mutableStateOf(false) }
    var dimWindow by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(0.15f) }

    LaunchedEffect(context) {
        while (isActive) {
            snapshot = TransferForegroundService.progressSnapshot()
            delay(PROGRESS_REFRESH_INTERVAL_MS)
        }
    }

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
            Button(onClick = onBack) { Text("Назад") }
            Text(snapshot.statusText())
            snapshot.receivedBytesText()?.let { Text(it) }
            val progress = snapshot.progressFraction
            if (progress == null) {
                Text("Точний прогрес з’явиться після першого durable checkpoint.")
            } else {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Не гасити екран поки ця сторінка відкрита", modifier = Modifier.weight(1f))
                Switch(checked = keepScreenAwake, onCheckedChange = { keepScreenAwake = it })
            }
            if (keepScreenAwake) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Зменшити яскравість цього вікна", modifier = Modifier.weight(1f))
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
                Button(onClick = onPause, enabled = false) { Text("Пауза") }
                Button(onClick = onCancel, enabled = false) { Text("Скасувати") }
            }
            Text("Керування паузою та скасуванням ще не під’єднане до безпечної межі чанка.")
        }
    }
}

private const val PROGRESS_REFRESH_INTERVAL_MS = 500L
