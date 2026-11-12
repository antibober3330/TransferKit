package com.transferkit.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One logical CalendarContract.Events row; recurrence remains a rule, not expanded Instances. */
class CalendarEventItem internal constructor(
    val id: String,
    private val payload: ByteArray,
    val containsProviderExtensions: Boolean,
) {
    val sizeBytes: Long get() = payload.size.toLong()
    val sha256: String by lazy(LazyThreadSafetyMode.NONE) { sha256Hex(payload) }

    fun manifestMetadata(): Map<String, Any> = mapOf(
        "format" to "transferkit-calendar-event-json",
        "version" to 1,
        "contains_unexported_extensions" to containsProviderExtensions,
    )

    /** The exact bytes measured in the manifest are sent to the transport. */
    fun writeTo(output: OutputStream) = output.write(payload)
}

data class CalendarScanSummary(
    val eventCount: Int,
    val totalBytes: Long,
    val eventsWithProviderExtensions: Int,
)

private data class CalendarField(val jsonName: String, val providerColumn: String)

class CalendarProvider(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun hasReadAccess(): Boolean = appContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    /**
     * Enumerates events one at a time and includes their attendee/reminder child rows. Account names,
     * account types and local calendar IDs are used only for provider queries and never serialized.
     */
    fun forEachEvent(onEvent: (CalendarEventItem) -> Unit): CalendarScanSummary {
        if (!hasReadAccess()) throw SecurityException("READ_CALENDAR is required")

        val projection = arrayOf(
            Events._ID,
            Events.CALENDAR_ID,
            *EVENT_FIELDS.map(CalendarField::providerColumn).toTypedArray(),
        )
        val cursor = resolver.query(
            Events.CONTENT_URI,
            projection,
            "${Events.DELETED} = 0",
            null,
            "${Events._ID} ASC",
        ) ?: throw IOException("Calendar Provider returned no event cursor")

        var eventCount = 0
        var totalBytes = 0L
        var extensionCount = 0
        cursor.use { events ->
            val idIndex = events.getColumnIndexOrThrow(Events._ID)
            val calendarIdIndex = events.getColumnIndexOrThrow(Events.CALENDAR_ID)
            val fieldIndexes = EVENT_FIELDS.map { field ->
                field.jsonName to events.getColumnIndexOrThrow(field.providerColumn)
            }

            while (events.moveToNext()) {
                if (events.isNull(idIndex) || events.isNull(calendarIdIndex)) {
                    throw IOException("Calendar Provider omitted event identity fields")
                }
                val eventId = events.getLong(idIndex)
                val calendarId = events.getLong(calendarIdIndex)
                val eventFields = fieldIndexes.associate { (name, index) -> name to events.jsonValue(index) }
                    .toMutableMap()
                val sourceOriginalId = eventFields.remove("originalEventId")
                eventFields["originalItemId"] = portableOriginalEventReference(calendarId, sourceOriginalId)
                val attendees = readAttendees(eventId)
                val reminders = readReminders(eventId)
                val payload = canonicalCalendarEventBytes(eventFields, attendees, reminders)
                val hasExtensions = hasUnexportedExtensions(eventFields["hasExtendedProperties"])
                val item = CalendarEventItem(
                    id = calendarEventItemId(calendarId, eventId),
                    payload = payload,
                    containsProviderExtensions = hasExtensions,
                )
                totalBytes = checkedAdd(totalBytes, item.sizeBytes)
                if (hasExtensions) extensionCount += 1
                onEvent(item)
                eventCount += 1
            }
        }

        return CalendarScanSummary(eventCount, totalBytes, extensionCount)
    }

    private fun readAttendees(eventId: Long): List<JsonObject> = readChildRows(
        uri = Attendees.CONTENT_URI,
        fields = ATTENDEE_FIELDS,
        selection = "${Attendees.EVENT_ID} = ?",
        selectionArgs = arrayOf(eventId.toString()),
        sortOrder = listOf(
            Attendees.ATTENDEE_NAME,
            Attendees.ATTENDEE_EMAIL,
            Attendees.ATTENDEE_RELATIONSHIP,
            Attendees.ATTENDEE_TYPE,
            Attendees.ATTENDEE_STATUS,
        ).joinToString(", ") { "$it ASC" },
    )

    private fun readReminders(eventId: Long): List<JsonObject> = readChildRows(
        uri = Reminders.CONTENT_URI,
        fields = REMINDER_FIELDS,
        selection = "${Reminders.EVENT_ID} = ?",
        selectionArgs = arrayOf(eventId.toString()),
        sortOrder = "${Reminders.MINUTES} ASC, ${Reminders.METHOD} ASC",
    )

    private fun readChildRows(
        uri: android.net.Uri,
        fields: List<CalendarField>,
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String,
    ): List<JsonObject> {
        val cursor = resolver.query(
            uri,
            fields.map(CalendarField::providerColumn).toTypedArray(),
            selection,
            selectionArgs,
            sortOrder,
        ) ?: throw IOException("Calendar Provider returned no child-row cursor")

        cursor.use { rows ->
            val indexes = fields.map { it.jsonName to rows.getColumnIndexOrThrow(it.providerColumn) }
            val result = ArrayList<JsonObject>()
            while (rows.moveToNext()) {
                result += sortedJsonObject(indexes.associate { (name, index) -> name to rows.jsonValue(index) })
            }
            return result
        }
    }

    companion object {
        private val EVENT_FIELDS = listOf(
            CalendarField("title", Events.TITLE),
            CalendarField("description", Events.DESCRIPTION),
            CalendarField("location", Events.EVENT_LOCATION),
            CalendarField("startMillis", Events.DTSTART),
            CalendarField("endMillis", Events.DTEND),
            CalendarField("duration", Events.DURATION),
            CalendarField("timezone", Events.EVENT_TIMEZONE),
            CalendarField("endTimezone", Events.EVENT_END_TIMEZONE),
            CalendarField("allDay", Events.ALL_DAY),
            CalendarField("recurrenceRule", Events.RRULE),
            CalendarField("recurrenceDates", Events.RDATE),
            CalendarField("exceptionRule", Events.EXRULE),
            CalendarField("exceptionDates", Events.EXDATE),
            CalendarField("status", Events.STATUS),
            CalendarField("accessLevel", Events.ACCESS_LEVEL),
            CalendarField("availability", Events.AVAILABILITY),
            CalendarField("organizer", Events.ORGANIZER),
            CalendarField("guestCanModify", Events.GUESTS_CAN_MODIFY),
            CalendarField("guestCanInviteOthers", Events.GUESTS_CAN_INVITE_OTHERS),
            CalendarField("guestCanSeeGuests", Events.GUESTS_CAN_SEE_GUESTS),
            CalendarField("selfAttendeeStatus", Events.SELF_ATTENDEE_STATUS),
            CalendarField("originalEventId", Events.ORIGINAL_ID),
            CalendarField("originalInstanceTime", Events.ORIGINAL_INSTANCE_TIME),
            CalendarField("originalAllDay", Events.ORIGINAL_ALL_DAY),
            CalendarField("hasAttendeeData", Events.HAS_ATTENDEE_DATA),
            CalendarField("hasAlarm", Events.HAS_ALARM),
            CalendarField("hasExtendedProperties", Events.HAS_EXTENDED_PROPERTIES),
        )

        private val ATTENDEE_FIELDS = listOf(
            CalendarField("name", Attendees.ATTENDEE_NAME),
            CalendarField("email", Attendees.ATTENDEE_EMAIL),
            CalendarField("relationship", Attendees.ATTENDEE_RELATIONSHIP),
            CalendarField("type", Attendees.ATTENDEE_TYPE),
            CalendarField("status", Attendees.ATTENDEE_STATUS),
        )

        private val REMINDER_FIELDS = listOf(
            CalendarField("minutes", Reminders.MINUTES),
            CalendarField("method", Reminders.METHOD),
        )
    }
}

