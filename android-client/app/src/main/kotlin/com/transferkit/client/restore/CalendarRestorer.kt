package com.transferkit.client.restore

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class CalendarDestination(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
)

internal data class CalendarEventPayload(
    val event: JsonObject,
    val attendees: List<JsonObject>,
    val reminders: List<JsonObject>,
)

/** Restores only into a calendar the user selected; no calendar or account is silently chosen. */
class CalendarRestorer(
    context: Context,
    private val approvalGate: RestoreApprovalGate,
) : CategoryRestorer {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override val categoryIds: Set<String> = setOf("calendar")

    override fun restore(item: VerifiedRestoreItem, approval: RestoreApproval?): RestoreItemResult =
        restore(item, approval, RestoreContext())

    override fun restore(
        item: VerifiedRestoreItem,
        approval: RestoreApproval?,
        context: RestoreContext,
    ): RestoreItemResult {
        if (item.categoryId !in categoryIds) return failure(item, "UNSUPPORTED")
        if (appContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED
        ) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "CALENDAR_PERMISSION_REQUIRED")
        }
        val calendarId = context.calendarDestinationId
            ?: return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "CALENDAR_DESTINATION_REQUIRED")
        if (item.metadata["format"] != FORMAT || (item.metadata["version"] as? Number)?.toInt() != 1 ||
            item.sizeBytes !in 1..MAX_PAYLOAD_BYTES || !SHA256_PATTERN.matches(item.sha256) ||
            !item.payloadFile.isFile || item.payloadFile.length() != item.sizeBytes
        ) return failure(item, "INVALID_MANIFEST")

        val payload = try {
            val bytes = item.payloadFile.readBytes()
            if (bytes.size.toLong() != item.sizeBytes || sha256(bytes) != item.sha256) return integrityFailure(item)
            parseCalendarPayload(bytes)
        } catch (_: IllegalArgumentException) {
            return failure(item, "INVALID_CALENDAR_PAYLOAD")
        } catch (_: Exception) {
            return failure(item, "INVALID_CALENDAR_PAYLOAD")
        }
        if (!approvalGate.consume(approval, item)) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "RESTORE_CONFIRMATION_REQUIRED")
        }

        return try {
            requireWritableCalendar(calendarId)
            val marker = eventMarker(item)
            val existing = findMarkedEvent(marker)
            if (existing != null) {
                return if (existing.calendarId == calendarId && verifyEvent(existing.id, payload)) {
                    RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
                } else {
                    failure(item, "DESTINATION_MISMATCH")
                }
            }
            insertAndVerify(item, calendarId, marker, payload)
        } catch (_: SecurityException) {
            failure(item, "PERMISSION_DENIED")
        } catch (_: IllegalArgumentException) {
            failure(item, "INVALID_CALENDAR_PAYLOAD")
        } catch (_: IOException) {
            failure(item, "TARGET_WRITE_FAILED")
        } catch (_: Exception) {
            failure(item, "RESTORE_FAILED")
        }
    }

    private fun insertAndVerify(
        item: VerifiedRestoreItem,
        calendarId: Long,
        marker: String,
        payload: CalendarEventPayload,
    ): RestoreItemResult {
        val originalItemId = payload.event["originalItemId"].stringOrNull()
        val originalEventId = if (originalItemId == null) null else {
            val parent = findMarkedEvent(eventMarker(item.copy(itemId = originalItemId)))
                ?: return failure(item, "RECURRENCE_PARENT_NOT_RESTORED")
            if (parent.calendarId != calendarId) return failure(item, "RECURRENCE_PARENT_CALENDAR_MISMATCH")
            parent.id
        }

        val eventValues = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.CUSTOM_APP_PACKAGE, appContext.packageName)
            put(Events.CUSTOM_APP_URI, marker)
            put(Events.HAS_ATTENDEE_DATA, if (payload.attendees.isNotEmpty()) 1 else 0)
            put(Events.HAS_ALARM, if (payload.reminders.isNotEmpty()) 1 else 0)
            if (originalEventId != null) put(Events.ORIGINAL_ID, originalEventId)
            RESTORABLE_EVENT_FIELDS.forEach { (jsonName, column, kind) ->
                payload.event[jsonName]?.let { putJsonValue(this, column, it, kind) }
            }
        }
        val operations = ArrayList<ContentProviderOperation>(1 + payload.attendees.size + payload.reminders.size)
        operations += ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(eventValues).build()
        payload.attendees.forEach { attendee ->
            val values = ContentValues()
            ATTENDEE_FIELDS.forEach { (jsonName, column, kind) ->
                attendee[jsonName]?.let { putJsonValue(values, column, it, kind) }
            }
            operations += ContentProviderOperation.newInsert(Attendees.CONTENT_URI)
                .withValueBackReference(Attendees.EVENT_ID, 0)
                .withValues(values)
                .build()
        }
        payload.reminders.forEach { reminder ->
            val values = ContentValues()
            REMINDER_FIELDS.forEach { (jsonName, column, kind) ->
                reminder[jsonName]?.let { putJsonValue(values, column, it, kind) }
            }
            operations += ContentProviderOperation.newInsert(Reminders.CONTENT_URI)
                .withValueBackReference(Reminders.EVENT_ID, 0)
                .withValues(values)
                .build()
        }
        resolver.applyBatch(Events.CONTENT_URI.authority!!, operations)
        val created = findMarkedEvent(marker) ?: return failure(item, "POST_VERIFY_MISMATCH")
        if (created.calendarId != calendarId || !verifyEvent(created.id, payload)) {
            deleteMarkedEvent(created.id, marker)
            return failure(item, "POST_VERIFY_MISMATCH")
        }
        return RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
    }

    private fun requireWritableCalendar(calendarId: Long) {
        val cursor = resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_ACCESS_LEVEL),
            "${Calendars._ID} = ?",
            arrayOf(calendarId.toString()),
            null,
        ) ?: throw IOException("Calendar Provider returned no calendar cursor")
        cursor.use {
            if (!it.moveToFirst() || it.getInt(it.getColumnIndexOrThrow(Calendars.CALENDAR_ACCESS_LEVEL)) <
                Calendars.CAL_ACCESS_CONTRIBUTOR
            ) throw IOException("Selected calendar is not writable")
        }
    }

    private fun findMarkedEvent(marker: String): MarkedEvent? {
        val cursor = resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.CALENDAR_ID),
            "${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.CUSTOM_APP_URI} = ?",
            arrayOf(appContext.packageName, marker),
            null,
        ) ?: throw IOException("Calendar Provider returned no event cursor")
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            val event = MarkedEvent(
                id = it.getLong(it.getColumnIndexOrThrow(Events._ID)),
                calendarId = it.getLong(it.getColumnIndexOrThrow(Events.CALENDAR_ID)),
            )
            if (it.moveToNext()) throw IOException("TransferKit calendar marker is ambiguous")
            event
        }
    }

    private fun verifyEvent(eventId: Long, payload: CalendarEventPayload): Boolean {
        val projection = RESTORABLE_EVENT_FIELDS.map { it.column }.toTypedArray()
        val cursor = resolver.query(
            Events.CONTENT_URI,
            projection,
            "${Events._ID} = ?",
            arrayOf(eventId.toString()),
            null,
        ) ?: throw IOException("Calendar Provider returned no verification cursor")
        val eventMatches = cursor.use {
            if (!it.moveToFirst()) return@use false
            RESTORABLE_EVENT_FIELDS.all { (jsonName, column, _) ->
                val index = it.getColumnIndexOrThrow(column)
                jsonEquivalent(payload.event[jsonName] ?: JsonNull, it, index)
            }
        }
        return eventMatches && verifyChildren(eventId, payload.attendees, payload.reminders)
    }

    private fun verifyChildren(eventId: Long, attendees: List<JsonObject>, reminders: List<JsonObject>): Boolean {
        val actualAttendees = readRows(
            Attendees.CONTENT_URI,
            ATTENDEE_FIELDS,
            "${Attendees.EVENT_ID} = ?",
            eventId,
        )
        val actualReminders = readRows(
            Reminders.CONTENT_URI,
            REMINDER_FIELDS,
            "${Reminders.EVENT_ID} = ?",
            eventId,
        )
        return expectedRows(attendees, ATTENDEE_FIELDS) == actualAttendees &&
            expectedRows(reminders, REMINDER_FIELDS) == actualReminders
    }

    private fun readRows(uri: Uri, fields: List<ProviderField>, selection: String, eventId: Long): List<List<String?>> {
        val cursor = resolver.query(uri, fields.map { it.column }.toTypedArray(), selection, arrayOf(eventId.toString()), null)
            ?: throw IOException("Calendar Provider returned no child cursor")
        return cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    add(fields.map { field ->
                        val index = rows.getColumnIndexOrThrow(field.column)
                        if (rows.isNull(index)) null else rows.getString(index)
                    })
                }
            }.sortedBy { it.joinToString("\u0000") }
        }
    }

    private fun expectedRows(rows: List<JsonObject>, fields: List<ProviderField>): List<List<String?>> =
        rows.map { row -> fields.map { field -> row[field.jsonName].canonicalPrimitive() } }
            .sortedBy { it.joinToString("\u0000") }

    private fun deleteMarkedEvent(eventId: Long, marker: String) {
        resolver.delete(
            Events.CONTENT_URI,
            "${Events._ID} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.CUSTOM_APP_URI} = ?",
            arrayOf(eventId.toString(), appContext.packageName, marker),
        )
    }

    private data class MarkedEvent(val id: Long, val calendarId: Long)

    private fun failure(item: VerifiedRestoreItem, reason: String) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED, reason)

    private fun integrityFailure(item: VerifiedRestoreItem) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")

    companion object {
        private const val FORMAT = "transferkit-calendar-event-json"
        private const val MAX_PAYLOAD_BYTES = 4L * 1024L * 1024L
        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        private val ROOT_KEYS = setOf("attendees", "event", "format", "reminders", "version")
        private val EVENT_KEYS = setOf(
            "title", "description", "location", "startMillis", "endMillis", "duration", "timezone",
            "endTimezone", "allDay", "recurrenceRule", "recurrenceDates", "exceptionRule", "exceptionDates",
            "status", "accessLevel", "availability", "organizer", "guestCanModify", "guestCanInviteOthers",
            "guestCanSeeGuests", "selfAttendeeStatus", "originalItemId", "originalInstanceTime", "originalAllDay",
            "hasAttendeeData", "hasAlarm", "hasExtendedProperties",
        )
        private val ATTENDEE_KEYS = setOf("name", "email", "relationship", "type", "status")
        private val REMINDER_KEYS = setOf("minutes", "method")
        private val RESTORABLE_EVENT_FIELDS = listOf(
            ProviderField("title", Events.TITLE, ValueKind.STRING),
            ProviderField("description", Events.DESCRIPTION, ValueKind.STRING),
            ProviderField("location", Events.EVENT_LOCATION, ValueKind.STRING),
            ProviderField("startMillis", Events.DTSTART, ValueKind.LONG),
            ProviderField("endMillis", Events.DTEND, ValueKind.LONG),
            ProviderField("duration", Events.DURATION, ValueKind.STRING),
            ProviderField("timezone", Events.EVENT_TIMEZONE, ValueKind.STRING),
            ProviderField("endTimezone", Events.EVENT_END_TIMEZONE, ValueKind.STRING),
            ProviderField("allDay", Events.ALL_DAY, ValueKind.INT),
            ProviderField("recurrenceRule", Events.RRULE, ValueKind.STRING),
            ProviderField("recurrenceDates", Events.RDATE, ValueKind.STRING),
            ProviderField("exceptionRule", Events.EXRULE, ValueKind.STRING),
            ProviderField("exceptionDates", Events.EXDATE, ValueKind.STRING),
            ProviderField("status", Events.STATUS, ValueKind.INT),
            ProviderField("accessLevel", Events.ACCESS_LEVEL, ValueKind.INT),
            ProviderField("availability", Events.AVAILABILITY, ValueKind.INT),
            ProviderField("guestCanModify", Events.GUESTS_CAN_MODIFY, ValueKind.INT),
            ProviderField("guestCanInviteOthers", Events.GUESTS_CAN_INVITE_OTHERS, ValueKind.INT),
            ProviderField("guestCanSeeGuests", Events.GUESTS_CAN_SEE_GUESTS, ValueKind.INT),
            ProviderField("selfAttendeeStatus", Events.SELF_ATTENDEE_STATUS, ValueKind.INT),
            ProviderField("originalInstanceTime", Events.ORIGINAL_INSTANCE_TIME, ValueKind.LONG),
            ProviderField("originalAllDay", Events.ORIGINAL_ALL_DAY, ValueKind.INT),
        )
        private val ATTENDEE_FIELDS = listOf(
            ProviderField("name", Attendees.ATTENDEE_NAME, ValueKind.STRING),
            ProviderField("email", Attendees.ATTENDEE_EMAIL, ValueKind.STRING),
            ProviderField("relationship", Attendees.ATTENDEE_RELATIONSHIP, ValueKind.INT),
            ProviderField("type", Attendees.ATTENDEE_TYPE, ValueKind.INT),
            ProviderField("status", Attendees.ATTENDEE_STATUS, ValueKind.INT),
        )
        private val REMINDER_FIELDS = listOf(
            ProviderField("minutes", Reminders.MINUTES, ValueKind.INT),
            ProviderField("method", Reminders.METHOD, ValueKind.INT),
        )

        fun listWritableCalendars(context: Context): List<CalendarDestination> {
            if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED
            ) throw SecurityException("READ_CALENDAR and WRITE_CALENDAR are required")
            val projection = arrayOf(
                Calendars._ID,
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.ACCOUNT_NAME,
                Calendars.ACCOUNT_TYPE,
                Calendars.CALENDAR_ACCESS_LEVEL,
            )
            val cursor = context.contentResolver.query(
                Calendars.CONTENT_URI,
                projection,
                "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                "${Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE ASC, ${Calendars._ID} ASC",
            ) ?: throw IOException("Calendar Provider returned no destination cursor")
            return cursor.use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        add(
                            CalendarDestination(
                                id = rows.getLong(rows.getColumnIndexOrThrow(Calendars._ID)),
                                displayName = rows.stringOrEmpty(Calendars.CALENDAR_DISPLAY_NAME),
                                accountName = rows.stringOrEmpty(Calendars.ACCOUNT_NAME),
                                accountType = rows.stringOrEmpty(Calendars.ACCOUNT_TYPE),
                            ),
                        )
                    }
                }
            }
        }

        internal fun parseCalendarPayload(bytes: ByteArray): CalendarEventPayload {
            require(bytes.size in 1..MAX_PAYLOAD_BYTES.toInt()) { "calendar payload size invalid" }
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw IllegalArgumentException("calendar envelope must be an object")
            require(root.keys == ROOT_KEYS) { "calendar envelope fields invalid" }
            require(root["format"].stringOrNull() == "transferkit-calendar-event")
            require(root["version"].intOrNull() == 1)
            val event = root["event"] as? JsonObject ?: throw IllegalArgumentException("event must be an object")
            require(event.keys == EVENT_KEYS) { "calendar event fields invalid" }
            require((event["startMillis"] as? JsonPrimitive)?.longOrNull != null) { "startMillis is required" }
            require((event["endMillis"] as? JsonPrimitive)?.longOrNull != null || event["duration"].stringOrNull() != null) {
                "endMillis or duration is required"
            }
            event.forEach { (key, value) ->
                if (key !in setOf("originalItemId")) require(value.isPrimitiveOrNull()) { "event field is not primitive" }
            }
            val original = event["originalItemId"]
            require(original == null || original == JsonNull || original.stringOrNull()?.matches(Regex("calendar_[0-9a-f]{64}")) == true) {
                "originalItemId invalid"
            }
            val attendees = (root["attendees"] as? kotlinx.serialization.json.JsonArray)
                ?.map { it as? JsonObject ?: throw IllegalArgumentException("attendee must be an object") }
                ?: throw IllegalArgumentException("attendees must be an array")
            require(attendees.size <= MAX_CHILD_ROWS && attendees.all { it.keys == ATTENDEE_KEYS && it.values.all(JsonElement::isPrimitiveOrNull) })
            val reminders = (root["reminders"] as? kotlinx.serialization.json.JsonArray)
                ?.map { it as? JsonObject ?: throw IllegalArgumentException("reminder must be an object") }
                ?: throw IllegalArgumentException("reminders must be an array")
            require(reminders.size <= MAX_CHILD_ROWS && reminders.all { it.keys == REMINDER_KEYS && it.values.all(JsonElement::isPrimitiveOrNull) })
            return CalendarEventPayload(event, attendees, reminders)
        }

        internal fun markerFor(transferId: String, manifestSha256: String, itemId: String): String =
            "transferkit://restore/${sha256("$transferId\u0000$manifestSha256\u0000$itemId".toByteArray())}"

        /** Keep recurrence masters ahead of exceptions while preserving non-calendar item positions. */
        internal fun parentFirstCalendarOrder(items: List<VerifiedRestoreItem>): List<VerifiedRestoreItem> {
            val orderedCalendarItems = items.filter { it.categoryId == "calendar" }.sortedBy { item ->
                runCatching {
                    require(item.payloadFile.length() in 1..MAX_PAYLOAD_BYTES)
                    val parsed = parseCalendarPayload(item.payloadFile.readBytes())
                    val original = parsed.event["originalItemId"]
                    original != null && original != JsonNull
                }.getOrDefault(true)
            }.iterator()
            return items.map { item ->
                if (item.categoryId == "calendar") orderedCalendarItems.next() else item
            }
        }

        private const val MAX_CHILD_ROWS = 512
        private enum class ValueKind { STRING, INT, LONG }
        private data class ProviderField(val jsonName: String, val column: String, val kind: ValueKind)
    }

    private fun eventMarker(item: VerifiedRestoreItem) = markerFor(item.transferId, item.manifestSha256, item.itemId)
}

