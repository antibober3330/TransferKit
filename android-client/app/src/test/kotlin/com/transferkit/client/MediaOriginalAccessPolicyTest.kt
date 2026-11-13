package com.transferkit.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOriginalAccessPolicyTest {
    @Test
    fun api34OriginalPhotoRequestBundlesPhotoSelectionAndLocationPermissions() {
        val permissions = MediaOriginalAccessPolicy.permissionsToRequest(
            MediaCategory.PHOTOS,
            34,
            includeOriginalPhotoMetadata = true,
        ).toSet()

        assertTrue(permissions.contains(android.Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(permissions.contains(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        assertTrue(permissions.contains(android.Manifest.permission.ACCESS_MEDIA_LOCATION))
    }

    @Test
    fun mediaLocationPermissionIsOnlyBundledForExplicitOriginalPhotoRequests() {
        val ordinaryPhotoPermissions = MediaOriginalAccessPolicy.permissionsToRequest(
            MediaCategory.PHOTOS,
            34,
            includeOriginalPhotoMetadata = false,
        ).toSet()
        val videoPermissions = MediaOriginalAccessPolicy.permissionsToRequest(
            MediaCategory.VIDEOS,
            34,
            includeOriginalPhotoMetadata = true,
        ).toSet()
        val audioPermissions = MediaOriginalAccessPolicy.permissionsToRequest(
            MediaCategory.AUDIO,
            34,
            includeOriginalPhotoMetadata = true,
        ).toSet()

        assertFalse(ordinaryPhotoPermissions.contains(android.Manifest.permission.ACCESS_MEDIA_LOCATION))
        assertFalse(videoPermissions.contains(android.Manifest.permission.ACCESS_MEDIA_LOCATION))
        assertFalse(audioPermissions.contains(android.Manifest.permission.ACCESS_MEDIA_LOCATION))
        assertFalse(audioPermissions.contains(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    }

    @Test
    fun legacyAndApi33OriginalPhotoRequestsUseTheirPlatformPermissions() {
        val legacy = MediaOriginalAccessPolicy.permissionsToRequest(
            MediaCategory.PHOTOS,
            28,
            includeOriginalPhotoMetadata = true,
        ).toSet()
        val api33 = MediaOriginalAccessPolicy.permissionsToRequest(
            MediaCategory.PHOTOS,
            33,
            includeOriginalPhotoMetadata = true,
        ).toSet()

        assertTrue(legacy.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        assertFalse(legacy.contains(android.Manifest.permission.ACCESS_MEDIA_LOCATION))
        assertTrue(api33.contains(android.Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(api33.contains(android.Manifest.permission.ACCESS_MEDIA_LOCATION))
        assertFalse(api33.contains(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    }

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

    @Test
    fun api34SelectedPhotoGrantAuthorizesOnlySelectedVisualCategories() {
        assertTrue(
            MediaOriginalAccessPolicy.hasReadAccess(
                MediaCategory.PHOTOS,
                34,
                hasCategoryPermission = false,
                hasUserSelectedVisualPermission = true,
            ),
        )
        assertTrue(
            MediaOriginalAccessPolicy.hasReadAccess(
                MediaCategory.VIDEOS,
                34,
                hasCategoryPermission = false,
                hasUserSelectedVisualPermission = true,
            ),
        )
        assertFalse(
            MediaOriginalAccessPolicy.hasReadAccess(
                MediaCategory.AUDIO,
                34,
                hasCategoryPermission = false,
                hasUserSelectedVisualPermission = true,
            ),
        )
    }

    @Test
    fun selectedVisualGrantDoesNotImplyAccessBeforeApi34OrWhenDenied() {
        assertFalse(
            MediaOriginalAccessPolicy.hasReadAccess(
                MediaCategory.PHOTOS,
                33,
                hasCategoryPermission = false,
                hasUserSelectedVisualPermission = true,
            ),
        )
        assertFalse(
            MediaOriginalAccessPolicy.hasReadAccess(
                MediaCategory.PHOTOS,
                34,
                hasCategoryPermission = false,
                hasUserSelectedVisualPermission = false,
            ),
        )
        assertTrue(
            MediaOriginalAccessPolicy.hasReadAccess(
                MediaCategory.AUDIO,
                34,
                hasCategoryPermission = true,
                hasUserSelectedVisualPermission = false,
            ),
        )
    }
}
