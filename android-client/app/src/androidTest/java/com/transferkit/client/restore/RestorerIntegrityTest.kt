package com.transferkit.client.restore

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    fun restoresSyntheticGpsExifJpegAndRetryDoesNotDuplicateMediaStoreItem() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val transferId = UUID.randomUUID().toString()
        val manifestSha256 = "d".repeat(64)
        val itemId = "synthetic_gps_exif_restore"
        val sourceBytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("synthetic-gps-exif.jpg")
            .use { it.readBytes() }
        val payload = File(context.cacheDir, "restore-synthetic-exif-$transferId.jpg")
        payload.writeBytes(sourceBytes)
        val marker = sha256("$transferId\u0000photos\u0000$itemId".toByteArray()).take(24)
        val displayName = "tk_${marker}_synthetic-gps-exif.jpg"
        val relativePath = "Pictures/TransferKit/"
        var createdUri: android.net.Uri? = null
        try {
            val item = VerifiedRestoreItem(
                transferId = transferId,
                manifestSha256 = manifestSha256,
                categoryId = "photos",
                itemId = itemId,
                sizeBytes = sourceBytes.size.toLong(),
                sha256 = sha256(sourceBytes),
                metadata = mapOf("path" to "Pictures/TransferKitTest/synthetic-gps-exif.jpg", "mime_type" to "image/jpeg"),
                payloadFile = payload,
            )
            val gate = RestoreApprovalGate()

            fun restoreWithFreshApproval() = MediaStoreRestorer(context, gate).restore(
                item,
                gate.approveAfterUserConfirmation(
                    transferId,
                    manifestSha256,
                    listOf(item),
                    confirmedByUser = true,
                ),
            )

            assertEquals(RestoreItemStatus.RESTORED, restoreWithFreshApproval().status)
            assertPublishedMedia(collection, displayName, relativePath, "image/jpeg", sourceBytes).also {
                createdUri = it
            }

            assertEquals(RestoreItemStatus.RESTORED, restoreWithFreshApproval().status)
            assertPublishedMedia(collection, displayName, relativePath, "image/jpeg", sourceBytes)
        } finally {
            createdUri?.let { resolver.delete(it, null, null) }
            // If an assertion fails before the URI is captured, remove only this
            // random-marker fixture from the exact TransferKit destination folder.
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(displayName, relativePath),
            )
            payload.delete()
        }
    }

    @Test
    fun restoresSyntheticMp4AndRetryDoesNotDuplicateMediaStoreItem() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val transferId = UUID.randomUUID().toString()
        val manifestSha256 = "e".repeat(64)
        val itemId = "synthetic_video_restore"
        val sourceBytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("synthetic-video.mp4")
            .use { it.readBytes() }
        val payload = File(context.cacheDir, "restore-synthetic-video-$transferId.mp4")
        payload.writeBytes(sourceBytes)
        val marker = sha256("$transferId\u0000videos\u0000$itemId".toByteArray()).take(24)
        val displayName = "tk_${marker}_synthetic-video.mp4"
        val relativePath = "Movies/TransferKit/"
        var createdUri: android.net.Uri? = null
        try {
            val item = VerifiedRestoreItem(
                transferId = transferId,
                manifestSha256 = manifestSha256,
                categoryId = "videos",
                itemId = itemId,
                sizeBytes = sourceBytes.size.toLong(),
                sha256 = sha256(sourceBytes),
                metadata = mapOf("path" to "Pictures/TransferKitTest/synthetic-video.mp4", "mime_type" to "video/mp4"),
                payloadFile = payload,
            )
            val gate = RestoreApprovalGate()

            fun restoreWithFreshApproval() = MediaStoreRestorer(context, gate).restore(
                item,
                gate.approveAfterUserConfirmation(
                    transferId,
                    manifestSha256,
                    listOf(item),
                    confirmedByUser = true,
                ),
            )

            assertEquals(RestoreItemStatus.RESTORED, restoreWithFreshApproval().status)
            assertPublishedMedia(collection, displayName, relativePath, "video/mp4", sourceBytes).also {
                createdUri = it
            }

            assertEquals(RestoreItemStatus.RESTORED, restoreWithFreshApproval().status)
            assertPublishedMedia(collection, displayName, relativePath, "video/mp4", sourceBytes)
        } finally {
            createdUri?.let { resolver.delete(it, null, null) }
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(displayName, relativePath),
            )
            payload.delete()
        }
    }

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

    private fun assertPublishedMedia(
        collection: android.net.Uri,
        displayName: String,
        relativePath: String,
        mimeType: String,
        expectedBytes: ByteArray,
    ): android.net.Uri {
        val resolver = context.contentResolver
        val uri = resolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING,
            ),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(displayName, relativePath),
            null,
        )!!.use { cursor ->
            assertTrue("Expected a published synthetic MediaStore row", cursor.moveToFirst())
            assertEquals(displayName, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)))
            assertEquals(relativePath, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)))
            assertEquals(mimeType, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)))
            assertEquals(expectedBytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)))
            val itemUri = ContentUris.withAppendedId(
                collection,
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
            )
            assertTrue("Unexpected duplicate synthetic rows", !cursor.moveToNext())
            itemUri
        }
        val restoredBytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals("Restored synthetic media must preserve its exact source bytes", expectedBytes, restoredBytes)
        assertEquals(sha256(expectedBytes), sha256(restoredBytes))
        return uri
    }
}
