package com.transferkit.client

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarProviderTest {
    @Test
    fun canonicalPayloadIsStableAndContainsChildRows() {
        val first = canonicalCalendarEventBytes(
            event = linkedMapOf(
                "title" to JsonPrimitive("Зустріч"),
                "startMillis" to JsonPrimitive(1_800_000_000_000L),
                "description" to JsonNull,
            ),
            attendees = listOf(JsonObject(mapOf("email" to JsonPrimitive("person@example.test")))),
            reminders = listOf(JsonObject(mapOf("minutes" to JsonPrimitive(15))), JsonObject(mapOf("minutes" to JsonPrimitive(5)))),
        )
        val reordered = canonicalCalendarEventBytes(
            event = linkedMapOf(
                "description" to JsonNull,
                "startMillis" to JsonPrimitive(1_800_000_000_000L),
                "title" to JsonPrimitive("Зустріч"),
            ),
            attendees = listOf(JsonObject(mapOf("email" to JsonPrimitive("person@example.test")))),
            reminders = listOf(JsonObject(mapOf("minutes" to JsonPrimitive(15))), JsonObject(mapOf("minutes" to JsonPrimitive(5)))),
        )

        assertArrayEquals(first, reordered)
        val json = String(first, StandardCharsets.UTF_8)
        assertTrue(json.contains("\"format\":\"transferkit-calendar-event\""))
        assertTrue(json.contains("\"attendees\":[{"))
        assertTrue(json.contains("\"reminders\":[{"))
        assertTrue(json.contains("\"description\":null"))
        assertTrue(json.indexOf("\"attendees\"") < json.indexOf("\"event\""))
    }

    @Test
    fun itemIdDoesNotExposeProviderIdsAndIsStable() {
        val id = calendarEventItemId(calendarId = 12345L, eventId = 67890L)

        assertEquals(id, calendarEventItemId(calendarId = 12345L, eventId = 67890L))
        assertTrue(id.matches(Regex("calendar_[0-9a-f]{64}")))
        assertFalse(id.contains("12345"))
        assertFalse(id.contains("67890"))
    }

    @Test
    fun recurrenceExceptionReferenceUsesHashedSourceItemId() {
        val reference = portableOriginalEventReference(12345L, JsonPrimitive(67890L))

        assertEquals(JsonPrimitive(calendarEventItemId(12345L, 67890L)), reference)
        assertEquals(JsonNull, portableOriginalEventReference(12345L, JsonPrimitive(0L)))
    }

    @Test
    fun extensionFlagHandlesIntegerAndEmptyValues() {
        assertTrue(hasUnexportedExtensions(JsonPrimitive(1)))
        assertFalse(hasUnexportedExtensions(JsonPrimitive(0)))
        assertFalse(hasUnexportedExtensions(JsonNull))
    }

    @Test
    fun itemWritesExactlyHashedPayloadAndDeclaresFormat() {
        val payload = canonicalCalendarEventBytes(
            event = mapOf("title" to JsonPrimitive("Private title")),
            attendees = emptyList(),
            reminders = emptyList(),
        )
        val item = CalendarEventItem("calendar_test", payload, containsProviderExtensions = false)
        val output = ByteArrayOutputStream()

        item.writeTo(output)

        assertArrayEquals(payload, output.toByteArray())
        assertEquals(payload.size.toLong(), item.sizeBytes)
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        assertEquals(expectedHash, item.sha256)
        assertEquals(
            mapOf(
                "format" to "transferkit-calendar-event-json",
                "version" to 1,
                "contains_unexported_extensions" to false,
            ),
            item.manifestMetadata(),
        )
    }
}
