package com.transferkit.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.Telephony
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One text SMS row. MMS and its attachments are deliberately not represented as SMS items. */
class SmsMessageItem internal constructor(
    val id: String,
    private val payload: ByteArray,
) {
    val sizeBytes: Long get() = payload.size.toLong()
    val sha256: String by lazy(LazyThreadSafetyMode.NONE) { sha256Hex(payload) }

    fun manifestMetadata(): Map<String, Any> = mapOf(
        "format" to "transferkit-sms-json",
        "version" to 1,
    )

    fun writeTo(output: OutputStream) = output.write(payload)
}

data class SmsScanSummary(val messageCount: Int, val totalBytes: Long)

/** Read-only source export. Access is checked on every call because restricted grants can change. */
class SmsProvider(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun hasReadAccess(): Boolean = appContext.checkSelfPermission(Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

    /** Exports text SMS only; callers must not retain message content in UI state or logs. */
    fun forEachTextMessage(onMessage: (SmsMessageItem) -> Unit): SmsScanSummary {
        if (!hasReadAccess()) throw SecurityException("READ_SMS is required")

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.READ,
            Telephony.Sms.SEEN,
            Telephony.Sms.TYPE,
            Telephony.Sms.SUBJECT,
            Telephony.Sms.STATUS,
        )
        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms._ID} ASC",
        ) ?: throw IOException("SMS Provider returned no cursor")

        var count = 0
        var totalBytes = 0L
        cursor.use { rows ->
            val indexes = projection.associateWith(rows::getColumnIndexOrThrow)
            while (rows.moveToNext()) {
                val sourceId = rows.getLong(indexes.getValue(Telephony.Sms._ID))
                val payload = canonicalSmsBytes(
                    address = rows.stringOrNull(indexes.getValue(Telephony.Sms.ADDRESS)),
                    body = rows.stringOrNull(indexes.getValue(Telephony.Sms.BODY)),
                    date = rows.getLong(indexes.getValue(Telephony.Sms.DATE)),
                    dateSent = rows.getLong(indexes.getValue(Telephony.Sms.DATE_SENT)),
                    isRead = rows.getInt(indexes.getValue(Telephony.Sms.READ)) != 0,
                    isSeen = rows.getInt(indexes.getValue(Telephony.Sms.SEEN)) != 0,
                    type = rows.getInt(indexes.getValue(Telephony.Sms.TYPE)),
                    subject = rows.stringOrNull(indexes.getValue(Telephony.Sms.SUBJECT)),
                    status = rows.getInt(indexes.getValue(Telephony.Sms.STATUS)),
                )
                if (payload.size > MAX_TEXT_ITEM_BYTES) {
                    throw IOException("SMS Provider returned an item larger than the export limit")
                }
                val item = SmsMessageItem(smsItemId(sourceId), payload)
                totalBytes = checkedAdd(totalBytes, item.sizeBytes)
                onMessage(item)
                count += 1
            }
        }
        return SmsScanSummary(count, totalBytes)
    }
}

/** One call history row; the source row ID is used only to create an opaque item ID. */
class CallLogItem internal constructor(
    val id: String,
    private val payload: ByteArray,
) {
    val sizeBytes: Long get() = payload.size.toLong()
    val sha256: String by lazy(LazyThreadSafetyMode.NONE) { sha256Hex(payload) }

    fun manifestMetadata(): Map<String, Any> = mapOf(
        "format" to "transferkit-call-log-json",
        "version" to 1,
    )

    fun writeTo(output: OutputStream) = output.write(payload)
}

data class CallLogScanSummary(val callCount: Int, val totalBytes: Long)

