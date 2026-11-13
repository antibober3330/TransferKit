package com.transferkit.client

/** Pure permission/metadata policy, separated so SDK boundaries stay testable. */
object MediaOriginalAccessPolicy {
    const val FIRST_API_WITH_MEDIA_LOCATION_PERMISSION = 29
    const val FIRST_API_WITH_USER_SELECTED_MEDIA_PERMISSION = 34

    /**
     * Android recommends requesting selected-media, category, and original-location access
     * together so the permission controller can present one coherent consent flow.
     */
    fun permissionsToRequest(
        category: MediaCategory,
        sdkInt: Int,
        includeOriginalPhotoMetadata: Boolean,
    ): Array<String> {
        if (sdkInt < 33) return arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)

        val categoryPermission = when (category) {
            MediaCategory.PHOTOS -> android.Manifest.permission.READ_MEDIA_IMAGES
            MediaCategory.VIDEOS -> android.Manifest.permission.READ_MEDIA_VIDEO
            MediaCategory.AUDIO -> android.Manifest.permission.READ_MEDIA_AUDIO
        }
        val permissions = mutableListOf(categoryPermission)
        if (sdkInt >= FIRST_API_WITH_USER_SELECTED_MEDIA_PERMISSION && category != MediaCategory.AUDIO) {
            permissions += android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
        if (requiresMediaLocationPermission(category, sdkInt, includeOriginalPhotoMetadata)) {
            permissions += android.Manifest.permission.ACCESS_MEDIA_LOCATION
        }
        return permissions.toTypedArray()
    }

    /**
     * Android 14+ can authorize only selected images/videos. The visual-selected grant must never
     * imply access to audio or to a category the user did not select.
     */
    fun hasReadAccess(
        category: MediaCategory,
        sdkInt: Int,
        hasCategoryPermission: Boolean,
        hasUserSelectedVisualPermission: Boolean,
    ): Boolean = hasCategoryPermission || (
        sdkInt >= FIRST_API_WITH_USER_SELECTED_MEDIA_PERMISSION &&
            category != MediaCategory.AUDIO &&
            hasUserSelectedVisualPermission
        )

    fun requiresMediaLocationPermission(
        category: MediaCategory,
        sdkInt: Int,
        includeOriginalPhotoMetadata: Boolean,
    ): Boolean = sdkInt >= FIRST_API_WITH_MEDIA_LOCATION_PERMISSION &&
        category == MediaCategory.PHOTOS && includeOriginalPhotoMetadata

    /** MediaStore can redact photo location metadata unless original bytes were explicitly requested. */
    fun providerSizeMayDiffer(
        category: MediaCategory,
        sdkInt: Int,
        includeOriginalPhotoMetadata: Boolean,
    ): Boolean = sdkInt >= FIRST_API_WITH_MEDIA_LOCATION_PERMISSION &&
        category == MediaCategory.PHOTOS && !includeOriginalPhotoMetadata
}
