package com.transferkit.client.deviceowner

import android.app.admin.DevicePolicyManager
import android.content.Context

data class DeviceOwnerProbe(
    val activeForThisApp: Boolean,
    val reasonCode: String,
    val statusAvailable: Boolean = true,
    /** Informational only: TransferKit never provisions itself or initiates a factory reset. */
    val provisioningWarning: String =
        "Device Owner потребує окремого корпоративного налаштування; на новому пристрої це може " +
            "вимагати скидання до заводських налаштувань. TransferKit не запускає це самостійно.",
    val policyOperationsImplemented: Boolean = false,
)

/** Read-only seam keeps Device Owner detection testable without provisioning a device. */
internal fun interface DeviceOwnerApi {
    fun isDeviceOwnerApp(packageName: String): Boolean
}

private class AndroidDeviceOwnerApi(private val context: Context) : DeviceOwnerApi {
    override fun isDeviceOwnerApp(packageName: String): Boolean {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: throw IllegalStateException("DevicePolicyManager is unavailable")
        return manager.isDeviceOwnerApp(packageName)
    }
}

/** Detects existing Device Owner state without changing device policy or provisioning the device. */
class DoBridge internal constructor(
    private val packageName: String,
    private val api: DeviceOwnerApi,
) {
    constructor(context: Context) : this(
        context.applicationContext.packageName,
        AndroidDeviceOwnerApi(context.applicationContext),
    )

    fun probe(): DeviceOwnerProbe {
        val isOwner = runCatching { api.isDeviceOwnerApp(packageName) }.getOrElse {
            return DeviceOwnerProbe(
                activeForThisApp = false,
                reasonCode = "DEVICE_OWNER_STATUS_UNAVAILABLE",
                statusAvailable = false,
            )
        }
        return DeviceOwnerProbe(
            activeForThisApp = isOwner,
            reasonCode = if (isOwner) "OK" else "DEVICE_OWNER_REQUIRED",
            statusAvailable = true,
        )
    }
}
