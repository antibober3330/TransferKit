package com.transferkit.client.shizuku

import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku

enum class ShizukuState {
    UNSUPPORTED_API,
    SERVICE_NOT_RUNNING,
    PERMISSION_REQUIRED,
    AVAILABLE,
}

data class ShizukuProbe(
    val state: ShizukuState,
    val reasonCode: String,
    val serviceRunning: Boolean,
    val permissionGranted: Boolean,
    /** A Shizuku grant is not proof that a specific operation is supported or permitted. */
    val elevatedOperationsImplemented: Boolean = false,
)

/** Read-only availability probe. This bridge does not start a shell or execute privileged work. */
class ShizukuBridge {
    fun probe(): ShizukuProbe {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ShizukuProbe(
                state = ShizukuState.UNSUPPORTED_API,
                reasonCode = "API_UNAVAILABLE",
                serviceRunning = false,
                permissionGranted = false,
            )
        }
        val serviceRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!serviceRunning) {
            return ShizukuProbe(
                state = ShizukuState.SERVICE_NOT_RUNNING,
                reasonCode = "SHIZUKU_REQUIRED",
                serviceRunning = false,
                permissionGranted = false,
            )
        }
        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (permissionGranted) {
            ShizukuProbe(
                state = ShizukuState.AVAILABLE,
                reasonCode = "OK",
                serviceRunning = true,
                permissionGranted = true,
            )
        } else {
            ShizukuProbe(
                state = ShizukuState.PERMISSION_REQUIRED,
                reasonCode = "USER_ACTION_REQUIRED",
                serviceRunning = true,
                permissionGranted = false,
            )
        }
    }

    fun userWarning(): String =
        "Shizuku є опційним. Його потрібно окремо запустити й авторизувати; це не надає root " +
            "і не гарантує доступ до кожного API. TransferKit поки не виконує elevated operations."
}