internal fun canonicalCalendarEventBytes(
    event: Map<String, JsonElement>,
    attendees: List<JsonObject>,
    reminders: List<JsonObject>,
): ByteArray {
    val document = JsonObject(
        sortedMapOf(
            "attendees" to JsonArray(attendees),
            "event" to sortedJsonObject(event),
            "format" to JsonPrimitive("transferkit-calendar-event"),
            "reminders" to JsonArray(reminders),
            "version" to JsonPrimitive(1),
        ),
    )
    return CALENDAR_JSON.encodeToString(JsonObject.serializer(), document).toByteArray(StandardCharsets.UTF_8)
}

internal fun calendarEventItemId(calendarId: Long, eventId: Long): String =
    "calendar_${sha256Hex("$calendarId:$eventId".toByteArray(StandardCharsets.UTF_8))}"

internal fun portableOriginalEventReference(calendarId: Long, sourceEventId: JsonElement?): JsonElement {
    val sourceId = (sourceEventId as? JsonPrimitive)?.content?.toLongOrNull()
    return if (sourceId != null && sourceId > 0) {
        JsonPrimitive(calendarEventItemId(calendarId, sourceId))
    } else {
        JsonNull
    }
}

internal fun hasUnexportedExtensions(value: JsonElement?): Boolean {
    val primitive = value as? JsonPrimitive ?: return false
    if (primitive == JsonNull) return false
    return primitive.content.toLongOrNull()?.let { it != 0L } ?: (primitive.content == "true")
}

private val CALENDAR_JSON = Json {
    encodeDefaults = true
    prettyPrint = false
}

private fun sortedJsonObject(fields: Map<String, JsonElement>): JsonObject = JsonObject(fields.toSortedMap())

private fun Cursor.jsonValue(index: Int): JsonElement = when (getType(index)) {
    Cursor.FIELD_TYPE_NULL -> JsonNull
    Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(getLong(index))
    Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(getDouble(index))
    Cursor.FIELD_TYPE_STRING -> JsonPrimitive(getString(index))
    Cursor.FIELD_TYPE_BLOB -> throw IOException("Calendar Provider returned an unsupported binary field")
    else -> throw IOException("Calendar Provider returned an unknown field type")
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun checkedAdd(total: Long, increment: Long): Long = try {
    Math.addExact(total, increment)
} catch (_: ArithmeticException) {
    throw IOException("Calendar export exceeds supported byte count")
}
