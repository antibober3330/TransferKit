package com.transferkit.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun DeviceInfoScreen(
    deviceInfo: DeviceInfo,
    onOpenRestoreInbox: () -> Unit,
    onOpenTransferProgress: () -> Unit,
    mediaStatus: String,
    onScanMedia: (MediaCategory) -> Unit,
    onScanPhotosWithOriginalMetadata: () -> Unit,
    onPickDocuments: () -> Unit,
    contactsStatus: String,
    onScanContacts: () -> Unit,
    calendarStatus: String,
    onScanCalendar: () -> Unit,
    smsStatus: String,
    onScanSms: () -> Unit,
    callLogStatus: String,
    onScanCallLog: () -> Unit,
    apkStatus: String,
    onScanApks: () -> Unit,
    settingsStatus: String,
    onScanSettings: () -> Unit,
    shizukuStatus: String,
    onRequestShizukuPermission: () -> Unit,
    manifestSelectionCount: Int,
    manifestStatus: String,
    manifestBuildInProgress: Boolean,
    onBuildManifest: () -> Unit,
    onClearManifest: () -> Unit,
    manifestReady: Boolean,
    sourceListenerStatus: String,
    onStartUsbSource: () -> Unit,
    onStopUsbSource: () -> Unit,
    receiverListenerStatus: String,
    onStartUsbReceiver: () -> Unit,
    onStopUsbReceiver: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("TransferKit", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onOpenRestoreInbox) {
                Text("Отримані передачі та відновлення")
            }
            Button(onClick = onOpenTransferProgress) {
                Text("Стан передачі")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("Прийом з Windows Station через USB/ADB", style = MaterialTheme.typography.titleLarge)
            Text("Запусти приймач перед передаванням. ADB має бути авторизований на цьому комп’ютері; дані приймаються лише після одноразової перевірки сесії.")
            Button(
                enabled = receiverListenerStatus != "STARTING" && receiverListenerStatus != "LISTENING",
                onClick = onStartUsbReceiver,
            ) { Text(if (receiverListenerStatus == "STARTING") "Запускаю приймач…" else "Запустити приймач USB/ADB") }
            Button(
                enabled = receiverListenerStatus == "LISTENING" || receiverListenerStatus == "STARTING",
                onClick = onStopUsbReceiver,
            ) { Text("Зупинити приймач") }
            Text(
                when (receiverListenerStatus) {
                    "STARTING" -> "Служба прийому запускається…"
                    "LISTENING" -> "Приймач активний і очікує Windows Station."
                    "ERROR" -> "Не вдалося відкрити локальний ADB listener. Перезапусти приймач і перевір журнал сповіщень."
                    else -> "Приймач зупинений."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Дані пристрою", style = MaterialTheme.typography.titleLarge)
            Text("${deviceInfo.manufacturer} ${deviceInfo.model}")
            Text("Android ${deviceInfo.androidRelease} (API ${deviceInfo.sdk})")

            Spacer(modifier = Modifier.height(8.dp))
            Text("Оперативна пам’ять", style = MaterialTheme.typography.titleMedium)
            Text(
                "Доступно ${formatGiB(deviceInfo.availableMemoryBytes)} " +
                    "із ${formatGiB(deviceInfo.totalMemoryBytes)}"
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("Внутрішнє сховище", style = MaterialTheme.typography.titleMedium)
            Text(
                "Доступно ${formatGiB(deviceInfo.dataStorageAvailableBytes)} " +
                    "із ${formatGiB(deviceInfo.dataStorageTotalBytes)}"
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text("Медіадані", style = MaterialTheme.typography.titleLarge)
            Text(
                "Дозволи джерела: API 26–32 — READ_EXTERNAL_STORAGE; API 33+ — " +
                    "READ_MEDIA_IMAGES, READ_MEDIA_VIDEO або READ_MEDIA_AUDIO для вибраної категорії. " +
                    "На Android 14+ READ_MEDIA_VISUAL_USER_SELECTED дозволяє лише доступ до фото й відео, " +
                    "вибраних у системному вікні файлів. " +
                    "Запит з’явиться після вибору категорії. На цілі запис через MediaStore не потребує READ_MEDIA; " +
                    "На API 26–28 для відновлення фото, відео, аудіо та Downloads знадобиться окремий " +
                    "дозвіл на запис; починаючи з API 29 використовується MediaStore."
            )
            Button(onClick = { onScanMedia(MediaCategory.PHOTOS) }) {
                Text("Перевірити фото")
            }
            Text("За замовчуванням Android може приховати геолокацію в EXIF. Її передавання необов’язкове й потребує окремого дозволу.")
            Button(onClick = onScanPhotosWithOriginalMetadata) {
                Text("Фото з оригінальними EXIF (окремий дозвіл)")
            }
            Button(onClick = { onScanMedia(MediaCategory.VIDEOS) }) {
                Text("Перевірити відео")
            }
            Button(onClick = { onScanMedia(MediaCategory.AUDIO) }) {
                Text("Перевірити аудіо")
            }
            Button(onClick = onPickDocuments) {
                Text("Вибрати документи або Downloads")
            }
            Text("Документи й Downloads відкриваються через системний SAF picker: доступ надається лише до вибраних файлів, без широкого дозволу на сховище.")
            Text(mediaStatus, style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(12.dp))
            Text("Контакти", style = MaterialTheme.typography.titleLarge)
            Text("Джерело: READ_CONTACTS. Для відновлення на цільовому пристрої потрібен WRITE_CONTACTS. Доступ запитується після натискання кнопки.")
            Button(onClick = onScanContacts) {
                Text("Перевірити контакти")
            }
            Text(contactsStatus, style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(12.dp))
            Text("Календар", style = MaterialTheme.typography.titleLarge)
            Text("Джерело: READ_CALENDAR; для відновлення на цілі — WRITE_CALENDAR. Дозвіл запитується після натискання кнопки. Назви календарів і акаунтів не експортуються.")
            Button(onClick = onScanCalendar) {
                Text("Перевірити події календаря")
            }
            Text(calendarStatus, style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(12.dp))
            Text("SMS", style = MaterialTheme.typography.titleLarge)
            Text("Джерело потребує READ_SMS. Це hard-restricted дозвіл: Android має дозволити його інсталятору/збірці; звичайний запит у застосунку може бути недостатнім. TransferKit не стає SMS-застосунком за замовчуванням. MMS не включені.")
            Button(onClick = onScanSms) { Text("Перевірити SMS") }
            Text(smsStatus, style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(12.dp))
            Text("Журнал дзвінків", style = MaterialTheme.typography.titleLarge)
            Text("Джерело потребує READ_CALL_LOG. Це обмежений Android дозвіл, доступність залежить від інсталятора й прошивки. TransferKit не стає телефонним застосунком за замовчуванням.")
            Button(onClick = onScanCallLog) { Text("Перевірити журнал дзвінків") }
            Text(callLogStatus, style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(12.dp))
            Text("APK застосунків", style = MaterialTheme.typography.titleLarge)
            Text("Для переліку застосунків на API 30+ потрібен QUERY_ALL_PACKAGES. Для встановлення на цілі потрібен REQUEST_INSTALL_PACKAGES і окреме системне підтвердження кожного APK. Системні й split APK пропускаються та рахуються окремо.")
            Button(onClick = onScanApks) { Text("Перевірити доступні APK") }
            Text(apkStatus, style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(12.dp))
            Text("Системні налаштування", style = MaterialTheme.typography.titleLarge)
            Text("WRITE_SETTINGS потребує окремого дозволу спеціального доступу в налаштуваннях Android. Доступний лише allowlist Settings.System; Secure/Global не змінюються. Shizuku та Device Owner не обходять обмеження прошивки. TransferKit не виконує Device Owner provisioning; на новому пристрої воно може вимагати заводського скидання.")
            Button(onClick = onScanSettings) { Text("Перевірити доступність налаштувань") }
            Text(settingsStatus, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRequestShizukuPermission) { Text("Перевірити та дозволити Shizuku") }
            Text(shizukuStatus, style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(12.dp))
            Text("Підготовка маніфесту", style = MaterialTheme.typography.titleLarge)
            Text("До нього увійдуть категорії, які ти успішно перевірив вище. Підготовка не запускає передавання.")
            Text("Вибрано категорій: $manifestSelectionCount")
            Button(
                enabled = manifestSelectionCount > 0 && !manifestBuildInProgress,
                onClick = onBuildManifest,
            ) {
                Text(if (manifestBuildInProgress) "Готую маніфест…" else "Підготувати маніфест")
            }
            Button(
                enabled = manifestSelectionCount > 0 && !manifestBuildInProgress,
                onClick = onClearManifest,
            ) {
                Text("Очистити вибір і тимчасовий snapshot")
            }
            Text(manifestStatus, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Передавання з цього телефона через USB/ADB", style = MaterialTheme.typography.titleLarge)
            Text("Запускай після підготовки маніфесту. Служба чекає на автентифіковане ADB-з’єднання та лишається активною, доки її не зупинити.")
            Button(
                enabled = manifestReady && sourceListenerStatus != "STARTING" && sourceListenerStatus != "LISTENING",
                onClick = onStartUsbSource,
            ) {
                Text(if (sourceListenerStatus == "STARTING") "Запускаю джерело…" else "Запустити USB/ADB-джерело")
            }
            Button(
                enabled = sourceListenerStatus in setOf("LISTENING", "STARTING", "ERROR"),
                onClick = onStopUsbSource,
            ) { Text("Зупинити джерело") }
            Text(
                when (sourceListenerStatus) {
                    "STARTING" -> "Служба джерела запускається…"
                    "LISTENING" -> "Джерело активне й очікує Windows Station."
                    "ERROR" -> "Не вдалося запустити джерело. Перевір підготовлений маніфест і спробуй знову."
                    else -> "Джерело зупинене."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun formatGiB(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
