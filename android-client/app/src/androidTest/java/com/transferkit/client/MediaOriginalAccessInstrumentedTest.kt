package com.transferkit.client

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaOriginalAccessInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun explicitPhotoOptInHonorsCurrentPermissionState() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val provider = MediaProvider(context)
        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "1")

        val hasPermission = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            assertThrows(SecurityException::class.java) {
                provider.uriForRead(uri, MediaCategory.PHOTOS, includeOriginalPhotoMetadata = true)
            }
        } else {
            val originalUri = provider.uriForRead(
                uri,
                MediaCategory.PHOTOS,
                includeOriginalPhotoMetadata = true,
            )

            assertNotEquals(uri, originalUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                assertTrue(MediaStore.getRequireOriginal(originalUri))
            }
        }
    }

    @Test
    fun defaultPhotoAndVideoReadsNeverRequestOriginalBytes() {
        val provider = MediaProvider(context)
        val photo = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "1")
        val video = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "1")

        assertEquals(
            photo,
            provider.uriForRead(photo, MediaCategory.PHOTOS, includeOriginalPhotoMetadata = false),
        )
        assertEquals(
            video,
            provider.uriForRead(video, MediaCategory.VIDEOS, includeOriginalPhotoMetadata = true),
        )
    }

    @Test
    fun requireOriginalStreamPreservesSyntheticGpsExifPayload() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = context.contentResolver
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var insertedUri: Uri? = null
        var shellPermissionIdentityAdopted = false

        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            )
            shellPermissionIdentityAdopted = true
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION),
            )

            val sourceBytes = instrumentation.context.assets
                .open("synthetic-gps-exif.jpg")
                .use { it.readBytes() }
            assertEquals(
                "0bc7779ec792e4d290a1d85a908fb55905508033aeda1bd7c50b727c6ef3901e",
                sha256(sourceBytes),
            )
            val displayName = "transferkit-synthetic-gps-${UUID.randomUUID()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TransferKitTest/")
            }
            insertedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw AssertionError("MediaStore did not create the synthetic test image")
            val syntheticUri = insertedUri ?: throw AssertionError("Synthetic test URI was not retained")
            val output = resolver.openOutputStream(syntheticUri, "w")
                ?: throw AssertionError("MediaStore did not open the synthetic image for writing")
            output.use { it.write(sourceBytes) }

            val provider = MediaProvider(context)
            val requiredOriginalUri = provider.uriForRead(
                syntheticUri,
                MediaCategory.PHOTOS,
                includeOriginalPhotoMetadata = true,
            )
            val item = MediaItem(
                categoryId = MediaCategory.PHOTOS.contractId,
                id = "synthetic_gps_exif",
                uri = requiredOriginalUri,
                displayName = displayName,
                sizeBytes = sourceBytes.size.toLong(),
                mimeType = "image/jpeg",
                modifiedAtSeconds = null,
                relativePath = "Pictures/TransferKitTest/",
            )
            val restoredSourceBytes = provider.openStream(item).use { it.readBytes() }

            assertArrayEquals("Original EXIF stream must preserve every synthetic source byte", sourceBytes, restoredSourceBytes)
            assertEquals(sha256(sourceBytes), sha256(restoredSourceBytes))
        } finally {
            insertedUri?.let { resolver.delete(it, null, null) }
            if (shellPermissionIdentityAdopted) {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
