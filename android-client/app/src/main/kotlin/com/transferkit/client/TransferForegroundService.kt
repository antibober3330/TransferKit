package com.transferkit.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.transferkit.client.receiver.AndroidTransferReceiver
import com.transferkit.client.transport.UsbAdbReceiverServer
import com.transferkit.client.transport.UsbAdbSourceServer

/** User-visible foreground notification shell; transfer workers are connected in later transport steps. */
class TransferForegroundService : Service() {
    private var receiver: AndroidTransferReceiver? = null
    private var adbReceiverServer: UsbAdbReceiverServer? = null
    private var adbSourceServer: UsbAdbSourceServer? = null
    private var sourceManifestBundle: TransferManifestBundle? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Передача даних",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Стан активної передачі TransferKit"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote before any future worker setup so Android never sees lengthy work before FGS.
        val phase = intent?.getStringExtra(EXTRA_PHASE)
            ?.let { value -> TransferPhase.entries.firstOrNull { it.name == value } }
            ?: TransferPhase.PREPARING
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, -1) ?: -1
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("TransferKit")
            .setContentText(phase.label)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(-1, 100).takeIf { it >= 0 } ?: 0, progress < 0)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    CONTENT_REQUEST_CODE,
                    packageManager.getLaunchIntentForPackage(packageName),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        startForeground(NOTIFICATION_ID, notification)
        when (intent?.action) {
            ACTION_START_ADB_RECEIVER -> startAdbReceiver()
            ACTION_START_ADB_SOURCE -> startAdbSource()
            ACTION_STOP_ADB_SOURCE -> {
                stopAdbSource()
                if (adbReceiverServer == null) stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_STOP_ADB_RECEIVER -> {
                stopAdbReceiver()
                if (adbSourceServer == null) stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAdbReceiver() {
        if (adbReceiverServer != null) {
            setReceiverStatus(STATUS_LISTENING)
            updateNotification("Очікую Windows Station через USB/ADB")
            return
        }
        setReceiverStatus(STATUS_STARTING)
        try {
            receiver = AndroidTransferReceiver(applicationContext)
            adbReceiverServer = UsbAdbReceiverServer(requireNotNull(receiver)).also { it.start() }
            setReceiverStatus(STATUS_LISTENING)
            updateNotification("Очікую Windows Station через USB/ADB")
        } catch (_: Exception) {
            adbReceiverServer?.close()
            adbReceiverServer = null
            receiver?.close()
            receiver = null
            setReceiverStatus(STATUS_ERROR)
            updateNotification("Не вдалося запустити USB/ADB-приймач")
            stopSelf()
        }
    }

    private fun startAdbSource() {
        if (adbSourceServer != null) {
            setSourceStatus(STATUS_LISTENING)
            updateNotification("Очікую Windows Station як джерело через USB/ADB")
            return
        }
        setSourceStatus(STATUS_STARTING)
        val bundle = synchronized(sourceBundleLock) {
            pendingSourceManifest.also { pendingSourceManifest = null }
        }
        if (bundle == null) {
            setSourceStatus(STATUS_ERROR)
            updateNotification("Не вдалося запустити Android-джерело")
            if (adbReceiverServer == null) stopSelf()
            return
        }
        try {
            sourceManifestBundle = bundle
            adbSourceServer = UsbAdbSourceServer(bundle).also { it.start() }
            setSourceStatus(STATUS_LISTENING)
            updateNotification("Очікую Windows Station як джерело через USB/ADB")
        } catch (_: Exception) {
            adbSourceServer?.close()
            adbSourceServer = null
            sourceManifestBundle?.let { runCatching { it.close() } }
            sourceManifestBundle = null
            setSourceStatus(STATUS_ERROR)
            updateNotification("Не вдалося запустити Android-джерело USB/ADB")
            if (adbReceiverServer == null) stopSelf()
        }
    }

    private fun stopAdbReceiver() {
        adbReceiverServer?.close()
        adbReceiverServer = null
        receiver?.close()
        receiver = null
        setReceiverStatus(STATUS_STOPPED)
    }

    private fun stopAdbSource() {
        adbSourceServer?.close()
        adbSourceServer = null
        sourceManifestBundle?.let { runCatching { it.close() } }
        sourceManifestBundle = null
        setSourceStatus(STATUS_STOPPED)
    }

    private fun setReceiverStatus(status: String) = setReceiverStatus(this, status)
    private fun setSourceStatus(status: String) = setSourceStatus(this, status)

    private fun updateNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("TransferKit")
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        adbReceiverServer?.close()
        adbReceiverServer = null
        receiver?.close()
        receiver = null
        adbSourceServer?.close()
        adbSourceServer = null
        sourceManifestBundle?.let { runCatching { it.close() } }
        sourceManifestBundle = null
        if (receiverStatus(this) != STATUS_ERROR) setReceiverStatus(STATUS_STOPPED)
        if (sourceStatus(this) != STATUS_ERROR) setSourceStatus(STATUS_STOPPED)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "transfer_progress"
        private const val NOTIFICATION_ID = 1001
        private const val CONTENT_REQUEST_CODE = 1002
        private const val EXTRA_PHASE = "com.transferkit.client.extra.TRANSFER_PHASE"
        private const val EXTRA_PROGRESS = "com.transferkit.client.extra.TRANSFER_PROGRESS"
        private const val ACTION_START_ADB_RECEIVER = "com.transferkit.client.action.START_ADB_RECEIVER"
        private const val ACTION_STOP_ADB_RECEIVER = "com.transferkit.client.action.STOP_ADB_RECEIVER"
        private const val ACTION_START_ADB_SOURCE = "com.transferkit.client.action.START_ADB_SOURCE"
        private const val ACTION_STOP_ADB_SOURCE = "com.transferkit.client.action.STOP_ADB_SOURCE"
        const val STATUS_STARTING = "STARTING"
        const val STATUS_LISTENING = "LISTENING"
        const val STATUS_STOPPED = "STOPPED"
        const val STATUS_ERROR = "ERROR"
        @Volatile private var currentReceiverStatus = STATUS_STOPPED
        @Volatile private var currentSourceStatus = STATUS_STOPPED
        private val sourceBundleLock = Any()
        private var pendingSourceManifest: TransferManifestBundle? = null

        /** Call only from an explicit, user-started transfer flow while the app is foreground. */
        fun start(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java)
                .putExtra(EXTRA_PHASE, TransferPhase.PREPARING.name)
            context.startForegroundService(intent)
        }

        /** Starts the ADB listener only from an explicit, foreground user action. */
        fun startAdbReceiver(context: Context) {
            setReceiverStatus(context, STATUS_STARTING)
            val intent = Intent(context, TransferForegroundService::class.java)
                .setAction(ACTION_START_ADB_RECEIVER)
                .putExtra(EXTRA_PHASE, TransferPhase.PREPARING.name)
            context.startForegroundService(intent)
        }

        fun stopAdbReceiver(context: Context) {
            context.startService(Intent(context, TransferForegroundService::class.java).setAction(ACTION_STOP_ADB_RECEIVER))
            setReceiverStatus(context, STATUS_STOPPED)
        }

        /** Transfers ownership of a prepared manifest bundle to the foreground source service. */
        fun startAdbSource(context: Context, bundle: TransferManifestBundle): Boolean {
            synchronized(sourceBundleLock) { pendingSourceManifest = bundle }
            setSourceStatus(context, STATUS_STARTING)
            return try {
                val intent = Intent(context, TransferForegroundService::class.java)
                    .setAction(ACTION_START_ADB_SOURCE)
                    .putExtra(EXTRA_PHASE, TransferPhase.PREPARING.name)
                context.startForegroundService(intent)
                true
            } catch (_: Exception) {
                synchronized(sourceBundleLock) { pendingSourceManifest = null }
                setSourceStatus(context, STATUS_ERROR)
                false
            }
        }

        fun stopAdbSource(context: Context) {
            context.startService(Intent(context, TransferForegroundService::class.java).setAction(ACTION_STOP_ADB_SOURCE))
            setSourceStatus(context, STATUS_STOPPED)
        }

        fun receiverStatus(context: Context): String = currentReceiverStatus

        fun sourceStatus(context: Context): String = currentSourceStatus

        private fun setReceiverStatus(context: Context, status: String) {
            currentReceiverStatus = status
        }

        private fun setSourceStatus(context: Context, status: String) {
            currentSourceStatus = status
        }

        /** Updates only bounded progress/status values; never pass file names or user data here. */
        fun update(context: Context, phase: TransferPhase, progressPercent: Int? = null) {
            val intent = Intent(context, TransferForegroundService::class.java)
                .putExtra(EXTRA_PHASE, phase.name)
                .putExtra(EXTRA_PROGRESS, progressPercent?.coerceIn(0, 100) ?: -1)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferForegroundService::class.java))
        }
    }

    enum class TransferPhase(val label: String) {
        PREPARING("Готую передачу"),
        TRANSFERRING("Передача даних триває"),
        VERIFYING("Перевіряю отримані дані"),
        RECONNECTING("Відновлюю з’єднання"),
        PAUSED("Передачу призупинено; чекпойнт збережено"),
    }
}
