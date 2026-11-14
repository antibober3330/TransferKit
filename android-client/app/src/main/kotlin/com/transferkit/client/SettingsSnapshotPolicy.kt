package com.transferkit.client

import android.provider.Settings
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Shared source/target contract for the narrowly allowlisted public Settings.System snapshot. */
internal object SettingsSnapshotPolicy {
    const val MANIFEST_FORMAT = "transferkit-public-settings-json"
    const val PAYLOAD_FORMAT = "transferkit-settings-system"
    const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    val allowlistedKeys = listOf(
        Settings.System.SCREEN_OFF_TIMEOUT,
        Settings.System.SCREEN_BRIGHTNESS,
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.ACCELEROMETER_ROTATION,
        Settings.System.FONT_SCALE,
        Settings.System.SOUND_EFFECTS_ENABLED,
    )

    /** Null means the snapshot is malformed, non-canonical, unsupported, or outside safe bounds. */
    fun parseCanonicalPayload(bytes: ByteArray): Map<String, String>? {
        if (bytes.isEmpty() || bytes.size > MAX_PAYLOAD_BYTES) return null
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            return null
        }
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        if (root.keys != setOf("format", "settings", "version")) return null
        if ((root["format"] as? JsonPrimitive)?.let { it.isString && it.content == PAYLOAD_FORMAT } != true) {
            return null
        }
        val version = root["version"] as? JsonPrimitive ?: return null
        if (version.isString || version.intOrNull != VERSION) return null
        val settings = root["settings"] as? JsonObject ?: return null
        val values = linkedMapOf<String, String>()
        for ((key, rawValue) in settings) {
            val value = rawValue as? JsonPrimitive ?: return null
            if (key !in allowlistedKeys || !value.isString || !isRestorableValue(key, value.content)) return null
            values[key] = value.content
        }
        if (!canonicalSettingsBytes(values).contentEquals(bytes)) return null
        return values
    }

    private fun isRestorableValue(key: String, value: String): Boolean = when (key) {
        Settings.System.SCREEN_OFF_TIMEOUT -> value.toLongOrNull()?.let { it in 0L..604_800_000L } == true
        Settings.System.SCREEN_BRIGHTNESS -> value.toIntOrNull()?.let { it in 0..255 } == true
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.ACCELEROMETER_ROTATION,
        Settings.System.SOUND_EFFECTS_ENABLED,
        -> value == "0" || value == "1"
        Settings.System.FONT_SCALE -> value.toFloatOrNull()?.let { it.isFinite() && it in 0.5f..2.0f } == true
        else -> false
    }
}
