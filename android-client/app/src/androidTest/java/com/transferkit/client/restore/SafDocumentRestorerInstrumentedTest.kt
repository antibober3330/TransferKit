package com.transferkit.client.restore

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafDocumentRestorerInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver = context.contentResolver
    private val treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)
    private val payloads = mutableListOf<File>()

    @Before
    fun prepareIsolatedProvider() {
        callProvider(METHOD_CLEAR)
        callProvider(METHOD_GRANT, context.packageName)
    }

    @After
    fun clearIsolatedProvider() {
        callProvider(METHOD_CLEAR)
        context.revokeUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        payloads.forEach { it.delete() }
        payloads.clear()
    }

    @Test
    fun verifiedDocumentIsPublishedAndRetryDoesNotCreateDuplicate() {
        val item = item("report.txt", "TransferKit SAF fixture bytes".toByteArray())

        val first = restore(item)
        assertEquals("status=${first.status}, reason=${first.errorReason}", RestoreItemStatus.RESTORED, first.status)
        val destination = SafDocumentDestination.from(item)!!
        val published = children().filter { it.first == destination.displayName }
        assertEquals(1, published.size)
        assertArrayEquals(item.payloadFile.readBytes(), resolver.openInputStream(published.single().second)!!.use { it.readBytes() })

        val retry = restore(item)
        assertEquals(RestoreItemStatus.RESTORED, retry.status)
        assertEquals(1, children().count { it.first == destination.displayName })
    }

    @Test
    fun selectedTreeGrantIsPersistedAndReleasedOnlyWhenOwnedByTransferKit() {
        assertTrue(resolver.persistedUriPermissions.none { it.uri == treeUri })

        val selection = SafDocumentTreeAccess.acquire(context, treeUri)

        assertEquals(ROOT_ID, DocumentsContract.getTreeDocumentId(selection.uri))
        assertEquals("TransferKit synthetic test", selection.displayName)
        assertTrue(selection.releaseGrantOnFinish)
        val persisted = resolver.persistedUriPermissions.single { it.uri == treeUri }
        assertTrue(persisted.isReadPermission)
        assertTrue(persisted.isWritePermission)
        assertEquals(selection.uri, SafDocumentTreeAccess.restoreSaved(context)?.uri)

        SafDocumentTreeAccess.releaseIfOwned(context, selection)

        assertTrue(resolver.persistedUriPermissions.none { it.uri == treeUri })
        assertEquals(null, SafDocumentTreeAccess.restoreSaved(context))
    }

    @Test
    fun sourceBytesThatDifferFromManifestAreRejectedBeforeCreatingAnything() {
        val expected = "expected document".toByteArray()
        val item = item("source-mismatch.txt", expected)
        item.payloadFile.writeBytes("changed after staging".toByteArray())

        val result = restore(item)

        assertEquals("status=${result.status}, reason=${result.errorReason}", RestoreItemStatus.FAILED_INTEGRITY, result.status)
        assertEquals("SHA256_MISMATCH", result.errorReason)
        assertTrue(children().isEmpty())
    }

    @Test
    fun corruptExistingTransferMarkerIsNotOverwritten() {
        val item = item("existing-marker.txt", "expected target bytes".toByteArray())
        val destination = SafDocumentDestination.from(item)!!
        val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val existing = DocumentsContract.createDocument(
            resolver,
            parentDocumentUri,
            destination.mimeType,
            destination.displayName,
        )!!
        resolver.openOutputStream(existing, "w")!!.use { it.write("user or corrupted bytes".toByteArray()) }

        val result = restore(item)

        assertEquals(RestoreItemStatus.FAILED_INTEGRITY, result.status)
        assertArrayEquals("user or corrupted bytes".toByteArray(), resolver.openInputStream(existing)!!.use { it.readBytes() })
        assertEquals(1, children().count { it.first == destination.displayName })
    }

    @Test
    fun providerWithoutRenameFailsClosedAndRemovesItsPendingFile() {
        callProvider(METHOD_SET_RENAME, extras = Bundle().apply { putBoolean(KEY_RENAME_SUPPORTED, false) })
        val item = item("no-rename.txt", "verified bytes".toByteArray())

        val result = restore(item)

        assertEquals("status=${result.status}, reason=${result.errorReason}", RestoreItemStatus.FAILED, result.status)
        assertEquals("TARGET_WRITE_FAILED", result.errorReason)
        assertTrue("an unfinished .part file must not remain", children().isEmpty())
    }

    @Test
    fun missingDestinationReturnsUserActionRequiredWithoutConsumingApproval() {
        val item = item("missing-tree.txt", "no destination yet".toByteArray())
        val gate = RestoreApprovalGate()
        val approval = approve(gate, item)

        val result = SafDocumentRestorer(context, gate).restore(item, approval)

        assertEquals(RestoreItemStatus.USER_ACTION_REQUIRED, result.status)
        assertEquals("SAF_DESTINATION_REQUIRED", result.errorReason)
        assertEquals(
            RestoreItemStatus.RESTORED,
            SafDocumentRestorer(context, gate).restore(
                item,
                approval,
                RestoreContext(documentTreeUri = treeUri),
            ).status,
        )
    }

    private fun restore(item: VerifiedRestoreItem): RestoreItemResult {
        val gate = RestoreApprovalGate()
        return SafDocumentRestorer(context, gate).restore(item, approve(gate, item), RestoreContext(documentTreeUri = treeUri))
    }

    private fun approve(gate: RestoreApprovalGate, item: VerifiedRestoreItem): RestoreApproval =
        requireNotNull(
            gate.approveAfterUserConfirmation(
                transferId = item.transferId,
                manifestSha256 = item.manifestSha256,
                items = listOf(item),
                confirmedByUser = true,
            ),
        )

    private fun item(name: String, bytes: ByteArray) = VerifiedRestoreItem(
        transferId = UUID.randomUUID().toString(),
        manifestSha256 = "a".repeat(64),
        categoryId = "documents",
        itemId = "doc-${UUID.randomUUID()}",
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256(bytes),
        metadata = mapOf("path" to name, "mime_type" to "text/plain"),
        payloadFile = File(context.cacheDir, "saf-restore-${UUID.randomUUID()}.fixture").also {
            it.writeBytes(bytes)
            payloads += it
        },
    )

    private fun children(): List<Pair<String, Uri>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, ROOT_ID)
        val result = mutableListOf<Pair<String, Uri>>()
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )!!.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex)
                result += name to DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            }
        }
        return result
    }

    private fun callProvider(method: String, arg: String? = null, extras: Bundle? = null) {
        resolver.call(Uri.parse("content://$AUTHORITY"), method, arg, extras)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val AUTHORITY = "com.transferkit.client.test.synthetic-documents"
        const val ROOT_ID = "root"
        const val METHOD_GRANT = "transferkit_test_grant_tree"
        const val METHOD_CLEAR = "transferkit_test_clear"
        const val METHOD_SET_RENAME = "transferkit_test_set_rename"
        const val KEY_RENAME_SUPPORTED = "rename_supported"
    }
}
