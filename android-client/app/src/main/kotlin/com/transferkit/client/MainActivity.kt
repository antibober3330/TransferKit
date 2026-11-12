package com.transferkit.client

import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.database.SQLException
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.util.concurrent.Executors
import com.transferkit.client.receiver.AndroidTransferReceiver
import com.transferkit.client.receiver.ReceivedTransferSummary
import com.transferkit.client.restore.MediaStoreRestorer
import com.transferkit.client.restore.RestoreApproval
import com.transferkit.client.restore.RestoreApprovalGate
import com.transferkit.client.restore.RestoreItemStatus
import com.transferkit.client.restore.RestorerRegistry
import com.transferkit.client.restore.RestoreConfirmationDialog
import com.transferkit.client.restore.VerifiedRestoreItem

class MainActivity : ComponentActivity() {
    private val mediaExecutor = Executors.newSingleThreadExecutor()
    private val restoreApprovalGate = RestoreApprovalGate()
    private lateinit var mediaProvider: MediaProvider
    private lateinit var contactsProvider: ContactsProvider
    private lateinit var calendarProvider: CalendarProvider
    private lateinit var smsProvider: SmsProvider
    private lateinit var callLogProvider: CallLogProvider
    private lateinit var apkProvider: ApkProvider
    private lateinit var settingsProvider: SettingsProvider
    private lateinit var transferReceiver: AndroidTransferReceiver
    private lateinit var restorerRegistry: RestorerRegistry
    private var pendingCategory: MediaCategory? = null
    private val selectedManifestSources = linkedMapOf<String, ManifestCategorySource>()
    private lateinit var sourceDeviceInfo: DeviceInfo
    private var preparedManifestBundle: TransferManifestBundle? = null
    private var manifestSelectionVersion = 0L
    private var manifestSelectionCount by mutableStateOf(0)
    private var manifestBuildInProgress by mutableStateOf(false)
    private var showRestoreInbox by mutableStateOf(false)
    private var adbReceiverStatus by mutableStateOf(TransferForegroundService.STATUS_STOPPED)
    private var adbSourceStatus by mutableStateOf(TransferForegroundService.STATUS_STOPPED)
    private var restoreInboxLoading by mutableStateOf(false)
    private var restoreInProgress by mutableStateOf(false)
    private var restoreInboxStatus by mutableStateOf("Список отриманих передач ще не оновлено.")
    private var receivedTransfers by mutableStateOf<List<ReceivedTransferSummary>>(emptyList())
    private var selectedRestoreItems by mutableStateOf<List<VerifiedRestoreItem>>(emptyList())
    private var manifestStatus by mutableStateOf("Маніфест ще не підготовлено.")
    private var mediaStatus by mutableStateOf("Медіатеку ще не перевірено.")
    private var contactsStatus by mutableStateOf("Контакти ще не перевірено.")
    private var calendarStatus by mutableStateOf("Календар ще не перевірено.")
    private var smsStatus by mutableStateOf("SMS ще не перевірено.")
    private var callLogStatus by mutableStateOf("Журнал дзвінків ще не перевірено.")
    private var apkStatus by mutableStateOf("Застосунки ще не перевірено.")
    private var settingsStatus by mutableStateOf("Налаштування ще не перевірено.")

