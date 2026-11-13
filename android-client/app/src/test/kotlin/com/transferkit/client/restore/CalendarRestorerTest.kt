package com.transferkit.client.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.io.File
import java.security.MessageDigest
import org.junit.Test

class CalendarRestorerTest {
    @Test
    fun acceptsCanonicalCalendarEnvelopeWithOptionalChildRows() {
        val parsed = CalendarRestorer.parseCalendarPayload(payload())

        assertEquals("Planning", parsed.event.getValue("title").toString().trim('"'))
        assertEquals(1, parsed.attendees.size)
        assertEquals(1, parsed.reminders.size)
    }

    @Test
    fun rejectsUnknownEnvelopeAndEventProperties() {
        assertThrows(IllegalArgumentException::class.java) {
            CalendarRestorer.parseCalendarPayload(payload().decodeToString().replace("\"version\":1", "\"version\":1,\"extra\":true").encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalendarRestorer.parseCalendarPayload(payload().decodeToString().replace("\"title\":\"Planning\"", "\"title\":\"Planning\",\"privateId\":3").encodeToByteArray())
        }
    }

    @Test
    fun rejectsMissingStartAndBadRecurrenceReference() {
        assertThrows(IllegalArgumentException::class.java) {
            CalendarRestorer.parseCalendarPayload(payload().decodeToString().replace("\"startMillis\":1700000000000,", "").encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalendarRestorer.parseCalendarPayload(payload().decodeToString().replace("\"originalItemId\":null", "\"originalItemId\":\"event-1\"").encodeToByteArray())
        }
    }

    @Test
    fun destinationMarkerIsStableAndDoesNotExposeSourceItemId() {
        val marker = CalendarRestorer.markerFor("transfer-1", "a".repeat(64), "calendar_sensitive-source-id")

        assertEquals(marker, CalendarRestorer.markerFor("transfer-1", "a".repeat(64), "calendar_sensitive-source-id"))
        assertNotEquals(marker, CalendarRestorer.markerFor("transfer-1", "a".repeat(64), "calendar_other"))
        assertTrue(marker.startsWith("transferkit://restore/"))
        assertTrue(!marker.contains("sensitive-source-id"))
    }

    @Test
    fun parentEventsAreOrderedBeforeRecurrenceExceptions() {
        val parentId = "calendar_${"c".repeat(64)}"
        val parent = item(parentId, payload())
        val exception = item("calendar_exception", payload(parentId))
        val other = item("contact_item", "{}".encodeToByteArray(), category = "contacts")

        try {
            val ordered = CalendarRestorer.parentFirstCalendarOrder(listOf(exception, other, parent))

            assertEquals(listOf(parent.itemId, other.itemId, exception.itemId), ordered.map { it.itemId })
        } finally {
            parent.payloadFile.delete()
            exception.payloadFile.delete()
            other.payloadFile.delete()
        }
    }

    private fun item(itemId: String, payload: ByteArray, category: String = "calendar"): VerifiedRestoreItem {
        val file = File.createTempFile("calendar-order-", ".json").apply { writeBytes(payload) }
        val hash = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return VerifiedRestoreItem(
            transferId = "transfer-order",
            manifestSha256 = "d".repeat(64),
            categoryId = category,
            itemId = itemId,
            sizeBytes = payload.size.toLong(),
            sha256 = hash,
            metadata = emptyMap(),
            payloadFile = file,
        )
    }

    private fun payload(originalItemId: String? = null): ByteArray = """
        {"attendees":[{"email":"person@example.test","name":"Pat","relationship":1,"status":0,"type":1}],
        "event":{"accessLevel":0,"allDay":0,"availability":0,"description":null,"duration":null,"endMillis":1700003600000,
        "endTimezone":null,"exceptionDates":null,"exceptionRule":null,"guestCanInviteOthers":0,"guestCanModify":0,
        "guestCanSeeGuests":0,"hasAlarm":1,"hasAttendeeData":1,"hasExtendedProperties":0,"location":null,
        "originalAllDay":null,"originalInstanceTime":null,"originalItemId":null,"organizer":null,"recurrenceDates":null,
        "recurrenceRule":null,"selfAttendeeStatus":0,"startMillis":1700000000000,"status":1,"timezone":"UTC","title":"Planning"},
        "format":"transferkit-calendar-event","reminders":[{"method":1,"minutes":10}],"version":1}
    """.trimIndent().replace(
        "\"originalItemId\":null",
        "\"originalItemId\":${originalItemId?.let { "\"$it\"" } ?: "null"}",
    ).encodeToByteArray()
}
