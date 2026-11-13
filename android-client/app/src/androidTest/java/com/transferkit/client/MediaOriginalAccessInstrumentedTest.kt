package com.transferkit.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

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
}