/** Read-only call history export, guarded by Android's actual READ_CALL_LOG grant. */
class CallLogProvider(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun hasReadAccess(): Boolean = appContext.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED

    fun forEachCall(onCall: (CallLogItem) -> Unit): CallLogScanSummary {
        if (!hasReadAccess()) throw SecurityException("READ_CALL_LOG is required")

        val projection = arrayOf(
            android.provider.CallLog.Calls._ID,
            android.provider.CallLog.Calls.NUMBER,
            android.provider.CallLog.Calls.DATE,
            android.provider.CallLog.Calls.DURATION,
            android.provider.CallLog.Calls.TYPE,
            android.provider.CallLog.Calls.NEW,
            android.provider.CallLog.Calls.IS_READ,
            android.provider.CallLog.Calls.COUNTRY_ISO,
            android.provider.CallLog.Calls.NUMBER_PRESENTATION,
        )
        val cursor = resolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${android.provider.CallLog.Calls._ID} ASC",
        ) ?: throw IOException("Call Log Provider returned no cursor")

        var count = 0
        var totalBytes = 0L
        cursor.use { rows ->
            val indexes = projection.associateWith(rows::getColumnIndexOrThrow)
            while (rows.moveToNext()) {
                val sourceId = rows.getLong(indexes.getValue(android.provider.CallLog.Calls._ID))
                val payload = canonicalCallLogBytes(
                    number = rows.stringOrNull(indexes.getValue(android.provider.CallLog.Calls.NUMBER)),
                    date = rows.getLong(indexes.getValue(android.provider.CallLog.Calls.DATE)),
                    duration = rows.getLong(indexes.getValue(android.provider.CallLog.Calls.DURATION)),
                    type = rows.getInt(indexes.getValue(android.provider.CallLog.Calls.TYPE)),
                    isNew = rows.getInt(indexes.getValue(android.provider.CallLog.Calls.NEW)) != 0,
                    isRead = rows.getInt(indexes.getValue(android.provider.CallLog.Calls.IS_READ)) != 0,
                    countryIso = rows.stringOrNull(indexes.getValue(android.provider.CallLog.Calls.COUNTRY_ISO)),
                    numberPresentation = rows.getInt(
                        indexes.getValue(android.provider.CallLog.Calls.NUMBER_PRESENTATION),
                    ),
                )
                if (payload.size > MAX_TEXT_ITEM_BYTES) {
                    throw IOException("Call Log Provider returned an item larger than the export limit")
                }
                val item = CallLogItem(callLogItemId(sourceId), payload)
                totalBytes = checkedAdd(totalBytes, item.sizeBytes)
                onCall(item)
                count += 1
            }
        }
        return CallLogScanSummary(count, totalBytes)
    }
}

internal fun canonicalSmsBytes(
    address: String?,
    body: String?,
    date: Long,
    dateSent: Long,
    isRead: Boolean,
    isSeen: Boolean,
    type: Int,
    subject: String?,
    status: Int,
): ByteArray = canonicalJsonBytes(
    mapOf(
        "address" to address.jsonOrNull(),
        "body" to body.jsonOrNull(),
        "dateMillis" to JsonPrimitive(date),
        "dateSentMillis" to JsonPrimitive(dateSent),
        "format" to JsonPrimitive("transferkit-sms"),
        "isRead" to JsonPrimitive(isRead),
        "isSeen" to JsonPrimitive(isSeen),
        "status" to JsonPrimitive(status),
        "subject" to subject.jsonOrNull(),
        "type" to JsonPrimitive(type),
        "version" to JsonPrimitive(1),
    ),
)

internal fun canonicalCallLogBytes(
    number: String?,
    date: Long,
    duration: Long,
    type: Int,
    isNew: Boolean,
    isRead: Boolean,
    countryIso: String?,
    numberPresentation: Int,
): ByteArray = canonicalJsonBytes(
    mapOf(
        "countryIso" to countryIso.jsonOrNull(),
        "dateMillis" to JsonPrimitive(date),
        "durationSeconds" to JsonPrimitive(duration),
        "format" to JsonPrimitive("transferkit-call-log"),
        "isNew" to JsonPrimitive(isNew),
        "isRead" to JsonPrimitive(isRead),
        "number" to number.jsonOrNull(),
        "numberPresentation" to JsonPrimitive(numberPresentation),
        "type" to JsonPrimitive(type),
        "version" to JsonPrimitive(1),
    ),
)

private fun canonicalJsonBytes(fields: Map<String, JsonElement>): ByteArray =
    JSON.encodeToString(JsonObject.serializer(), JsonObject(fields.toSortedMap()))
        .toByteArray(StandardCharsets.UTF_8)

private fun String?.jsonOrNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

private fun smsItemId(sourceId: Long): String = opaqueItemId("sms", sourceId)

private fun callLogItemId(sourceId: Long): String = opaqueItemId("call", sourceId)

private fun opaqueItemId(kind: String, sourceId: Long): String =
    "${kind}_${sha256Hex("$kind:$sourceId".toByteArray(StandardCharsets.UTF_8))}"

private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun checkedAdd(total: Long, increment: Long): Long = try {
    Math.addExact(total, increment)
} catch (_: ArithmeticException) {
    throw IOException("Export exceeds supported byte count")
}

private const val MAX_TEXT_ITEM_BYTES = 1024 * 1024
private val JSON = Json { encodeDefaults = true; prettyPrint = false }