    private val mediaPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val category = pendingCategory
        pendingCategory = null
        if (category != null && mediaProvider.hasReadAccess(category)) {
            scanMedia(category)
        } else if (category != null) {
            mediaStatus = "Доступ до категорії ${category.contractId} не надано."
        }
    }

    private val documentsPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) {
            mediaStatus = "Файли не вибрано."
        } else {
            scanSelectedDocuments(uris)
        }
    }

    private val contactsPermissionRequest = registerForActivityResult(RequestPermission()) { granted ->
        if (granted && contactsProvider.hasReadAccess()) {
            scanContacts()
        } else {
            contactsStatus = "Доступ до контактів не надано."
        }
    }

    private val calendarPermissionRequest = registerForActivityResult(RequestPermission()) { granted ->
        if (granted && calendarProvider.hasReadAccess()) {
            scanCalendar()
        } else {
            calendarStatus = "Доступ до календаря не надано."
        }
    }

    private val smsPermissionRequest = registerForActivityResult(RequestPermission()) { granted ->
        if (granted && smsProvider.hasReadAccess()) {
            scanSms()
        } else {
            smsStatus = "Android не надав READ_SMS. Для цього hard-restricted дозволу потрібне " +
                "схвалене джерело встановлення; доступ не обходиться root або іншими API."
        }
    }

    private val callLogPermissionRequest = registerForActivityResult(RequestPermission()) { granted ->
        if (granted && callLogProvider.hasReadAccess()) {
            scanCallLog()
        } else {
            callLogStatus = "Android не надав READ_CALL_LOG. Для цього hard-restricted дозволу " +
                "потрібне схвалене джерело встановлення; доступ не обходиться root або іншими API."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProvider = MediaProvider(applicationContext)
        contactsProvider = ContactsProvider(applicationContext)
        calendarProvider = CalendarProvider(applicationContext)
        smsProvider = SmsProvider(applicationContext)
        callLogProvider = CallLogProvider(applicationContext)
        apkProvider = ApkProvider(applicationContext)
        settingsProvider = SettingsProvider(applicationContext)
        transferReceiver = AndroidTransferReceiver(applicationContext)
        adbReceiverStatus = TransferForegroundService.receiverStatus(this)
        adbSourceStatus = TransferForegroundService.sourceStatus(this)
        restorerRegistry = RestorerRegistry(MediaStoreRestorer(applicationContext, restoreApprovalGate))
        val deviceInfo = DeviceInfoProvider.read(applicationContext)
        sourceDeviceInfo = deviceInfo
        setContent {
            MaterialTheme {
                if (showRestoreInbox) {
                    RestoreInboxScreen(
                        transfers = receivedTransfers,
                        status = restoreInboxStatus,
                        loading = restoreInboxLoading,
                        restoring = restoreInProgress,
                        onBack = {
                            selectedRestoreItems = emptyList()
                            showRestoreInbox = false
                        },
                        onRefresh = { refreshRestoreInbox() },
                        onReview = ::prepareRestoreReview,
                    )
                    if (selectedRestoreItems.isNotEmpty()) {
                        RestoreConfirmationDialog(
                            transferId = selectedRestoreItems.first().transferId,
                            manifestSha256 = selectedRestoreItems.first().manifestSha256,
                            items = selectedRestoreItems,
                            approvalGate = restoreApprovalGate,
                            onApproved = ::beginRestore,
                            onDismiss = { selectedRestoreItems = emptyList() },
                        )
                    }
                } else {
                    DeviceInfoScreen(
                        deviceInfo = deviceInfo,
                        onOpenRestoreInbox = {
                            showRestoreInbox = true
                            refreshRestoreInbox()
                        },
                        mediaStatus = mediaStatus,
                        onScanMedia = ::requestMediaAccess,
                        onPickDocuments = { documentsPicker.launch(arrayOf("*/*")) },
                        contactsStatus = contactsStatus,
                        onScanContacts = ::requestContactsAccess,
                        calendarStatus = calendarStatus,
                        onScanCalendar = ::requestCalendarAccess,
                        smsStatus = smsStatus,
                        onScanSms = ::requestSmsAccess,
                        callLogStatus = callLogStatus,
                        onScanCallLog = ::requestCallLogAccess,
                        apkStatus = apkStatus,
                        onScanApks = ::scanApks,
                        settingsStatus = settingsStatus,
                        onScanSettings = ::scanSettings,
                        manifestSelectionCount = manifestSelectionCount,
                        manifestStatus = manifestStatus,
                        manifestBuildInProgress = manifestBuildInProgress,
                        onBuildManifest = ::buildManifest,
                        onClearManifest = ::clearManifestSelection,
                        manifestReady = preparedManifestBundle != null,
                        sourceListenerStatus = adbSourceStatus,
                        onStartUsbSource = ::startUsbSource,
                        onStopUsbSource = ::stopUsbSource,
                        receiverListenerStatus = adbReceiverStatus,
                        onStartUsbReceiver = ::startUsbReceiver,
                        onStopUsbReceiver = ::stopUsbReceiver,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adbReceiverStatus = TransferForegroundService.receiverStatus(this)
        adbSourceStatus = TransferForegroundService.sourceStatus(this)
    }

    private fun startUsbSource() {
        if (adbSourceStatus == TransferForegroundService.STATUS_STARTING ||
            adbSourceStatus == TransferForegroundService.STATUS_LISTENING
        ) return
        val bundle = preparedManifestBundle
        if (bundle == null) {
            adbSourceStatus = TransferForegroundService.STATUS_ERROR
            return
        }
        adbSourceStatus = TransferForegroundService.STATUS_STARTING
        if (TransferForegroundService.startAdbSource(this, bundle)) {
            preparedManifestBundle = null // The foreground service now owns and closes the bundle.
            manifestStatus = "Підготовлений маніфест передано службі USB/ADB-джерела."
        } else {
            adbSourceStatus = TransferForegroundService.STATUS_ERROR
            manifestStatus = "Не вдалося запустити USB/ADB-джерело; підготовлений маніфест збережено."
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isDestroyed) adbSourceStatus = TransferForegroundService.sourceStatus(this)
        }, 500)
    }

    private fun stopUsbSource() {
        TransferForegroundService.stopAdbSource(this)
        adbSourceStatus = TransferForegroundService.STATUS_STOPPED
    }

    private fun startUsbReceiver() {
        adbReceiverStatus = TransferForegroundService.STATUS_STARTING
        runCatching { TransferForegroundService.startAdbReceiver(this) }
            .onFailure { adbReceiverStatus = TransferForegroundService.STATUS_ERROR }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isDestroyed) adbReceiverStatus = TransferForegroundService.receiverStatus(this)
        }, 500)
    }

    private fun stopUsbReceiver() {
        TransferForegroundService.stopAdbReceiver(this)
        adbReceiverStatus = TransferForegroundService.STATUS_STOPPED
    }

    private fun refreshRestoreInbox(preserveStatus: Boolean = false) {
        if (restoreInboxLoading || restoreInProgress) return
        restoreInboxLoading = true
        if (!preserveStatus) restoreInboxStatus = "Перевіряю завершені передачі…"
        mediaExecutor.execute {
            val result = runCatching { transferReceiver.availableRestoreTransfers() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                restoreInboxLoading = false
                receivedTransfers = result.getOrDefault(emptyList())
                if (!preserveStatus) {
                    restoreInboxStatus = if (result.isSuccess) {
                        if (receivedTransfers.isEmpty()) "Завершених передач ще немає."
                        else "Знайдено завершених передач: ${receivedTransfers.size}."
                    } else {
                        "Не вдалося прочитати список отриманих передач."
                    }
                }
            }
        }
    }

    private fun prepareRestoreReview(transferId: String) {
        if (restoreInboxLoading || restoreInProgress) return
        restoreInboxLoading = true
        restoreInboxStatus = "Перевіряю отримані items перед підтвердженням…"
        mediaExecutor.execute {
            val result = runCatching {
                transferReceiver.verifiedRestoreItems(transferId).filter {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        it.categoryId in setOf("photos", "videos", "audio", "downloads")
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                restoreInboxLoading = false
                val items = result.getOrDefault(emptyList())
                selectedRestoreItems = items
                restoreInboxStatus = when {
                    result.isFailure -> "Не вдалося відкрити перевірені items передачі."
                    items.isEmpty() -> "У передачі немає перевірених медіафайлів, які підтримує цей Android API."
                    else -> "Підготовлено для перегляду медіафайлів: ${items.size}. Запис ще не почався."
                }
            }
        }
    }

    private fun beginRestore(approval: RestoreApproval) {
        val items = selectedRestoreItems
        selectedRestoreItems = emptyList()
        if (items.isEmpty()) {
            restoreApprovalGate.revoke(approval)
            return
        }
        restoreInProgress = true
        restoreInboxStatus = "Відновлюю та перевіряю записані медіафайли…"
        mediaExecutor.execute {
            val results = try {
                items.map { item ->
                    runCatching { restorerRegistry.restore(item, approval) }.getOrElse {
                        com.transferkit.client.restore.RestoreItemResult(
                            itemId = item.itemId,
                            status = RestoreItemStatus.FAILED,
                            errorReason = "RESTORE_FAILED",
                        )
                    }
                }
            } finally {
                restoreApprovalGate.revoke(approval)
            }
            val cleanupPending = items.zip(results).count { (item, result) ->
                result.status == RestoreItemStatus.RESTORED &&
                    !runCatching { transferReceiver.recordPostVerifiedRestore(item) }.getOrDefault(false)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                restoreInProgress = false
                val restored = results.count { it.status == RestoreItemStatus.RESTORED }
                val integrity = results.count { it.status == RestoreItemStatus.FAILED_INTEGRITY }
                val unavailable = results.count { it.status == RestoreItemStatus.UNAVAILABLE }
                val failed = results.size - restored - integrity - unavailable
                restoreInboxStatus = "Результат: відновлено $restored із ${items.size}; " +
                    "помилок цілісності $integrity, недоступно $unavailable, інші помилки $failed; " +
                    "не вдалося прибрати тимчасові файли: $cleanupPending."
                refreshRestoreInbox(preserveStatus = true)
            }
        }
    }

    private fun requestMediaAccess(category: MediaCategory) {
        if (mediaProvider.hasReadAccess(category)) {
            scanMedia(category)
        } else {
            pendingCategory = category
            mediaPermissionRequest.launch(mediaProvider.permissionsToRequest(category))
        }
    }

    private fun requestContactsAccess() {
        if (contactsProvider.hasReadAccess()) {
            scanContacts()
        } else {
            contactsPermissionRequest.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }

    private fun scanContacts() {
        contactsStatus = "Перевіряю та хешую контакти…"
        mediaExecutor.execute {
            try {
                var totalBytes = 0L
                var hashedCount = 0
                val manifestItems = ArrayList<ManifestItemSource>()
                val count = contactsProvider.forEachContact { item ->
                    val digest = contactsProvider.hashVCard(item)
                    totalBytes = totalBytes.saturatingAdd(digest.sizeBytes)
                    hashedCount += 1
                    manifestItems += ManifestItemSources.contact(contactsProvider, item)
                }
                val result = if (count == 0) {
                    "Контактів для експорту немає."
                } else {
                    "Знайдено $count контактів; повністю потоково хешовано $hashedCount vCard " +
                        "(${formatBytes(totalBytes)}). Вміст на диск не зберігався."
                }
                runOnUiThread {
                    rememberManifestSource(
                        ManifestCategorySource(
                            "contacts",
                            "android.provider.ContactsContract",
                            "CONTACTS.CONTENT_VCARD_URI",
                            manifestItems,
                        ),
                    )
                    contactsStatus = result
                }
            } catch (_: SecurityException) {
                runOnUiThread { contactsStatus = "Android не надав доступ до контактів." }
            } catch (_: IOException) {
                runOnUiThread { contactsStatus = "Не вдалося прочитати vCard контакту." }
            } catch (_: IllegalArgumentException) {
                runOnUiThread { contactsStatus = "Contacts Provider повернув некоректний запис." }
            }
        }
    }

    private fun requestCalendarAccess() {
        if (calendarProvider.hasReadAccess()) {
            scanCalendar()
        } else {
            calendarPermissionRequest.launch(android.Manifest.permission.READ_CALENDAR)
        }
    }

    private fun scanCalendar() {
        calendarStatus = "Перевіряю події, учасників і нагадування…"
        mediaExecutor.execute {
            try {
                var hashedCount = 0
                val manifestItems = ArrayList<ManifestItemSource>()
                val summary = calendarProvider.forEachEvent { item ->
                    item.sha256
                    hashedCount += 1
                    manifestItems += ManifestItemSources.calendar(item)
                }
                val result = if (summary.eventCount == 0) {
                    "Подій для експорту немає."
                } else {
                    "Знайдено ${summary.eventCount} подій; хешовано $hashedCount записів " +
                        "(${formatBytes(summary.totalBytes)}). " +
                        "${summary.eventsWithProviderExtensions} можуть мати поля sync-adapter, " +
                        "які не входять у portable export. Вміст на диск не зберігався."
                }
                runOnUiThread {
                    rememberManifestSource(
                        ManifestCategorySource(
                            "calendar",
                            "android.provider.CalendarContract",
                            "Events+Attendees+Reminders",
                            manifestItems,
                        ),
                    )
                    calendarStatus = result
                }
            } catch (_: SecurityException) {
                runOnUiThread { calendarStatus = "Android не надав доступ до календаря." }
            } catch (_: IOException) {
                runOnUiThread { calendarStatus = "Не вдалося прочитати повний запис календаря." }
            } catch (_: SQLException) {
                runOnUiThread { calendarStatus = "Calendar Provider повернув помилку під час читання." }
            } catch (_: IllegalArgumentException) {
                runOnUiThread { calendarStatus = "Calendar Provider повернув некоректний запис." }
            }
        }
    }

    private fun requestSmsAccess() {
        if (smsProvider.hasReadAccess()) {
            scanSms()
        } else {
            smsPermissionRequest.launch(android.Manifest.permission.READ_SMS)
        }
    }

    private fun scanSms() {
        smsStatus = "Перевіряю кількість SMS і хешую записи…"
        mediaExecutor.execute {
            try {
                var hashedCount = 0
                val manifestItems = ArrayList<ManifestItemSource>()
                val summary = smsProvider.forEachTextMessage { item ->
                    item.sha256
                    hashedCount += 1
                    manifestItems += ManifestItemSources.sms(item)
                }
                val result = "Знайдено ${summary.messageCount} SMS; хешовано $hashedCount записів " +
                    "(${formatBytes(summary.totalBytes)}). MMS та вкладення не включені."
                runOnUiThread {
                    rememberManifestSource(
                        ManifestCategorySource("sms", "android.provider.Telephony.Sms", "SMS.CONTENT_URI", manifestItems),
                    )
                    smsStatus = result
                }
            } catch (_: SecurityException) {
                runOnUiThread { smsStatus = "Доступ до SMS недоступний через обмеження дозволу Android." }
            } catch (_: IOException) {
                runOnUiThread { smsStatus = "Не вдалося прочитати повні записи SMS." }
            }
        }
    }

    private fun requestCallLogAccess() {
        if (callLogProvider.hasReadAccess()) {
            scanCallLog()
        } else {
            callLogPermissionRequest.launch(android.Manifest.permission.READ_CALL_LOG)
        }
    }

    private fun scanCallLog() {
        callLogStatus = "Перевіряю журнал і хешую записи…"
        mediaExecutor.execute {
            try {
                var hashedCount = 0
                val manifestItems = ArrayList<ManifestItemSource>()
                val summary = callLogProvider.forEachCall { item ->
                    item.sha256
                    hashedCount += 1
                    manifestItems += ManifestItemSources.callLog(item)
                }
                val result = "Знайдено ${summary.callCount} записів; хешовано $hashedCount " +
                    "(${formatBytes(summary.totalBytes)}). Номери й вміст не показуються."
                runOnUiThread {
                    rememberManifestSource(
                        ManifestCategorySource(
                            "call_log",
                            "android.provider.CallLog.Calls",
                            "Calls.CONTENT_URI",
                            manifestItems,
                        ),
                    )
                    callLogStatus = result
                }
            } catch (_: SecurityException) {
                runOnUiThread { callLogStatus = "Доступ до журналу дзвінків недоступний через обмеження Android." }
            } catch (_: IOException) {
                runOnUiThread { callLogStatus = "Не вдалося прочитати повні записи журналу дзвінків." }
            }
        }
    }

    private fun scanApks() {
        apkStatus = "Перевіряю доступні APK і потоково обчислюю SHA-256…"
        mediaExecutor.execute {
            try {
                val manifestItems = ArrayList<ManifestItemSource>()
                val summary = apkProvider.forEachAvailableApk { item ->
                    item.sha256
                    manifestItems += ManifestItemSources.apk(item)
                }
                val result = "Доступно ${summary.availableCount} одиночних APK " +
                    "(${formatBytes(summary.totalBytes)}); пропущено системних: " +
                    "${summary.systemPackagesSkipped}, split: ${summary.splitPackagesSkipped}, " +
                    "недоступних: ${summary.unavailablePackagesSkipped}. " +
                    "Назви пакетів не показуються."
                runOnUiThread {
                    rememberManifestSource(
                        ManifestCategorySource("apks", "android.content.pm.PackageManager", "ApplicationInfo.sourceDir", manifestItems),
                    )
                    apkStatus = result
                }
            } catch (_: SecurityException) {
                runOnUiThread { apkStatus = "Android не надав видимість списку встановлених пакетів." }
            } catch (_: IOException) {
                runOnUiThread { apkStatus = "Не вдалося прочитати доступні APK." }
            }
        }
    }

    private fun scanSettings() {
        settingsStatus = "Перевіряю allowlist системних налаштувань read-only…"
        mediaExecutor.execute {
            try {
                val result = settingsProvider.scanReadOnly()
                val access = result.access
                val shizukuStatus = when {
                    !access.shizukuServiceAvailable -> "Shizuku service не підключений"
                    !access.shizukuPermissionGranted -> "Shizuku permission не надано"
                    else -> "Shizuku доступ підтверджений, але elevated settings операції ще не реалізовані"
                }
                val ownerStatus = if (access.deviceOwner) "Device Owner активний" else "Device Owner відсутній"
                val summary = "Прочитано ${access.publicSettingsRead}/${access.publicSettingsTotal} " +
                    "публічних ключів Settings.System; WRITE_SETTINGS approval: " +
                    "${if (access.systemWriteApproved) "є" else "немає"}; $shizukuStatus; $ownerStatus. " +
                    "Settings.Secure/Global і будь-які зміни тут не виконуються."
                runOnUiThread {
                    rememberManifestSource(
                        ManifestCategorySource(
                            "settings",
                            "android.provider.Settings.System",
                            "public_allowlist_v1",
                            listOf(ManifestItemSources.settings(result.item)),
                        ),
                    )
                    settingsStatus = summary
                }
                // Keep payload only for this operation; UI receives aggregate status, never values.
                result.item.sha256
            } catch (_: SecurityException) {
                runOnUiThread { settingsStatus = "Android заборонив читання частини налаштувань." }
            }
        }
    }

    private fun scanMedia(category: MediaCategory) {
        mediaStatus = "Перевіряю категорію ${category.contractId}…"
        mediaExecutor.execute {
            try {
                var totalBytes = 0L
                var unknownSizes = 0
                var firstStreamOpened = false
                val manifestItems = ArrayList<ManifestItemSource>()
                val count = mediaProvider.forEachMedia(category) { item ->
                    val size = item.sizeBytes
                    if (size == null) unknownSizes += 1 else totalBytes = totalBytes.saturatingAdd(size)
                    manifestItems += ManifestItemSources.media(mediaProvider, item)
                    if (!firstStreamOpened) {
                        mediaProvider.openStream(item).use { stream ->
                            stream.read(ByteArray(16 * 1024))
                        }
                        firstStreamOpened = true
                    }
                }
                val result = if (count == 0) {
                    "${category.contractId}: доступних елементів немає."
                } else {
                    "${category.contractId}: $count елементів, " +
                        "відомий розмір ${formatBytes(totalBytes)}, " +
                        "$unknownSizes без розміру; потік першого елемента відкрито."
                }
                runOnUiThread {
                    rememberManifestSource(
                        ManifestCategorySource(
                            category.contractId,
                            "android.provider.MediaStore",
                            "MediaStore.${category.contractId}",
                            manifestItems,
                        ),
                    )
                    mediaStatus = result
                }
            } catch (_: SecurityException) {
                runOnUiThread { mediaStatus = "Android не надав доступ до вибраної категорії." }
            } catch (_: IOException) {
                runOnUiThread { mediaStatus = "Не вдалося потоково відкрити елемент категорії." }
            }
        }
    }

    private fun scanSelectedDocuments(uris: List<android.net.Uri>) {
        mediaStatus = "Перевіряю вибрані документи…"
        mediaExecutor.execute {
            try {
                var totalBytes = 0L
                var unknownSizes = 0
                uris.forEach(mediaProvider::persistReadGrant)
                val items = uris.map(mediaProvider::describeSelectedDocument)
                items.forEach { item ->
                    val size = item.sizeBytes
                    if (size == null) unknownSizes += 1 else totalBytes = totalBytes.saturatingAdd(size)
                }
                items.firstOrNull()?.let { item ->
                    mediaProvider.openStream(item).use { stream -> stream.read(ByteArray(16 * 1024)) }
                }
                val counts = items.groupingBy { it.categoryId }.eachCount()
                    .entries.joinToString { "${it.key}: ${it.value}" }
                val result = "Вибрано ${items.size} файлів ($counts), " +
                    "відомий розмір ${formatBytes(totalBytes)}, " +
                    "$unknownSizes без розміру; потік відкрито."
                val manifestCategories = items.groupBy { it.categoryId }.map { (categoryId, categoryItems) ->
                    ManifestCategorySource(
                        categoryId,
                        "android.provider.DocumentsContract",
                        "ACTION_OPEN_DOCUMENT",
                        categoryItems.map { item -> ManifestItemSources.media(mediaProvider, item) },
                    )
                }
                runOnUiThread {
                    manifestCategories.forEach(::rememberManifestSource)
                    mediaStatus = result
                }
            } catch (_: SecurityException) {
                runOnUiThread { mediaStatus = "SAF URI більше не доступний; вибери файл знову." }
            } catch (_: IOException) {
                runOnUiThread { mediaStatus = "Не вдалося потоково відкрити вибраний документ." }
            }
        }
    }

    /** A successfully completed provider scan becomes an explicit source-selection entry. */
    private fun rememberManifestSource(source: ManifestCategorySource) {
        selectedManifestSources[source.categoryId] = source
        manifestSelectionVersion += 1
        manifestSelectionCount = selectedManifestSources.size
        val cleaned = discardPreparedManifest()
        manifestStatus = if (cleaned) {
            "Вибір оновлено. Підготовлені категорії: ${selectedManifestSources.size}."
        } else {
            "Вибір оновлено, але попередній тимчасовий snapshot не вдалося очистити."
        }
    }

    private fun buildManifest() {
        if (manifestBuildInProgress) return
        val categories = selectedManifestSources.values.toList()
        if (categories.isEmpty()) {
            manifestStatus = "Спершу перевір одну або кілька категорій."
            return
        }

        val versionAtStart = manifestSelectionVersion
        manifestBuildInProgress = true
        manifestStatus = "Хешую вибрані джерела та готую schema-shaped маніфест…"
        mediaExecutor.execute {
            var createdBundle: TransferManifestBundle? = null
            try {
                val bundle = ManifestBuilder(applicationContext).build(sourceDeviceInfo, categories)
                createdBundle = bundle
                val itemCount = bundle.categories.sumOf { it.items.size }
                val totalBytes = bundle.categories.flatMap { it.items }
                    .fold(0L) { total, item -> total.saturatingAdd(item.sizeBytes) }
                val failureCount = bundle.failures.size
                runOnUiThread {
                    if (versionAtStart != manifestSelectionVersion || isDestroyed) {
                        runCatching { bundle.close() }
                        manifestBuildInProgress = false
                        manifestStatus = "Вибір змінився під час підготовки; перевір його й запусти знову."
                        return@runOnUiThread
                    }
                    if (!discardPreparedManifest()) {
                        runCatching { bundle.close() }
                        manifestBuildInProgress = false
                        manifestStatus = "Не вдалося очистити попередній тимчасовий snapshot."
                        return@runOnUiThread
                    }
                    preparedManifestBundle = bundle
                    manifestBuildInProgress = false
                    manifestStatus = "Готово: ${bundle.categories.size} категорій, $itemCount items, " +
                        "${formatBytes(totalBytes)}; проблемних items: $failureCount. Передавання не почато."
                }
            } catch (_: Exception) {
                createdBundle?.let { runCatching { it.close() } }
                runOnUiThread {
                    manifestBuildInProgress = false
                    manifestStatus = "Не вдалося підготувати маніфест; перевір джерела й доступи."
                }
            }
        }
    }

    private fun clearManifestSelection() {
        if (manifestBuildInProgress) return
        selectedManifestSources.clear()
        manifestSelectionVersion += 1
        manifestSelectionCount = 0
        manifestStatus = if (discardPreparedManifest()) {
            "Вибір очищено; TransferKit видалив власний тимчасовий snapshot."
        } else {
            "Вибір очищено, але тимчасовий snapshot не вдалося видалити."
        }
    }

    private fun discardPreparedManifest(): Boolean {
        val bundle = preparedManifestBundle ?: return true
        preparedManifestBundle = null
        return runCatching { bundle.close() }.isSuccess
    }

    override fun onDestroy() {
        mediaExecutor.shutdownNow()
        discardPreparedManifest()
        runCatching { transferReceiver.close() }
        super.onDestroy()
    }

    private fun formatBytes(bytes: Long): String =
        String.format(java.util.Locale.getDefault(), "%.1f MiB", bytes / (1024.0 * 1024))
}

private fun Long.saturatingAdd(value: Long): Long =
    if (value > Long.MAX_VALUE - this) Long.MAX_VALUE else this + value
