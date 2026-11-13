package com.transferkit.client

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

enum class MediaCategory(val contractId: String) {
    PHOTOS("photos"),
    VIDEOS("videos"),
    AUDIO("audio"),
}

/** One URI-backed item. The stream is opened on demand and is never buffered as a whole. */
data class MediaItem(
    val categoryId: String,
    val id: String,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val mimeType: String?,
    val modifiedAtSeconds: Long?,
    val relativePath: String?,
    /** The MediaStore stream may be redacted, so the indexed file size is only an estimate. */
    val providerSizeMayDiffer: Boolean = false,
) {
    fun manifestMetadata(): Map<String, Any> = buildMap {
        put("path", (relativePath ?: "") + displayName)
        modifiedAtSeconds?.let { put("mtime", it) }
        mimeType?.let { put("mime_type", it) }
    }
}

class MediaProvider(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun permissionsToRequest(category: MediaCategory): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val categoryPermission = when (category) {
            MediaCategory.PHOTOS -> android.Manifest.permission.READ_MEDIA_IMAGES
            MediaCategory.VIDEOS -> android.Manifest.permission.READ_MEDIA_VIDEO
            MediaCategory.AUDIO -> android.Manifest.permission.READ_MEDIA_AUDIO
        }
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            category != MediaCategory.AUDIO
        ) {
            arrayOf(categoryPermission, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            arrayOf(categoryPermission)
        }
    }

    fun hasReadAccess(category: MediaCategory): Boolean {
        val categoryPermission = permissionsToRequest(category).first()
        if (appContext.checkSelfPermission(categoryPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return true
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            category != MediaCategory.AUDIO &&
            appContext.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun hasMediaLocationAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        appContext.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Enumerates one row at a time; callers can hash/read each item before advancing the cursor. */
    fun forEachMedia(
        category: MediaCategory,
        includeOriginalPhotoMetadata: Boolean = false,
        onItem: (MediaItem) -> Unit,
    ): Int {
        if (!hasReadAccess(category)) {
            throw SecurityException("Read permission is required for ${category.contractId}")
        }
        val collection = when (category) {
            MediaCategory.PHOTOS -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaCategory.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaCategory.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.MediaColumns.RELATIVE_PATH
        }

        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC"
        val cursor = resolver.query(collection, projection.toTypedArray(), null, null, sortOrder)
            ?: throw IOException("MediaStore returned no cursor for ${category.contractId}")

        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val mimeIndex = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val modifiedIndex = it.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val pathIndex = it.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            var count = 0

            while (it.moveToNext()) {
                val rowId = it.getLong(idIndex)
                val mediaUri = ContentUris.withAppendedId(collection, rowId)
                val itemUri = uriForRead(mediaUri, category, includeOriginalPhotoMetadata)
                val displayName = it.getString(nameIndex) ?: "item_$rowId"
                val item = MediaItem(
                    categoryId = category.contractId,
                    id = "media_${category.contractId}_$rowId",
                    uri = itemUri,
                    displayName = displayName,
                    sizeBytes = it.longOrNull(sizeIndex),
                    mimeType = it.stringOrNull(mimeIndex),
                    modifiedAtSeconds = it.longOrNull(modifiedIndex),
                    relativePath = it.stringOrNull(pathIndex),
                    providerSizeMayDiffer = MediaOriginalAccessPolicy.providerSizeMayDiffer(
                        category,
                        Build.VERSION.SDK_INT,
                        includeOriginalPhotoMetadata,
                    ),
                )
                onItem(item)
                count += 1
            }
            return count
        }
    }

    /** Describes a file explicitly selected through ACTION_OPEN_DOCUMENT (SAF). */
    fun describeSelectedDocument(uri: Uri): MediaItem {
        val openColumns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = resolver.query(uri, openColumns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                cursor.stringOrNull(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)) to
                    cursor.longOrNull(cursor.getColumnIndex(OpenableColumns.SIZE))
            }
        }
        val name = row?.first ?: uri.lastPathSegment ?: "selected_document"
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            null
        }
        val categoryId = if (
            uri.authority == "com.android.providers.downloads.documents" ||
            documentId?.substringBefore('/')?.equals("downloads", ignoreCase = true) == true
        ) {
            "downloads"
        } else {
            "documents"
        }
        val modifiedSeconds = queryLastModifiedSeconds(uri)

        return MediaItem(
            categoryId = categoryId,
            id = "document_${sha256(uri.toString())}",
            uri = uri,
            displayName = name,
            sizeBytes = row?.second,
            mimeType = resolver.getType(uri),
            modifiedAtSeconds = modifiedSeconds,
            relativePath = null,
        )
    }

    fun persistReadGrant(uri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    fun openStream(item: MediaItem): InputStream = try {
        resolver.openInputStream(item.uri) ?: throw IOException("Could not open selected content URI")
    } catch (failure: UnsupportedOperationException) {
        throw IOException("Media provider cannot provide the requested source bytes", failure)
    }

    internal fun uriForRead(
        uri: Uri,
        category: MediaCategory,
        includeOriginalPhotoMetadata: Boolean,
    ): Uri {
        val requireOriginal = MediaOriginalAccessPolicy.requiresMediaLocationPermission(
            category,
            Build.VERSION.SDK_INT,
            includeOriginalPhotoMetadata,
        )
        if (!requireOriginal) return uri
        if (!hasMediaLocationAccess()) {
            throw SecurityException("Original photo metadata permission is required")
        }
        return try {
            MediaStore.setRequireOriginal(uri)
        } catch (failure: UnsupportedOperationException) {
            throw IOException("MediaStore cannot provide original photo bytes", failure)
        }
    }

    private fun queryLastModifiedSeconds(uri: Uri): Long? = try {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else
                cursor.longOrNull(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED))?.div(1000)
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun Cursor.stringOrNull(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)

private fun Cursor.longOrNull(index: Int): Long? =
    if (index < 0 || isNull(index)) null else getLong(index).takeIf { it >= 0L }
