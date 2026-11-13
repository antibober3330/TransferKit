package com.transferkit.client.deviceowner

import android.app.admin.DevicePolicyManager
import android.content.Context

data class DeviceOwnerProbe(
    val activeForThisApp: Boolean,
    val reasonCode: String,
    /** Informational only: TransferKit never provisions itself or initiates a factory reset. */
    val provisioningWarning: String =
        "Device Owner потребує окремого корпоративного налаштування; на новому пристрої це може " +
            "вимагати скидання до заводських налаштувань. TransferKit не запускає це самостійно.",
    val policyOperationsImplemented: Boolean = false,
)

/** Detects existing Device Owner state without changing device policy or provisioning the device. */
class DoBridge(context: Context) {
    private val appContext = context.applicationContext

    fun probe(): DeviceOwnerProbe {
        val manager = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val isOwner = runCatching {
            manager?.isDeviceOwnerApp(appContext.packageName) == true
        }.getOrDefault(false)
        return DeviceOwnerProbe(
            activeForThisApp = isOwner,
            reasonCode = if (isOwner) "OK" else "DEVICE_OWNER_REQUIRED",
        )
    }
}
