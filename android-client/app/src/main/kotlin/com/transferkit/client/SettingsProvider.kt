package com.transferkit.client

import android.content.Context
import android.provider.Settings
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.transferkit.client.deviceowner.DoBridge
import com.transferkit.client.shizuku.ShizukuBridge

/** One allowlisted, read-only snapshot of public Settings.System values. */
class SettingsSnapshotItem internal constructor(
    private val payload: ByteArray,
    val settingCount: Int,
) {
    val id: String = "settings_system_v1"
    val sizeBytes: Long get() = payload.size.toLong()
    val sha256: String by lazy(LazyThreadSafetyMode.NONE) {
        MessageDigest.getInstance("SHA-256").digest(payload).toHex()
    }

    fun manifestMetadata(): Map<String, Any> = mapOf(
        "format" to "transferkit-public-settings-json",
        "version" to 1,
        "setting_count" to settingCount,
    )

    fun writeTo(output: java.io.OutputStream) = output.write(payload)
}

data class SettingsAccessProbe(
    val publicSettingsRead: Int,
    val publicSettingsTotal: Int,
    val systemWriteApproved: Boolean,
    val shizukuServiceAvailable: Boolean,
    val shizukuPermissionGranted: Boolean,
    val deviceOwner: Boolean,
    val secureAndGlobalReadImplemented: Boolean = false,
    val elevatedSettingsOperationsImplemented: Boolean = false,
)

data class SettingsScanResult(
    val item: SettingsSnapshotItem,
    val access: SettingsAccessProbe,
)

/**
 * Reads a small explicit public Settings.System allowlist. It never writes settings and never
 * reads arbitrary Secure/Global values. Shizuku/Device Owner are detected as separate future
 * authority paths; neither is treated as proof that a particular setting can be changed.
 */
class SettingsProvider(context: Context) {
    private val appContext = context.applicationContext
    private val shizukuBridge = ShizukuBridge()
    private val deviceOwnerBridge = DoBridge(appContext)

    fun scanReadOnly(): SettingsScanResult {
        val values = linkedMapOf<String, String>()
        PUBLIC_SYSTEM_KEYS.forEach { key ->
            val value = runCatching {
                Settings.System.getString(appContext.contentResolver, key)
            }.getOrNull()
            if (value != null) values[key] = value
        }

        val deviceOwner = deviceOwnerBridge.probe().activeForThisApp
        val shizuku = shizukuBridge.probe()
        val item = SettingsSnapshotItem(canonicalSettingsBytes(values), values.size)
        return SettingsScanResult(
            item = item,
            access = SettingsAccessProbe(
                publicSettingsRead = values.size,
                publicSettingsTotal = PUBLIC_SYSTEM_KEYS.size,
                systemWriteApproved = Settings.System.canWrite(appContext),
                shizukuServiceAvailable = shizuku.serviceRunning,
                shizukuPermissionGranted = shizuku.permissionGranted,
                deviceOwner = deviceOwner,
            ),
        )
    }

    companion object {
        private val PUBLIC_SYSTEM_KEYS = listOf(
            Settings.System.SCREEN_OFF_TIMEOUT,
            Settings.System.SCREEN_BRIGHTNESS,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.ACCELEROMETER_ROTATION,
            Settings.System.FONT_SCALE,
            Settings.System.SOUND_EFFECTS_ENABLED,
        )
    }
}

internal fun canonicalSettingsBytes(values: Map<String, String>): ByteArray {
    val fields = values.toSortedMap().mapValues { (_, value) -> JsonPrimitive(value) as JsonElement }
    val document = JsonObject(
        sortedMapOf(
            "format" to JsonPrimitive("transferkit-settings-system"),
            "settings" to JsonObject(fields),
            "version" to JsonPrimitive(1),
        ),
    )
    return SETTINGS_JSON.encodeToString(JsonObject.serializer(), document).toByteArray(StandardCharsets.UTF_8)
}

private val SETTINGS_JSON = Json { encodeDefaults = true; prettyPrint = false }

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
