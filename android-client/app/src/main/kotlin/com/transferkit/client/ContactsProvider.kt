package com.transferkit.client

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract.Contacts
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A contact exported by Android's own vCard encoder. No display name or lookup key is exposed. */
data class ContactVCardItem(
    val id: String,
    internal val vCardUri: Uri,
) {
    fun manifestMetadata(): Map<String, Any> = mapOf("format" to "vCard")
}

data class VCardDigest(val sizeBytes: Long, val sha256: String)

class ContactsProvider(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun hasReadAccess(): Boolean = appContext.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

    /** Enumerates aggregate contacts one row at a time; callers must not retain personal fields in logs. */
    fun forEachContact(onContact: (ContactVCardItem) -> Unit): Int {
        if (!hasReadAccess()) throw SecurityException("READ_CONTACTS is required")

        val cursor = resolver.query(
            Contacts.CONTENT_URI,
            arrayOf(Contacts.LOOKUP_KEY),
            null,
            null,
            "${Contacts._ID} ASC",
        ) ?: throw IOException("Contacts Provider returned no cursor")

        cursor.use {
            val lookupIndex = it.getColumnIndexOrThrow(Contacts.LOOKUP_KEY)
            var count = 0
            while (it.moveToNext()) {
                val lookupKey = it.stringOrNull(lookupIndex)
                    ?: throw IOException("Contacts Provider omitted a contact lookup key")
                val vCardUri = Uri.withAppendedPath(Contacts.CONTENT_VCARD_URI, lookupKey)
                onContact(
                    ContactVCardItem(
                        id = contactItemId(lookupKey),
                        vCardUri = vCardUri,
                    ),
                )
                count += 1
            }
            return count
        }
    }

    fun openVCardStream(item: ContactVCardItem): InputStream =
        resolver.openInputStream(item.vCardUri) ?: throw IOException("Could not open contact vCard")

    /** Copies one vCard without buffering the entire contact and hashes the exact exported bytes. */
    fun exportVCard(item: ContactVCardItem, output: OutputStream): VCardDigest =
        openVCardStream(item).use { input -> copyAndDigest(input, output) }

    /** Reads and hashes a complete item while discarding bytes; useful for safe preflight/QA. */
    fun hashVCard(item: ContactVCardItem): VCardDigest = exportVCard(item, DiscardingOutputStream)

}

internal fun contactItemId(lookupKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(lookupKey.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "contact_$digest"
}

private object DiscardingOutputStream : OutputStream() {
    override fun write(value: Int) = Unit

    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
}

internal fun copyAndDigest(input: InputStream, output: OutputStream): VCardDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var size = 0L

    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            val singleByte = input.read()
            if (singleByte < 0) break
            output.write(singleByte)
            digest.update(singleByte.toByte())
            size = checkedAdd(size, 1)
        } else {
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            size = checkedAdd(size, count.toLong())
        }
    }

    val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return VCardDigest(sizeBytes = size, sha256 = hash)
}

private fun checkedAdd(total: Long, increment: Long): Long = try {
    Math.addExact(total, increment)
} catch (_: ArithmeticException) {
    throw IOException("Exported vCard exceeds supported byte count")
}

private fun Cursor.stringOrNull(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)
