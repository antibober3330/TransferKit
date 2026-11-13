package com.transferkit.client.restore

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Imports one vCard into the device-local Contacts account without touching existing contacts. */
class ContactsRestorer(
    context: Context,
    private val approvalGate: RestoreApprovalGate,
) : CategoryRestorer {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override val categoryIds: Set<String> = setOf("contacts")

    @Synchronized
    override fun restore(item: VerifiedRestoreItem, approval: RestoreApproval?): RestoreItemResult {
        if (item.categoryId !in categoryIds) return failure(item, "UNSUPPORTED")
        if (appContext.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "READ_CONTACTS_REQUIRED")
        }
        if (appContext.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "WRITE_CONTACTS_REQUIRED")
        }
        if (item.metadata["format"] != "vCard" || item.sizeBytes !in 1..VCardContactParser.MAX_VCARD_BYTES.toLong() ||
            !SHA256_PATTERN.matches(item.sha256) || !item.payloadFile.isFile || item.payloadFile.length() != item.sizeBytes
        ) {
            return failure(item, "INVALID_MANIFEST")
        }

        val parsed = try {
            val (size, digest, bytes) = digestAndRead(item.payloadFile)
            if (size != item.sizeBytes || digest != item.sha256) return integrityFailure(item)
            VCardContactParser.parse(bytes)
        } catch (failure: IllegalArgumentException) {
            val reason = failure.message?.takeIf { it in VCARD_FAILURES } ?: "UNSUPPORTED_VCARD"
            return failure(item, reason)
        } catch (_: Exception) {
            return failure(item, "INVALID_VCARD")
        }

        if (!approvalGate.consume(approval, item)) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "RESTORE_CONFIRMATION_REQUIRED")
        }

        val sourceId = sourceMarker(item)
        return try {
            val existingId = findRawContact(sourceId)
            if (existingId != null) {
                return if (verifyRawContact(existingId, parsed)) {
                    RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
                } else {
                    failure(item, "DESTINATION_MISMATCH")
                }
            }

            val operations = ArrayList<ContentProviderOperation>(parsed.rows.size + 1)
            operations += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.SOURCE_ID, sourceId)
                .build()
            parsed.rows.forEach { row ->
                val values = ContentValues().apply {
                    put(Data.MIMETYPE, row.mimeType)
                    row.values.forEach { (key, value) -> putValue(this, key, value) }
                }
                operations += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValues(values)
                    .build()
            }
            resolver.applyBatch(ContactsContract.AUTHORITY, operations)

            val createdId = findRawContact(sourceId) ?: return failure(item, "POST_VERIFY_MISMATCH")
            if (!verifyRawContact(createdId, parsed)) {
                // Remove only the local RawContact created for this exact transfer item.
                resolver.delete(
                    RawContacts.CONTENT_URI,
                    "${RawContacts._ID} = ? AND ${RawContacts.SOURCE_ID} = ?",
                    arrayOf(createdId.toString(), sourceId),
                )
                return failure(item, "POST_VERIFY_MISMATCH")
            }
            RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
        } catch (_: SecurityException) {
            failure(item, "PERMISSION_DENIED")
        } catch (_: IOException) {
            failure(item, "TARGET_WRITE_FAILED")
        } catch (_: Exception) {
            failure(item, "RESTORE_FAILED")
        }
    }

    private fun findRawContact(sourceId: String): Long? {
        val cursor = resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            "${RawContacts.SOURCE_ID} = ?",
            arrayOf(sourceId),
            null,
        ) ?: throw IOException("Contacts Provider returned no cursor")
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            val id = it.getLong(it.getColumnIndexOrThrow(RawContacts._ID))
            if (it.moveToNext()) throw IOException("TransferKit contact marker is ambiguous")
            id
        }
    }

    private fun verifyRawContact(rawContactId: Long, expected: ParsedVCardContact): Boolean {
        val projection = buildList {
            add(Data.MIMETYPE)
            addAll(DATA_COLUMNS)
        }.toTypedArray()
        val cursor = resolver.query(
            Data.CONTENT_URI,
            projection,
            "${Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null,
        ) ?: throw IOException("Contacts Provider returned no data cursor")
        val actual = cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val mime = rows.getString(rows.getColumnIndexOrThrow(Data.MIMETYPE))
                    val values = DATA_COLUMNS.associateWith { column ->
                        val index = rows.getColumnIndexOrThrow(column)
                        if (rows.isNull(index)) {
                            null
                        } else if (column == Data.DATA15 && rows.getType(index) == android.database.Cursor.FIELD_TYPE_BLOB) {
                            rows.getBlob(index)?.let(::bytesKey)
                        } else {
                            rows.getString(index)
                        }
                    }
                    add(mime to values)
                }
            }
        }
        val wanted = expected.rows.map { row ->
            val values = row.values.mapValues { (column, value) ->
                if (column == Data.DATA15 && value is ByteArray) bytesKey(value) else value.toString()
            }
            row.mimeType to values
        }
        val actualByMime = actual.groupBy { it.first }
        val expectedByMime = wanted.groupBy { it.first }
        if (actual.size != wanted.size || actualByMime.mapValues { it.value.size } != expectedByMime.mapValues { it.value.size }) {
            return false
        }
        for ((mime, expectedRows) in expectedByMime) {
            val remaining = actualByMime[mime].orEmpty().toMutableList()
            for (expectedRow in expectedRows) {
                val matchIndex = remaining.indexOfFirst { actualRow ->
                    expectedRow.second.all { (column, expectedValue) -> actualRow.second[column] == expectedValue }
                }
                if (matchIndex < 0) {
                    return false
                }
                remaining.removeAt(matchIndex)
            }
        }
        return true
    }

    private fun putValue(values: ContentValues, key: String, value: Any) {
        when (value) {
            is String -> values.put(key, value)
            is Int -> values.put(key, value)
            is ByteArray -> values.put(key, value)
            else -> throw IllegalArgumentException("Unsupported contact data type")
        }
    }

    private fun digestAndRead(file: File): Triple<Long, String, ByteArray> {
        if (file.length() !in 1..VCardContactParser.MAX_VCARD_BYTES.toLong()) {
            throw IllegalArgumentException("VCARD_SIZE_LIMIT")
        }
        val bytes = file.inputStream().use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Triple(bytes.size.toLong(), digest, bytes)
    }

    private fun sourceMarker(item: VerifiedRestoreItem): String {
        val value = "${item.transferId}\u0000${item.manifestSha256}\u0000${item.itemId}"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "transferkit-v1-$digest"
    }

    private fun bytesKey(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun failure(item: VerifiedRestoreItem, reason: String) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED, reason)

    private fun integrityFailure(item: VerifiedRestoreItem) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")

    private companion object {
        val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        val DATA_COLUMNS = listOf(
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5,
            Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10,
            Data.DATA11, Data.DATA12, Data.DATA13, Data.DATA14, Data.DATA15,
        )
        val VCARD_FAILURES = setOf(
            "VCARD_SIZE_LIMIT", "VCARD_PROPERTY_LIMIT", "INVALID_VCARD", "UNSUPPORTED_VCARD_VERSION",
            "UNSUPPORTED_VCARD_PROPERTY", "UNSUPPORTED_VCARD_ENCODING", "VCARD_PHOTO_LIMIT",
        )
    }
}
