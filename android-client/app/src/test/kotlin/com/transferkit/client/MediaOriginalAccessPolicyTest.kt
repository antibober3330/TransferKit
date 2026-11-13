package com.transferkit.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOriginalAccessPolicyTest {
    @Test
    fun api28DoesNotRequireMediaLocationRuntimePermission() {
        assertFalse(
            MediaOriginalAccessPolicy.requiresMediaLocationPermission(
                MediaCategory.PHOTOS,
                28,
                includeOriginalPhotoMetadata = true,
            ),
        )
    }

    @Test
    fun api29RequiresPermissionOnlyForExplicitOriginalPhotoOptIn() {
        assertTrue(
            MediaOriginalAccessPolicy.requiresMediaLocationPermission(
                MediaCategory.PHOTOS,
                29,
                includeOriginalPhotoMetadata = true,
            ),
        )
        assertFalse(
            MediaOriginalAccessPolicy.requiresMediaLocationPermission(
                MediaCategory.PHOTOS,
                29,
                includeOriginalPhotoMetadata = false,
            ),
        )
    }

    @Test
    fun locationPermissionIsNotRequestedForVideoOrAudio() {
        assertFalse(
            MediaOriginalAccessPolicy.requiresMediaLocationPermission(
                MediaCategory.VIDEOS,
                34,
                includeOriginalPhotoMetadata = true,
            ),
        )
        assertFalse(
            MediaOriginalAccessPolicy.requiresMediaLocationPermission(
                MediaCategory.AUDIO,
                34,
                includeOriginalPhotoMetadata = true,
            ),
        )
    }

    @Test
    fun api29PhotosMayHaveRedactedSizeUnlessOptedIntoOriginalBytes() {
        assertTrue(MediaOriginalAccessPolicy.providerSizeMayDiffer(MediaCategory.PHOTOS, 29, false))
        assertFalse(MediaOriginalAccessPolicy.providerSizeMayDiffer(MediaCategory.PHOTOS, 29, true))
        assertFalse(MediaOriginalAccessPolicy.providerSizeMayDiffer(MediaCategory.VIDEOS, 34, false))
        assertFalse(MediaOriginalAccessPolicy.providerSizeMayDiffer(MediaCategory.PHOTOS, 28, false))
    }
}
