package com.transferkit.client.restore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical acceptance: use the system picker, then write/read/delete one synthetic document. */
@RunWith(AndroidJUnit4::class)
class SafSystemPickerAcceptanceTest {
    @Test
    fun selectedSystemFolderPublishesSyntheticDocumentAndReleasesOwnedGrant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Manual physical picker acceptance; rerun with runPhysicalSafPicker=true",
            InstrumentationRegistry.getArguments().getString("runPhysicalSafPicker") == "true",
        )
        val appContext = instrumentation.targetContext
        val resolver = appContext.contentResolver
        val pickerState = appContext.getSharedPreferences(
            SafSystemPickerProbeActivity.PREFERENCES,
            Context.MODE_PRIVATE,
        )
        pickerState.edit().clear().commit()

        val launchPicker = Intent(appContext, SafSystemPickerProbeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(launchPicker)

        val deadline = SystemClock.elapsedRealtime() + PICKER_TIMEOUT_MILLIS
        var selectedUri: Uri? = null
        var selectedFlags = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val snapshot = pickerState.all
            val rawUri = snapshot[SafSystemPickerProbeActivity.KEY_URI] as? String
            if (rawUri != null) {
                selectedUri = Uri.parse(rawUri)
                selectedFlags = snapshot[SafSystemPickerProbeActivity.KEY_FLAGS] as? Int ?: 0
                break
            }
            if (snapshot[SafSystemPickerProbeActivity.KEY_RESULT] as? Int == android.app.Activity.RESULT_CANCELED) {
                throw AssertionError("System document tree picker was cancelled")
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }

        val treeUri = selectedUri
            ?: throw AssertionError("Timed out waiting for an actual ACTION_OPEN_DOCUMENT_TREE selection")
        assertTrue(DocumentsContract.isTreeUri(treeUri))
        assertTrue(selectedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(selectedFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)

        var selection: SafDocumentTreeSelection? = null
        var payload: File? = null
        var itemForCleanup: VerifiedRestoreItem? = null
        var createdDocumentUri: Uri? = null
        try {
            selection = SafDocumentTreeAccess.acquire(appContext, treeUri)
            val transferId = UUID.randomUUID().toString()
            val manifestSha256 = "f".repeat(64)
            val itemId = "system-picker-${UUID.randomUUID()}"
            val bytes = "TransferKit physical SAF synthetic acceptance $transferId".toByteArray()
            payload = File(appContext.cacheDir, "saf-system-picker-$transferId.fixture").apply {
                writeBytes(bytes)
            }
            val item = VerifiedRestoreItem(
                transferId = transferId,
                manifestSha256 = manifestSha256,
                categoryId = "documents",
                itemId = itemId,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256(bytes),
                metadata = mapOf("path" to "transferkit-saf-$transferId.txt", "mime_type" to "text/plain"),
                payloadFile = payload,
            )
            itemForCleanup = item
            val gate = RestoreApprovalGate()

            fun restoreWithFreshApproval() = SafDocumentRestorer(appContext, gate).restore(
                item,
                gate.approveAfterUserConfirmation(
                    transferId,
                    manifestSha256,
                    listOf(item),
                    confirmedByUser = true,
                ),
                RestoreContext(documentTreeUri = selection!!.uri),
            )

            val firstRestore = restoreWithFreshApproval()
            assertEquals(
                "status=${firstRestore.status}, reason=${firstRestore.errorReason}",
                RestoreItemStatus.RESTORED,
                firstRestore.status,
            )
            val publishedDocuments = findPublishedDocuments(appContext, selection!!.uri, item)
            assertEquals("Expected exactly one synthetic restored document", 1, publishedDocuments.size)
            createdDocumentUri = publishedDocuments.single()
            assertArrayEquals(bytes, resolver.openInputStream(createdDocumentUri!!)!!.use { it.readBytes() })

            val retryRestore = restoreWithFreshApproval()
            assertEquals(
                "status=${retryRestore.status}, reason=${retryRestore.errorReason}",
                RestoreItemStatus.RESTORED,
                retryRestore.status,
            )
            val retryDocuments = findPublishedDocuments(appContext, selection!!.uri, item)
            assertEquals("Retry must not publish a duplicate", 1, retryDocuments.size)
            val retryUri = retryDocuments.single()
            assertEquals(createdDocumentUri, retryUri)
            assertArrayEquals(bytes, resolver.openInputStream(retryUri)!!.use { it.readBytes() })
        } finally {
            val selectedTreeUri = selection?.uri
            val expectedItem = itemForCleanup
            if (selectedTreeUri != null && expectedItem != null) {
                runCatching {
                    findPublishedDocuments(appContext, selectedTreeUri, expectedItem)
                        .forEach { DocumentsContract.deleteDocument(resolver, it) }
                }
            } else {
                createdDocumentUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            }
            payload?.delete()
            selection?.let { SafDocumentTreeAccess.releaseIfOwned(appContext, it) }
        }

        assertTrue(
            "The test-owned persistable tree grant must be released",
            resolver.persistedUriPermissions.none { it.uri == treeUri },
        )
    }

    private fun findPublishedDocuments(
        context: Context,
        treeUri: Uri,
        item: VerifiedRestoreItem,
    ): List<Uri> {
        val destination = SafDocumentDestination.from(item)!!
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val matches = mutableListOf<Uri>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )!!.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == destination.displayName) {
                    matches += DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                }
            }
        }
        return matches
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val PICKER_TIMEOUT_MILLIS = 90 * 1000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