private fun putJsonValue(values: ContentValues, column: String, element: JsonElement, kind: Any) {
    if (element == JsonNull) {
        values.putNull(column)
        return
    }
    val primitive = element as? JsonPrimitive ?: throw IllegalArgumentException("calendar value must be primitive")
    when (kind.toString()) {
        "STRING" -> values.put(column, primitive.contentOrNull ?: throw IllegalArgumentException("string expected"))
        "INT" -> values.put(column, primitive.intOrNull ?: throw IllegalArgumentException("integer expected"))
        "LONG" -> values.put(column, primitive.longOrNull ?: throw IllegalArgumentException("long expected"))
        else -> throw IllegalArgumentException("unsupported calendar field")
    }
}

private fun jsonEquivalent(expected: JsonElement, cursor: Cursor, index: Int): Boolean {
    if (expected == JsonNull) return cursor.isNull(index)
    if (cursor.isNull(index)) return false
    val primitive = expected as? JsonPrimitive ?: return false
    return when {
        primitive.booleanOrNull != null -> primitive.booleanOrNull.toString() == cursor.getString(index) ||
            (if (primitive.booleanOrNull == true) 1 else 0) == cursor.getInt(index)
        primitive.longOrNull != null -> primitive.longOrNull == cursor.getLong(index)
        else -> primitive.contentOrNull == cursor.getString(index)
    }
}

private fun JsonElement?.stringOrNull(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> null
}

private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement.isPrimitiveOrNull(): Boolean = this == JsonNull || this is JsonPrimitive
private fun JsonElement?.canonicalPrimitive(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}
private fun Cursor.stringOrEmpty(column: String): String {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) "" else getString(index).orEmpty()
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
