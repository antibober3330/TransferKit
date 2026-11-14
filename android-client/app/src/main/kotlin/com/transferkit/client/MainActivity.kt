package com.transferkit.client

import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.database.SQLException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.Executors
import com.transferkit.client.receiver.AndroidTransferReceiver
import com.transferkit.client.receiver.ReceivedTransferSummary
import com.transferkit.client.receiver.StagingLeftover
import com.transferkit.client.restore.LegacyMediaRestorer
import com.transferkit.client.restore.MediaStoreRestorer
import com.transferkit.client.restore.ContactsRestorer
import com.transferkit.client.restore.CalendarRestorer
import com.transferkit.client.restore.SettingsSystemRestorer
import com.transferkit.client.restore.CalendarDestination
import com.transferkit.client.restore.CalendarDestinationDialog
import com.transferkit.client.restore.SafDocumentRestorer
import com.transferkit.client.restore.SafDocumentTreeAccess
import com.transferkit.client.restore.SafDocumentTreeSelection
import com.transferkit.client.restore.RestoreApproval
import com.transferkit.client.restore.RestoreApprovalGate
import com.transferkit.client.restore.RestoreItemStatus
import com.transferkit.client.restore.RestorerRegistry
import com.transferkit.client.restore.RestoreContext
import com.transferkit.client.restore.RestoreConfirmationDialog
import com.transferkit.client.restore.StagingRestorePipeline
import com.transferkit.client.restore.VerifiedRestoreItem
import com.transferkit.client.restore.isFileMediaRestoreCategory
import com.transferkit.client.restore.ApkInstallApproval
import com.transferkit.client.restore.ApkInstallCoordinator
import com.transferkit.client.restore.ApkInstallStatusReceiver
import com.transferkit.client.restore.ApkInstallTicket
import com.transferkit.client.restore.ApkPreparationResult
import com.transferkit.client.restore.ApkRestoreReviewDialog
import com.transferkit.client.restore.ApkRestoreViewModel
import com.transferkit.client.restore.ApkInstallConfirmationDialog
import com.transferkit.client.shizuku.ShizukuBridge
import com.transferkit.client.shizuku.shizukuPermissionRequestMessage
import com.transferkit.client.shizuku.shizukuPermissionResultMessage
import rikka.shizuku.Shizuku

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
    private lateinit var apkInstallCoordinator: ApkInstallCoordinator
    private val apkRestoreViewModel: ApkRestoreViewModel by viewModels()
    private val shizukuBridge = ShizukuBridge()
    private var pendingCategory: MediaCategory? = null
    private var pendingOriginalPhotoMetadata = false
    private var pendingRestoreReviewTransferId: String? = null
    private var pendingContactRestoreTransferId: String? = null
    private var pendingCalendarRestoreTransferId: String? = null
    private var pendingSettingsRestoreTransferId: String? = null
    private val selectedManifestSources = linkedMapOf<String, ManifestCategorySource>()
    private lateinit var sourceDeviceInfo: DeviceInfo
    private var preparedManifestBundle: TransferManifestBundle? = null
    private var manifestSelectionVersion = 0L
    private var manifestSelectionCount by mutableStateOf(0)
    private var manifestBuildInProgress by mutableStateOf(false)
    private var showRestoreInbox by mutableStateOf(false)
    private var showTransferProgress by mutableStateOf(false)
    private var adbReceiverStatus by mutableStateOf(TransferForegroundService.STATUS_STOPPED)
    private var adbSourceStatus by mutableStateOf(TransferForegroundService.STATUS_STOPPED)
    private var restoreInboxLoading by mutableStateOf(false)
    private var restoreInProgress by mutableStateOf(false)
    private var restoreInboxStatus by mutableStateOf("Список отриманих передач ще не оновлено.")
    private var receivedTransfers by mutableStateOf<List<ReceivedTransferSummary>>(emptyList())
    private var stagingLeftovers by mutableStateOf<List<StagingLeftover>>(emptyList())
    private var selectedRestoreItems by mutableStateOf<List<VerifiedRestoreItem>>(emptyList())
    private var pendingCalendarItems by mutableStateOf<List<VerifiedRestoreItem>>(emptyList())
    private var pendingDocumentRestoreItems = emptyList<VerifiedRestoreItem>()
    private var calendarDestinations by mutableStateOf<List<CalendarDestination>>(emptyList())
    private var selectedCalendarDestination: CalendarDestination? = null
    private var selectedDocumentTreeSelection: SafDocumentTreeSelection? = null
    private var documentDestinationName by mutableStateOf<String?>(null)
    private var selectedApkItems by mutableStateOf<List<VerifiedRestoreItem>>(emptyList())
    private var selectedApkItem by mutableStateOf<VerifiedRestoreItem?>(null)
    private var apkResultReceiverRegistered = false
    private var manifestStatus by mutableStateOf("Маніфест ще не підготовлено.")
    private var mediaStatus by mutableStateOf("Медіатеку ще не перевірено.")
    private var contactsStatus by mutableStateOf("Контакти ще не перевірено.")
    private var calendarStatus by mutableStateOf("Календар ще не перевірено.")
    private var smsStatus by mutableStateOf("SMS ще не перевірено.")
    private var callLogStatus by mutableStateOf("Журнал дзвінків ще не перевірено.")
    private var apkStatus by mutableStateOf("Застосунки ще не перевірено.")
    private var settingsStatus by mutableStateOf("Налаштування ще не перевірено.")
    private var shizukuStatus by mutableStateOf("Shizuku не запитувався.")

    private val apkInstallResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            restoreInboxStatus = ApkInstallStatusReceiver.takePendingResult(context)
                ?: intent.getStringExtra(ApkInstallStatusReceiver.EXTRA_RESULT_TEXT)
                ?: "Android завершив дію з APK."
            refreshRestoreInbox(preserveStatus = true)
        }
    }

    private val apkInstallSettingsRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val item = apkRestoreViewModel.pendingItem
        val approval = apkRestoreViewModel.pendingApproval
        if (item == null || approval == null) return@registerForActivityResult
        if (packageManager.canRequestPackageInstalls()) {
            prepareAndInstallApk(item, approval)
        } else {
            apkRestoreViewModel.approvalGate.revoke(approval)
            apkRestoreViewModel.pendingApproval = null
            apkRestoreViewModel.pendingItem = null
            restoreInboxStatus = "Дозвіл на встановлення APK не надано; дані передачі не змінено."
        }
    }

    private val shizukuPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            shizukuStatus = shizukuPermissionResultMessage(
                granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
            scanSettings()
        }
    }

    private val mediaPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val category = pendingCategory
        val includeOriginalPhotoMetadata = pendingOriginalPhotoMetadata
        pendingCategory = null
        pendingOriginalPhotoMetadata = false
        if (category != null && mediaProvider.hasReadAccess(category)) {
            if (
                MediaOriginalAccessPolicy.requiresMediaLocationPermission(
                    category,
                    Build.VERSION.SDK_INT,
                    includeOriginalPhotoMetadata,
                ) && !mediaProvider.hasMediaLocationAccess()
            ) {
                mediaStatus = "Доступ до оригінальних EXIF не надано; фото не сканувалися. " +
                    "Дозволь його в налаштуваннях Android або натисни «Перевірити фото» для звичайного доступу."
            } else {
                scanMedia(category, includeOriginalPhotoMetadata)
            }
        } else if (category != null) {
            mediaStatus = "Доступ до категорії ${category.contractId} не надано."
        }
    }

    private val mediaLocationPermissionRequest = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            scanMedia(MediaCategory.PHOTOS, includeOriginalPhotoMetadata = true)
        } else {
            mediaStatus = "Доступ до оригінальних EXIF не надано; фото не сканувалися. " +
                "Дозволь його в налаштуваннях Android або натисни «Перевірити фото» для звичайного доступу."
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

    private val restoreDocumentsTreePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val items = pendingDocumentRestoreItems
        pendingDocumentRestoreItems = emptyList()
        if (items.isEmpty()) return@registerForActivityResult
        if (uri == null) {
            selectedCalendarDestination = null
            restoreInboxStatus = "Вибір папки скасовано; документи не змінено."
            return@registerForActivityResult
        }
        releaseSelectedDocumentDestination()
        restoreInboxLoading = true
        restoreInboxStatus = "Перевіряю доступ і можливість запису у вибрану папку…"
        mediaExecutor.execute {
            val acquired = runCatching { SafDocumentTreeAccess.acquire(applicationContext, uri) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                restoreInboxLoading = false
                acquired.onSuccess { selection ->
                    selectedDocumentTreeSelection = selection
                    documentDestinationName = selection.displayName
                    selectedRestoreItems = items
                    restoreInboxStatus = "Папку призначення вибрано. Переглянь склад передачі й підтвердь відновлення."
                }.onFailure {
                    selectedCalendarDestination = null
                    restoreInboxStatus = "Ця папка не дозволяє безпечно створювати та публікувати документи; дані не змінено. Вибери іншу папку."
                }
            }
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

    private val legacyRestorePermissionRequest = registerForActivityResult(RequestPermission()) { granted ->
        val transferId = pendingRestoreReviewTransferId
        pendingRestoreReviewTransferId = null
        if (granted && transferId != null &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prepareRestoreReview(transferId)
        } else if (transferId != null) {
            restoreInboxStatus = "Дозвіл на запис публічних медіафайлів не надано; дані не змінено."
        }
    }

    private val contactsRestorePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val transferId = pendingContactRestoreTransferId
        pendingContactRestoreTransferId = null
        if (transferId != null &&
            checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prepareRestoreReview(transferId)
        } else if (transferId != null) {
            restoreInboxStatus = "Дозволи на читання та запис контактів не надано; контакти не змінено."
        }
    }

    private val calendarRestorePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val transferId = pendingCalendarRestoreTransferId
        pendingCalendarRestoreTransferId = null
        if (transferId != null &&
            checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prepareRestoreReview(transferId)
        } else if (transferId != null) {
            restoreInboxStatus = "Дозволи на читання та запис календаря не надано; події не змінено."
        }
    }

    private val settingsWriteAccessRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val transferId = pendingSettingsRestoreTransferId
        pendingSettingsRestoreTransferId = null
        if (transferId == null) return@registerForActivityResult
        if (Settings.System.canWrite(this)) {
            prepareRestoreReview(transferId)
        } else {
            restoreInboxStatus = "Дозвіл на зміну дозволених системних параметрів не надано; налаштування не змінено."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        mediaProvider = MediaProvider(applicationContext)
        contactsProvider = ContactsProvider(applicationContext)
        calendarProvider = CalendarProvider(applicationContext)
        smsProvider = SmsProvider(applicationContext)
        callLogProvider = CallLogProvider(applicationContext)
        apkProvider = ApkProvider(applicationContext)
        settingsProvider = SettingsProvider(applicationContext)
        transferReceiver = AndroidTransferReceiver(applicationContext)
        apkInstallCoordinator = ApkInstallCoordinator(applicationContext, apkRestoreViewModel.approvalGate)
        adbReceiverStatus = TransferForegroundService.receiverStatus(this)
        adbSourceStatus = TransferForegroundService.sourceStatus(this)
        val mediaRestorer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreRestorer(applicationContext, restoreApprovalGate)
        } else {
            LegacyMediaRestorer(applicationContext, restoreApprovalGate)
        }
        restorerRegistry = RestorerRegistry(
            listOf(
                mediaRestorer,
                SafDocumentRestorer(applicationContext, restoreApprovalGate),
                ContactsRestorer(applicationContext, restoreApprovalGate),
                CalendarRestorer(applicationContext, restoreApprovalGate),
                SettingsSystemRestorer(applicationContext, restoreApprovalGate),
            ),
        )
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
                        stagingLeftovers = stagingLeftovers,
                        onBack = {
                            selectedRestoreItems = emptyList()
                            pendingCalendarItems = emptyList()
                            calendarDestinations = emptyList()
                            selectedCalendarDestination = null
                            releaseSelectedDocumentDestination()
                            showRestoreInbox = false
                        },
                        onRefresh = { refreshRestoreInbox() },
                        onReview = ::requestRestoreReview,
                        onReviewApks = ::prepareApkReview,
                        onCleanupStaging = ::cleanupStagingCandidate,
                    )
                    if (pendingCalendarItems.isNotEmpty() && calendarDestinations.isNotEmpty()) {
                        CalendarDestinationDialog(
                            destinations = calendarDestinations,
                            onSelected = { destinationId ->
                                selectedCalendarDestination = calendarDestinations.firstOrNull { it.id == destinationId }
                                val items = pendingCalendarItems
                                pendingCalendarItems = emptyList()
                                calendarDestinations = emptyList()
                                prepareRestoreConfirmation(items)
                            },
                            onDismiss = {
                                pendingCalendarItems = emptyList()
                                calendarDestinations = emptyList()
                                selectedCalendarDestination = null
                                restoreInboxStatus = "Вибір календаря скасовано; жодної події не записано."
                            },
                        )
                    }
                    if (selectedRestoreItems.isNotEmpty()) {
                        RestoreConfirmationDialog(
                            transferId = selectedRestoreItems.first().transferId,
                            manifestSha256 = selectedRestoreItems.first().manifestSha256,
                            items = selectedRestoreItems,
                            calendarDestination = selectedCalendarDestination,
                            documentDestinationName = documentDestinationName,
                            approvalGate = restoreApprovalGate,
                            onApproved = ::beginRestore,
                            onDismiss = {
                                selectedRestoreItems = emptyList()
                                selectedCalendarDestination = null
                                releaseSelectedDocumentDestination()
                            },
                        )
                    }
                    if (selectedApkItems.isNotEmpty()) {
                        ApkRestoreReviewDialog(
                            items = selectedApkItems,
                            onInstall = { item ->
                                selectedApkItems = emptyList()
                                selectedApkItem = item
                            },
                            onDismiss = { selectedApkItems = emptyList() },
                        )
                    }
                    selectedApkItem?.let { item ->
                        ApkInstallConfirmationDialog(
                            item = item,
                            approvalGate = apkRestoreViewModel.approvalGate,
                            onApproved = { approval ->
                                selectedApkItem = null
                                apkRestoreViewModel.pendingItem = item
                                apkRestoreViewModel.pendingApproval = approval
                                prepareAndInstallApk(item, approval)
                            },
                            onDismiss = { selectedApkItem = null },
                        )
                    }
                } else if (showTransferProgress) {
                    TransferProgressScreen(
                        onBack = { showTransferProgress = false },
                        onPause = {},
                        onCancel = {},
                    )
                } else {
                    DeviceInfoScreen(
                        deviceInfo = deviceInfo,
                        onOpenRestoreInbox = {
                            showRestoreInbox = true
                            refreshRestoreInbox()
                        },
                        onOpenTransferProgress = { showTransferProgress = true },
                        mediaStatus = mediaStatus,
                        onScanMedia = ::requestMediaAccess,
                        onScanPhotosWithOriginalMetadata = ::requestPhotosWithOriginalMetadata,
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
                        shizukuStatus = shizukuStatus,
                        onRequestShizukuPermission = ::requestShizukuPermission,
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

    override fun onStart() {
        super.onStart()
        if (!apkResultReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                apkInstallResultReceiver,
                IntentFilter(ApkInstallStatusReceiver.ACTION_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            apkResultReceiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        adbReceiverStatus = TransferForegroundService.receiverStatus(this)
        adbSourceStatus = TransferForegroundService.sourceStatus(this)
        ApkInstallStatusReceiver.takePendingResult(this)?.let { restoreInboxStatus = it }
        if (showRestoreInbox) refreshRestoreInbox(preserveStatus = true)
    }

    override fun onStop() {
        if (apkResultReceiverRegistered) {
            runCatching { unregisterReceiver(apkInstallResultReceiver) }
            apkResultReceiverRegistered = false
        }
        super.onStop()
    }

    private fun requestShizukuPermission() {
        shizukuStatus = shizukuPermissionRequestMessage(
            shizukuBridge.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE),
        )
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
            val result = runCatching {
                transferReceiver.availableRestoreTransfers() to transferReceiver.stagingLeftovers()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                restoreInboxLoading = false
                val snapshot = result.getOrNull()
                receivedTransfers = snapshot?.first ?: emptyList()
                stagingLeftovers = snapshot?.second ?: emptyList()
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

    private fun cleanupStagingCandidate(transferId: String, displayedBytes: Long) {
        if (restoreInboxLoading || restoreInProgress) return
        restoreInboxLoading = true
        restoreInboxStatus = "Перевіряю підтверджений staging перед очищенням…"
        mediaExecutor.execute {
            val result = runCatching {
                transferReceiver.cleanupStagingLeftoverAfterConfirmation(
                    transferId = transferId,
                    displayedBytes = displayedBytes,
                    confirmedByUser = true,
                )
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                restoreInboxLoading = false
                restoreInboxStatus = if (result.getOrDefault(false)) {
                    "Тимчасові дані TransferKit очищено. Відновлені файли не змінювалися."
                } else {
                    "Не очищено: стан або розмір staging змінився, чи передача захищена. Онови список."
                }
                refreshRestoreInbox(preserveStatus = true)
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
                    it.categoryId in restorerRegistry.supportedCategoryIds
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                restoreInboxLoading = false
                val items = result.getOrDefault(emptyList())
                if (result.isSuccess && items.any { isFileMediaRestoreCategory(it.categoryId) } &&
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    pendingRestoreReviewTransferId = transferId
                    restoreInboxStatus = "Для запису медіафайлів у публічні папки Android 8–9 потрібен дозвіл на сховище. Документи через SAF його не потребують."
                    legacyRestorePermissionRequest.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return@runOnUiThread
                }
                if (result.isSuccess && items.any { it.categoryId == "contacts" } &&
                    (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED)
                ) {
                    pendingContactRestoreTransferId = transferId
                    restoreInboxStatus = "Для запису та перевірки контактів потрібні дозволи читання й запису; запитую їх перед підтвердженням відновлення."
                    contactsRestorePermissionRequest.launch(
                        arrayOf(
                            android.Manifest.permission.READ_CONTACTS,
                            android.Manifest.permission.WRITE_CONTACTS,
                        ),
                    )
                    return@runOnUiThread
                }
                if (result.isSuccess && items.any { it.categoryId == "calendar" } &&
                    (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED)
                ) {
                    pendingCalendarRestoreTransferId = transferId
                    restoreInboxStatus = "Для відновлення календаря потрібні дозволи читання й запису; запитую їх перед вибором календаря."
                    calendarRestorePermissionRequest.launch(
                        arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR),
                    )
                    return@runOnUiThread
                }
                if (result.isSuccess && items.any { it.categoryId == "settings" } &&
                    !Settings.System.canWrite(this)
                ) {
                    pendingSettingsRestoreTransferId = transferId
                    restoreInboxStatus = "Для відновлення вибраних публічних системних параметрів потрібне окреме підтвердження Android. Захищені Secure/Global налаштування не змінюються."
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                    runCatching { settingsWriteAccessRequest.launch(intent) }
                        .onFailure {
                            pendingSettingsRestoreTransferId = null
                            restoreInboxStatus = "Не вдалося відкрити системний дозвіл; налаштування не змінено."
                        }
                    return@runOnUiThread
                }
                if (result.isSuccess && items.any { it.categoryId == "calendar" }) {
                    loadCalendarDestinations(items)
                    return@runOnUiThread
                }
                when {
                    result.isFailure -> restoreInboxStatus = "Не вдалося відкрити перевірені items передачі."
                    items.isEmpty() -> restoreInboxStatus = "У передачі немає перевірених файлів, медіа, контактів, календарних подій або підтримуваних системних параметрів для цього Android API."
                    else -> {
                        restoreInboxStatus = "Підготовлено для перегляду й підтвердження: ${items.size} елементів. Запис ще не почався."
                        prepareRestoreConfirmation(items)
                    }
                }
            }
        }
    }

    private fun loadCalendarDestinations(items: List<VerifiedRestoreItem>) {
        restoreInboxLoading = true
        restoreInboxStatus = "Шукаю календарі, у які цьому застосунку дозволено запис…"
        mediaExecutor.execute {
            val result = runCatching { CalendarRestorer.listWritableCalendars(applicationContext) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                restoreInboxLoading = false
                val destinations = result.getOrDefault(emptyList())
                if (result.isFailure || destinations.isEmpty()) {
                    restoreInboxStatus = if (result.isFailure) {
                        "Не вдалося прочитати доступні календарі; жодної події не записано."
                    } else {
                        "Немає календаря з дозволом на запис. Створи або увімкни календар призначення й повтори спробу."
                    }
                } else {
                    pendingCalendarItems = items
                    calendarDestinations = destinations
                    restoreInboxStatus = "Вибери календар призначення. Передача почнеться лише після окремого підтвердження."
                }
            }
        }
    }

    private fun prepareApkReview(transferId: String) {
        if (restoreInboxLoading || restoreInProgress) return
        restoreInboxLoading = true
        restoreInboxStatus = "Перевіряю отримані APK перед переглядом…"
        mediaExecutor.execute {
            val result = runCatching {
                transferReceiver.verifiedRestoreItems(transferId).filter { it.categoryId == "apks" }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                restoreInboxLoading = false
                val items = result.getOrDefault(emptyList())
                selectedApkItems = items
                restoreInboxStatus = when {
                    result.isFailure -> "Не вдалося відкрити перевірені APK цієї передачі."
                    items.isEmpty() -> "У передачі немає перевірених APK для встановлення."
                    else -> "Знайдено перевірених APK: ${items.size}. Нічого не встановлено."
                }
            }
        }
    }

    private fun prepareAndInstallApk(item: VerifiedRestoreItem, approval: ApkInstallApproval) {
        if (restoreInProgress || isDestroyed) {
            apkRestoreViewModel.approvalGate.revoke(approval)
            return
        }
        restoreInProgress = true
        restoreInboxStatus = "Повторно перевіряю SHA-256 APK перед створенням install session…"
        mediaExecutor.execute {
            val result = runCatching { apkInstallCoordinator.prepare(item, approval) }
                .getOrElse {
                    ApkPreparationResult.Failed(
                        com.transferkit.client.restore.RestoreItemResult(
                            itemId = item.itemId,
                            status = RestoreItemStatus.FAILED,
                            errorReason = "RESTORE_FAILED",
                        ),
                    )
                }
            runOnUiThread {
                if (isDestroyed) {
                    apkRestoreViewModel.approvalGate.revoke(approval)
                    return@runOnUiThread
                }
                restoreInProgress = false
                when (result) {
                    is ApkPreparationResult.Ready -> commitApkInstall(result.ticket)
                    is ApkPreparationResult.NeedsUserAction -> requestUnknownAppInstallPermission(item, approval)
                    is ApkPreparationResult.Failed -> {
                        apkRestoreViewModel.approvalGate.revoke(approval)
                        apkRestoreViewModel.pendingApproval = null
                        apkRestoreViewModel.pendingItem = null
                        restoreInboxStatus = when (result.result.errorReason) {
                            "SHA256_MISMATCH" -> "APK не пройшов перевірку SHA-256; джерело не встановлено."
                            "INVALID_MANIFEST" -> "Метадані APK некоректні; файл не встановлено."
                            "PERMISSION_DENIED" -> "Android відхилив підготовку APK; дані передачі збережені."
                            "USER_DECLINED" -> "Потрібне повторне підтвердження саме цього APK."
                            else -> "Не вдалося підготувати APK (${result.result.errorReason ?: "RESTORE_FAILED"}); дані передачі збережені."
                        }
                    }
                }
            }
        }
    }

    private fun requestUnknownAppInstallPermission(item: VerifiedRestoreItem, approval: ApkInstallApproval) {
        val targetPackageName = item.metadata["package_name"] as? String
        if (targetPackageName.isNullOrBlank()) {
            apkRestoreViewModel.approvalGate.revoke(approval)
            apkRestoreViewModel.pendingApproval = null
            apkRestoreViewModel.pendingItem = null
            restoreInboxStatus = "Не вдалося визначити пакет; нічого не встановлено."
            return
        }
        apkRestoreViewModel.pendingItem = item
        apkRestoreViewModel.pendingApproval = approval
        restoreInboxStatus = "Дозволь TransferKit встановлювати APK у системних налаштуваннях; підтвердження цього APK збережене на 5 хвилин."
        val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${this.packageName}"))
        runCatching { apkInstallSettingsRequest.launch(settingsIntent) }
            .onFailure {
                apkRestoreViewModel.approvalGate.revoke(approval)
                apkRestoreViewModel.pendingApproval = null
                apkRestoreViewModel.pendingItem = null
                restoreInboxStatus = "Не вдалося відкрити системний дозвіл; нічого не встановлено."
            }
    }

    private fun commitApkInstall(ticket: ApkInstallTicket) {
        apkRestoreViewModel.pendingItem = null
        apkRestoreViewModel.pendingApproval = null
        restoreInProgress = true
        restoreInboxStatus = "Передаю один перевірений APK до Android PackageInstaller; Android може попросити окреме підтвердження."
        val statusPendingIntent = ApkInstallStatusReceiver.createStatusPendingIntent(this, ticket)
        mediaExecutor.execute {
            val committed = runCatching {
                apkInstallCoordinator.commit(ticket, statusPendingIntent.intentSender)
            }
            if (committed.isFailure) {
                apkInstallCoordinator.abandon(ticket)
                statusPendingIntent.cancel()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                restoreInProgress = false
                restoreInboxStatus = if (committed.isSuccess) {
                    "Android отримав один APK. Завершення буде показане після системного install callback і перевірки версії."
                } else {
                    "Не вдалося передати APK до PackageInstaller; отриманий файл збережено для повторної спроби."
                }
            }
        }
    }

    private fun requestRestoreReview(transferId: String) {
        prepareRestoreReview(transferId)
    }

    private fun prepareRestoreConfirmation(items: List<VerifiedRestoreItem>) {
        if (items.any { it.categoryId == "documents" }) {
            pendingDocumentRestoreItems = items
            restoreInboxLoading = true
            restoreInboxStatus = "Перевіряю збережений доступ до папки або відкрию системний вибір папки…"
            mediaExecutor.execute {
                val saved = SafDocumentTreeAccess.restoreSaved(applicationContext)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    restoreInboxLoading = false
                    if (saved != null) {
                        selectedDocumentTreeSelection = saved
                        documentDestinationName = saved.displayName
                        selectedRestoreItems = items
                        restoreInboxStatus = "Збережений доступ до папки дійсний. Переглянь склад передачі й підтвердь відновлення."
                        return@runOnUiThread
                    }
                    restoreInboxStatus = "Вибери папку, куди зберегти документи. Дані не зміняться до підтвердження."
                    runCatching { restoreDocumentsTreePicker.launch(null) }
                        .onFailure {
                            pendingDocumentRestoreItems = emptyList()
                            restoreInboxStatus = "Не вдалося відкрити системний вибір папки; дані не змінено."
                        }
                }
            }
        } else {
            selectedRestoreItems = items
        }
    }

    private fun releaseSelectedDocumentDestination() {
        SafDocumentTreeAccess.releaseIfOwned(applicationContext, selectedDocumentTreeSelection)
        selectedDocumentTreeSelection = null
        documentDestinationName = null
    }

    private fun beginRestore(approval: RestoreApproval) {
        val items = selectedRestoreItems
        val calendarDestinationId = selectedCalendarDestination?.id
        val documentSelection = selectedDocumentTreeSelection
        selectedRestoreItems = emptyList()
        if (items.isEmpty()) {
            restoreApprovalGate.revoke(approval)
            selectedCalendarDestination = null
            releaseSelectedDocumentDestination()
            return
        }
        restoreInProgress = true
        restoreInboxStatus = "Відновлюю вибрані дані та перевіряю результат…"
        mediaExecutor.execute {
            val orderedItems = CalendarRestorer.parentFirstCalendarOrder(items)
            var cleanupPending = 0
            val results = try {
                orderedItems.map { item ->
                    val outcome = StagingRestorePipeline.restoreOne(
                        item = item,
                        restore = { approvedItem ->
                            restorerRegistry.restore(
                                approvedItem,
                                approval,
                                RestoreContext(
                                    calendarDestinationId = calendarDestinationId,
                                    documentTreeUri = documentSelection?.uri,
                                ),
                            )
                        },
                        releaseAfterPostVerification = transferReceiver::recordPostVerifiedRestore,
                    )
                    // Release only this item's app-private staging payload immediately after its
                    // adapter has reported destination post-verification. A later item failure
                    // must not keep already-verified payloads occupying the staging volume.
                    if (outcome.stagingCleanupPending) cleanupPending++
                    outcome.result
                }
            } finally {
                restoreApprovalGate.revoke(approval)
            SafDocumentTreeAccess.releaseIfOwned(applicationContext, documentSelection)
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
                selectedDocumentTreeSelection = null
                documentDestinationName = null
            }
        }
        selectedCalendarDestination = null
    }

    private fun requestMediaAccess(category: MediaCategory) {
        requestMediaAccess(category, includeOriginalPhotoMetadata = false)
    }

    private fun requestPhotosWithOriginalMetadata() {
        requestMediaAccess(MediaCategory.PHOTOS, includeOriginalPhotoMetadata = true)
    }

    private fun requestMediaAccess(category: MediaCategory, includeOriginalPhotoMetadata: Boolean) {
        if (mediaProvider.hasReadAccess(category)) {
            continueMediaRequest(category, includeOriginalPhotoMetadata)
        } else {
            pendingCategory = category
            pendingOriginalPhotoMetadata = includeOriginalPhotoMetadata
            mediaPermissionRequest.launch(
                mediaProvider.permissionsToRequest(category, includeOriginalPhotoMetadata),
            )
        }
    }

    private fun continueMediaRequest(category: MediaCategory, includeOriginalPhotoMetadata: Boolean) {
        if (MediaOriginalAccessPolicy.requiresMediaLocationPermission(
                category,
                Build.VERSION.SDK_INT,
                includeOriginalPhotoMetadata,
            ) && !mediaProvider.hasMediaLocationAccess()
        ) {
            mediaLocationPermissionRequest.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            scanMedia(category, includeOriginalPhotoMetadata)
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
                    access.shizukuReasonCode == "SHIZUKU_STATUS_UNAVAILABLE" ->
                        "Не вдалося перевірити стан Shizuku; запит дозволу не надсилався"
                    !access.shizukuServiceAvailable -> "Shizuku service не підключений"
                    !access.shizukuPermissionGranted -> "Shizuku permission не надано"
                    else -> "Shizuku доступ підтверджений, але elevated settings операції ще не реалізовані"
                }
                val ownerStatus = when {
                    !access.deviceOwnerStatusAvailable ->
                        "Не вдалося перевірити стан Device Owner; повтори перевірку. " +
                            access.deviceOwnerProvisioningWarning
                    access.deviceOwner -> "Device Owner активний для TransferKit"
                    else -> "${access.deviceOwnerReasonCode}; ${access.deviceOwnerProvisioningWarning}"
                }
                val summary = "Прочитано ${access.publicSettingsRead}/${access.publicSettingsTotal} " +
                    "публічних ключів Settings.System; WRITE_SETTINGS approval: " +
                    "${if (access.systemWriteApproved) "є" else "немає"}; $shizukuStatus; $ownerStatus. " +
                    "Settings.Secure/Global і будь-які зміни тут не виконуються."
                runOnUiThread {
                    this@MainActivity.shizukuStatus = shizukuStatus
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

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 0x544b
    }

    private fun scanMedia(category: MediaCategory, includeOriginalPhotoMetadata: Boolean = false) {
        mediaStatus = if (category == MediaCategory.PHOTOS && includeOriginalPhotoMetadata) {
            "Перевіряю метадані доступних фото; вміст і SHA-256 будуть прочитані під час побудови маніфесту…"
        } else {
            "Перевіряю категорію ${category.contractId}…"
        }
        mediaExecutor.execute {
            try {
                var totalBytes = 0L
                var unknownSizes = 0
                val manifestItems = ArrayList<ManifestItemSource>()
                val count = mediaProvider.forEachMedia(category, includeOriginalPhotoMetadata) { item ->
                    val size = item.sizeBytes
                    if (size == null) unknownSizes += 1 else totalBytes = totalBytes.saturatingAdd(size)
                    manifestItems += ManifestItemSources.media(mediaProvider, item)
                }
                val mediaLocationNote = if (
                    category == MediaCategory.PHOTOS &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !includeOriginalPhotoMetadata
                ) {
                    " GPS EXIF може бути redacted Android; у передачу піде точний доступний потік, координати окремо не читаються."
                } else if (category == MediaCategory.PHOTOS && includeOriginalPhotoMetadata) {
                    " Оригінальні URI підготовлені після явного дозволу; координати застосунок не журналює."
                } else {
                    ""
                }
                val result = if (count == 0) {
                    "${category.contractId}: доступних елементів немає."
                } else {
                    "${category.contractId}: $count елементів, " +
                        "відомий розмір ${formatBytes(totalBytes)}, " +
                        "$unknownSizes без розміру; перевірено лише метадані.$mediaLocationNote"
                }
                val method = when {
                    category == MediaCategory.PHOTOS && includeOriginalPhotoMetadata ->
                        "MediaStore.images.require_original"
                    category == MediaCategory.PHOTOS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                        "MediaStore.images.platform_redaction_allowed"
                    else -> "MediaStore.${category.contractId}"
                }
                runOnUiThread {
                    rememberManifestSource(
                        ManifestCategorySource(
                            category.contractId,
                            "android.provider.MediaStore",
                            method,
                            manifestItems,
                        ),
                    )
                    mediaStatus = result
                }
            } catch (_: SecurityException) {
                runOnUiThread { mediaStatus = "Android не надав доступ до вибраної категорії." }
            } catch (_: IOException) {
                runOnUiThread { mediaStatus = "Не вдалося прочитати список категорії з MediaStore." }
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
                val counts = items.groupingBy { it.categoryId }.eachCount()
                    .entries.joinToString { "${it.key}: ${it.value}" }
                val result = "Вибрано ${items.size} файлів ($counts), " +
                    "відомий розмір ${formatBytes(totalBytes)}, " +
                    "$unknownSizes без розміру; прочитано лише метадані. Вміст і SHA-256 " +
                    "будуть прочитані під час побудови маніфесту."
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
                runOnUiThread { mediaStatus = "Не вдалося прочитати метадані вибраного документа." }
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
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
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
