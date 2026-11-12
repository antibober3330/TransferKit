package com.transferkit.client.restore

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestorerIntegrityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sameTransferMarkerWithDifferentBytesRemainsFailedIntegrity() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = context.contentResolver
        val transferId = UUID.randomUUID().toString()
        val itemId = "integrity-fixture"
        val expected = "expected transfer bytes".toByteArray()
        val corrupted = "corrupted transfer bytes".toByteArray()
        val payload = File(context.cacheDir, "restore-integrity-${UUID.randomUUID()}.bin")
        payload.writeBytes(expected)
        val marker = sha256("$transferId\u0000photos\u0000$itemId".toByteArray()).take(24)
        val displayName = "tk_${marker}_fixture.jpg"
        val relativePath = "Pictures/TransferKit/"
        var createdUri: android.net.Uri? = null
        try {
            val uri = resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: throw AssertionError("Could not create isolated MediaStore fixture")
            createdUri = uri
            resolver.openOutputStream(uri, "w")!!.use { it.write(corrupted) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )

            val item = VerifiedRestoreItem(
                transferId = transferId,
                manifestSha256 = "a".repeat(64),
                categoryId = "photos",
                itemId = itemId,
                sizeBytes = expected.size.toLong(),
                sha256 = sha256(expected),
                metadata = mapOf("path" to "fixture.jpg", "mime_type" to "image/jpeg"),
                payloadFile = payload,
            )
            val approvalGate = RestoreApprovalGate()
            val approval = approvalGate.approveAfterUserConfirmation(
                transferId,
                item.manifestSha256,
                listOf(item),
                confirmedByUser = true,
            )

            val result = MediaStoreRestorer(context, approvalGate).restore(item, approval)
            assertEquals(RestoreItemStatus.FAILED_INTEGRITY, result.status)
            assertEquals("SHA256_MISMATCH", result.errorReason)
        } finally {
            createdUri?.let { resolver.delete(it, null, null) }
            payload.delete()
        }
    }

    @Test
    fun newlyRestoredItemIsReadBackAfterPublishBeforeReportingRestored() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val transferId = UUID.randomUUID().toString()
        val itemId = "post-verify-fixture"
        val bytes = "post publish destination bytes".toByteArray()
        val payload = File(context.cacheDir, "restore-post-verify-${UUID.randomUUID()}.bin")
        payload.writeBytes(bytes)
        val marker = sha256("$transferId\u0000photos\u0000$itemId".toByteArray()).take(24)
        val displayName = "tk_${marker}_fixture.jpg"
        val relativePath = "Pictures/TransferKit/"
        var createdUri: android.net.Uri? = null
        try {
            val item = VerifiedRestoreItem(
                transferId = transferId,
                manifestSha256 = "b".repeat(64),
                categoryId = "photos",
                itemId = itemId,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256(bytes),
                metadata = mapOf("path" to "fixture.jpg", "mime_type" to "image/jpeg"),
                payloadFile = payload,
            )
            val approvalGate = RestoreApprovalGate()
            val approval = approvalGate.approveAfterUserConfirmation(
                transferId,
                item.manifestSha256,
                listOf(item),
                confirmedByUser = true,
            )

            val result = MediaStoreRestorer(context, approvalGate).restore(item, approval)
            assertEquals(RestoreItemStatus.RESTORED, result.status)

            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(displayName, relativePath),
                null,
            )!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                createdUri = ContentUris.withAppendedId(
                    collection,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                )
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)))
                assertTrue("A unique fixture should create one row", !cursor.moveToNext())
            }
            assertArrayEquals(bytes, resolver.openInputStream(createdUri!!)!!.use { it.readBytes() })
        } finally {
            createdUri?.let { resolver.delete(it, null, null) }
            payload.delete()
        }
    }

    @Test
    fun changingDestinationBytesAfterPublishIsNotReportedAsRestored() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val transferId = UUID.randomUUID().toString()
        val itemId = "post-publish-corruption-fixture"
        val expected = "manifest bytes".toByteArray()
        val payload = File(context.cacheDir, "restore-post-publish-${UUID.randomUUID()}.bin")
        payload.writeBytes(expected)
        val marker = sha256("$transferId\u0000photos\u0000$itemId".toByteArray()).take(24)
        val displayName = "tk_${marker}_fixture.jpg"
        val relativePath = "Pictures/TransferKit/"
        var createdUri: android.net.Uri? = null
        try {
            val item = VerifiedRestoreItem(
                transferId = transferId,
                manifestSha256 = "c".repeat(64),
                categoryId = "photos",
                itemId = itemId,
                sizeBytes = expected.size.toLong(),
                sha256 = sha256(expected),
                metadata = mapOf("path" to "fixture.jpg", "mime_type" to "image/jpeg"),
                payloadFile = payload,
            )
            val approvalGate = RestoreApprovalGate()
            val approval = approvalGate.approveAfterUserConfirmation(
                transferId,
                item.manifestSha256,
                listOf(item),
                confirmedByUser = true,
            )
            val result = MediaStoreRestorer(context, approvalGate).restore(item, approval)
            assertEquals(RestoreItemStatus.RESTORED, result.status)

            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(displayName, relativePath),
                null,
            )!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                createdUri = ContentUris.withAppendedId(
                    collection,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                )
            }
            resolver.openOutputStream(createdUri!!, "w")!!.use { it.write("changed after publish".toByteArray()) }

            val retryApproval = approvalGate.approveAfterUserConfirmation(
                transferId,
                item.manifestSha256,
                listOf(item),
                confirmedByUser = true,
            )
            val retry = MediaStoreRestorer(context, approvalGate).restore(item, retryApproval)
            assertEquals(RestoreItemStatus.FAILED_INTEGRITY, retry.status)
            assertEquals("SHA256_MISMATCH", retry.errorReason)
        } finally {
            createdUri?.let { resolver.delete(it, null, null) }
            payload.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
