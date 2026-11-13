package com.transferkit.client.restore

import android.content.ContentResolver
import android.content.pm.PackageManager
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactsRestorerInstrumentedTest {
    @Test
    fun importsOnlySyntheticLocalContactAndRetryDoesNotDuplicateIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS))

        val transferId = UUID.randomUUID().toString()
        val manifestSha256 = "a".repeat(64)
        val itemId = "contact_instrumented_fixture"
        val card = """BEGIN:VCARD
VERSION:3.0
N:TransferKit;Synthetic;;;
FN:TransferKit Synthetic Test Contact
TEL;TYPE=CELL:+15550000001
EMAIL;TYPE=HOME:synthetic@example.invalid
END:VCARD""".toByteArray(Charsets.UTF_8)
        val payload = File(context.cacheDir, "contact-fixture-$transferId.vcf")
        payload.writeBytes(card)
        val sha256 = sha256(card)
        val item = VerifiedRestoreItem(
            transferId = transferId,
            manifestSha256 = manifestSha256,
            categoryId = "contacts",
            itemId = itemId,
            sizeBytes = card.size.toLong(),
            sha256 = sha256,
            metadata = mapOf("format" to "vCard"),
            payloadFile = payload,
        )
        val gate = RestoreApprovalGate()
        val restorer = ContactsRestorer(context, gate)
        val sourceId = sourceMarker(transferId, manifestSha256, itemId)

        try {
            val first = restorer.restore(item, approve(gate, item))
            assertEquals("first restore result=${first.status} reason=${first.errorReason}", RestoreItemStatus.RESTORED, first.status)
            assertEquals(null, first.errorReason)
            assertSingleRawContact(resolver, sourceId)

            val retry = restorer.restore(item, approve(gate, item))
            assertEquals("retry result=${retry.status} reason=${retry.errorReason}", RestoreItemStatus.RESTORED, retry.status)
            assertSingleRawContact(resolver, sourceId)
        } finally {
            try {
                deleteOnlyMarkedRawContact(resolver, sourceId)
                assertEquals(0, countMarkedRawContacts(resolver, sourceId))
            } finally {
                payload.delete()
            }
        }
    }

    private fun approve(gate: RestoreApprovalGate, item: VerifiedRestoreItem) =
        requireNotNull(
            gate.approveAfterUserConfirmation(
                item.transferId,
                item.manifestSha256,
                listOf(item),
                confirmedByUser = true,
            ),
        )

    private fun assertSingleRawContact(resolver: ContentResolver, sourceId: String) {
        val cursor = resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            "${RawContacts.SOURCE_ID} = ?",
            arrayOf(sourceId),
            null,
        )
        assertNotNull(cursor)
        cursor!!.use {
            assertEquals(1, it.count)
        }
    }

    private fun deleteOnlyMarkedRawContact(resolver: ContentResolver, sourceId: String) {
        resolver.delete(
            RawContacts.CONTENT_URI,
            "${RawContacts.SOURCE_ID} = ?",
            arrayOf(sourceId),
        )
    }

    private fun countMarkedRawContacts(resolver: ContentResolver, sourceId: String): Int {
        val cursor = resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            "${RawContacts.SOURCE_ID} = ?",
            arrayOf(sourceId),
            null,
        ) ?: error("Contacts Provider returned no marker cursor")
        return cursor.use { it.count }
    }

    private fun sourceMarker(transferId: String, manifestSha256: String, itemId: String): String =
        "transferkit-v1-${sha256("$transferId\u0000$manifestSha256\u0000$itemId".toByteArray(Charsets.UTF_8))}"

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
