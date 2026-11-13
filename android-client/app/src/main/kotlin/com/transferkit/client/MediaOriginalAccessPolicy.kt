package com.transferkit.client

/** Pure permission/metadata policy, separated so SDK boundaries stay testable. */
object MediaOriginalAccessPolicy {
    const val FIRST_API_WITH_MEDIA_LOCATION_PERMISSION = 29

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
