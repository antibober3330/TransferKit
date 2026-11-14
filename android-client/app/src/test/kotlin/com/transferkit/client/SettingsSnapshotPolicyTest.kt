package com.transferkit.client

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsSnapshotPolicyTest {
    @Test
    fun acceptsCanonicalAllowlistedSnapshot() {
        val expected = mapOf(
            Settings.System.SCREEN_OFF_TIMEOUT to "60000",
            Settings.System.SCREEN_BRIGHTNESS to "128",
            Settings.System.SCREEN_BRIGHTNESS_MODE to "1",
            Settings.System.ACCELEROMETER_ROTATION to "0",
            Settings.System.FONT_SCALE to "1.0",
            Settings.System.SOUND_EFFECTS_ENABLED to "1",
        )

        assertEquals(expected, SettingsSnapshotPolicy.parseCanonicalPayload(canonicalSettingsBytes(expected)))
    }

    @Test
    fun rejectsUnknownSettingAndOutOfRangeValues() {
        assertNull(SettingsSnapshotPolicy.parseCanonicalPayload(canonicalSettingsBytes(mapOf("unknown_key" to "1"))))
        assertNull(SettingsSnapshotPolicy.parseCanonicalPayload(canonicalSettingsBytes(mapOf(Settings.System.SCREEN_BRIGHTNESS to "256"))))
        assertNull(SettingsSnapshotPolicy.parseCanonicalPayload(canonicalSettingsBytes(mapOf(Settings.System.FONT_SCALE to "NaN"))))
        assertNull(SettingsSnapshotPolicy.parseCanonicalPayload(canonicalSettingsBytes(mapOf(Settings.System.SCREEN_BRIGHTNESS_MODE to "2"))))
    }

    @Test
    fun rejectsWrongEnvelopeTypesAndUnsupportedVersion() {
        assertNull(parse("{\"format\":\"${SettingsSnapshotPolicy.PAYLOAD_FORMAT}\",\"settings\":{},\"version\":\"1\"}"))
        assertNull(parse("{\"format\":\"wrong\",\"settings\":{},\"version\":1}"))
        assertNull(parse("{\"format\":\"${SettingsSnapshotPolicy.PAYLOAD_FORMAT}\",\"settings\":{},\"version\":2}"))
        assertNull(parse("{\"format\":\"${SettingsSnapshotPolicy.PAYLOAD_FORMAT}\",\"settings\":{\"${Settings.System.SCREEN_BRIGHTNESS}\":128},\"version\":1}"))
    }

    @Test
    fun rejectsNonCanonicalDuplicateKeysAndOversizePayload() {
        val duplicate = "{\"format\":\"${SettingsSnapshotPolicy.PAYLOAD_FORMAT}\",\"settings\":{\"${Settings.System.SCREEN_BRIGHTNESS}\":\"1\",\"${Settings.System.SCREEN_BRIGHTNESS}\":\"2\"},\"version\":1}"
        assertNull(parse(duplicate))
        assertNull(parse(" {\"format\":\"${SettingsSnapshotPolicy.PAYLOAD_FORMAT}\",\"settings\":{},\"version\":1}"))
        assertNull(SettingsSnapshotPolicy.parseCanonicalPayload(ByteArray(SettingsSnapshotPolicy.MAX_PAYLOAD_BYTES + 1)))
    }

    private fun parse(text: String) = SettingsSnapshotPolicy.parseCanonicalPayload(text.toByteArray(Charsets.UTF_8))
}
