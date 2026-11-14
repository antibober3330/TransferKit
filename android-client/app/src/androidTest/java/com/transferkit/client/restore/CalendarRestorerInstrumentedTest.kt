package com.transferkit.client.restore

import android.content.ContentResolver
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarRestorerInstrumentedTest {
    @Test
    fun restoresSyntheticEventOnceAndRemovesOnlyItsOwnMarker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missingPermissions = listOf(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
        ).filter {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        assumeTrue("Pre-grant synthetic Calendar Provider test permissions with ADB: $missingPermissions", missingPermissions.isEmpty())
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR))
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR))
        val destinations = CalendarRestorer.listWritableCalendars(context)
        assumeTrue("No writable calendar is configured on this device", destinations.isNotEmpty())
        deletePreviousSyntheticFixtures(context.contentResolver)

        val transferId = UUID.randomUUID().toString()
        val manifestSha256 = "b".repeat(64)
        val itemId = "calendar_instrumented_fixture"
        val bytes = payload()
        val payloadFile = File(context.cacheDir, "calendar-fixture-$transferId.json").apply { writeBytes(bytes) }
        val item = VerifiedRestoreItem(
            transferId = transferId,
            manifestSha256 = manifestSha256,
            categoryId = "calendar",
            itemId = itemId,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            metadata = mapOf("format" to "transferkit-calendar-event-json", "version" to 1),
            payloadFile = payloadFile,
        )
        val gate = RestoreApprovalGate()
        val restorer = CalendarRestorer(context, gate)
        val marker = CalendarRestorer.markerFor(transferId, manifestSha256, itemId)
        val destinationId = destinations.first().id
        try {
            val first = restorer.restore(item, approve(gate, item), RestoreContext(destinationId))
            assertEquals("first restore result=${first.status} reason=${first.errorReason}", RestoreItemStatus.RESTORED, first.status)
            assertSingleMarkedEvent(context.contentResolver, marker)

            val retry = restorer.restore(item, approve(gate, item), RestoreContext(destinationId))
            assertEquals("retry result=${retry.status} reason=${retry.errorReason}", RestoreItemStatus.RESTORED, retry.status)
            assertSingleMarkedEvent(context.contentResolver, marker)
        } finally {
            try {
                deleteMarkedEvents(context.contentResolver, marker)
                assertEquals(0, countMarkedEvents(context.contentResolver, marker))
            } finally {
                payloadFile.delete()
            }
        }
    }

    private fun approve(gate: RestoreApprovalGate, item: VerifiedRestoreItem) =
        requireNotNull(gate.approveAfterUserConfirmation(item.transferId, item.manifestSha256, listOf(item), true))

    private fun assertSingleMarkedEvent(resolver: ContentResolver, marker: String) =
        assertEquals(1, countMarkedEvents(resolver, marker))

    private fun countMarkedEvents(resolver: ContentResolver, marker: String): Int {
        val cursor = resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.CUSTOM_APP_URI} = ?",
            arrayOf("com.transferkit.client", marker),
            null,
        ) ?: error("Calendar Provider returned no marker cursor")
        return cursor.use { it.count }
    }

    private fun deleteMarkedEvents(resolver: ContentResolver, marker: String) {
        val cursor = resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.CUSTOM_APP_URI} = ?",
            arrayOf("com.transferkit.client", marker),
            null,
        ) ?: error("Calendar Provider returned no cleanup cursor")
        val ids = cursor.use { rows -> buildList { while (rows.moveToNext()) add(rows.getLong(0)) } }
        ids.forEach { id ->
            resolver.delete(
                Events.CONTENT_URI,
                "${Events._ID} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.CUSTOM_APP_URI} = ?",
                arrayOf(id.toString(), "com.transferkit.client", marker),
            )
        }
    }

    private fun deletePreviousSyntheticFixtures(resolver: ContentResolver) {
        val cursor = resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.TITLE} = ? AND ${Events.DESCRIPTION} = ?",
            arrayOf("com.transferkit.client", "TransferKit synthetic test event", "Synthetic TransferKit test event"),
            null,
        ) ?: error("Calendar Provider returned no prior-fixture cursor")
        val ids = cursor.use { rows -> buildList { while (rows.moveToNext()) add(rows.getLong(0)) } }
        ids.forEach { id ->
            resolver.delete(
                Events.CONTENT_URI,
                "${Events._ID} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.TITLE} = ? AND ${Events.DESCRIPTION} = ?",
                arrayOf(id.toString(), "com.transferkit.client", "TransferKit synthetic test event", "Synthetic TransferKit test event"),
            )
        }
    }

    private fun payload(): ByteArray {
        val start = System.currentTimeMillis() + 2L * 365 * 24 * 60 * 60 * 1000
        val end = start + 60 * 60 * 1000
        return """
            {"attendees":[],"event":{"accessLevel":2,"allDay":0,"availability":0,"description":"Synthetic TransferKit test event",
            "duration":null,"endMillis":$end,"endTimezone":"UTC","exceptionDates":null,"exceptionRule":null,
            "guestCanInviteOthers":0,"guestCanModify":0,"guestCanSeeGuests":0,"hasAlarm":0,"hasAttendeeData":0,
            "hasExtendedProperties":0,"location":null,"originalAllDay":null,"originalInstanceTime":null,"originalItemId":null,
            "organizer":null,"recurrenceDates":null,"recurrenceRule":null,"selfAttendeeStatus":0,"startMillis":$start,
            "status":1,"timezone":"UTC","title":"TransferKit synthetic test event"},
            "format":"transferkit-calendar-event","reminders":[],"version":1}
        """.trimIndent().toByteArray(Charsets.UTF_8).also { Json.parseToJsonElement(it.decodeToString()) as JsonObject }
    }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
