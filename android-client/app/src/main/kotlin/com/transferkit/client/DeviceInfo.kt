package com.transferkit.client

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs

/** Device identity plus local diagnostics; only the first four fields belong in the shared contract. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdk: Int,
    val fingerprint: String,
    val androidRelease: String,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val dataStorageTotalBytes: Long,
    val dataStorageAvailableBytes: Long,
)

object DeviceInfoProvider {
    fun read(context: Context): DeviceInfo {
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: error("ActivityManager service is unavailable")
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)

        val dataStorage = StatFs(Environment.getDataDirectory().absolutePath)

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER.trim(),
            model = Build.MODEL.trim(),
            sdk = Build.VERSION.SDK_INT,
            fingerprint = Build.FINGERPRINT,
            androidRelease = Build.VERSION.RELEASE,
            totalMemoryBytes = memory.totalMem,
            availableMemoryBytes = memory.availMem,
            dataStorageTotalBytes = dataStorage.totalBytes,
            dataStorageAvailableBytes = dataStorage.availableBytes,
        )
    }
}
