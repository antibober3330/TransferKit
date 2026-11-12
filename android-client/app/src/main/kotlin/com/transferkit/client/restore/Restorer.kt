package com.transferkit.client.restore

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A receiver-verified item presented to a category adapter. */
data class VerifiedRestoreItem(
    val transferId: String,
    val manifestSha256: String,
    val categoryId: String,
    val itemId: String,
    val sizeBytes: Long,
    val sha256: String,
    val metadata: Map<String, Any?>,
    val payloadFile: File,
)

enum class RestoreItemStatus {
    RESTORED,
    FAILED,
    FAILED_INTEGRITY,
    UNAVAILABLE,
    USER_ACTION_REQUIRED,
}

data class RestoreItemResult(
    val itemId: String,
    val status: RestoreItemStatus,
    val errorReason: String? = null,
)

/**
 * Synchronous by design: the caller must run adapters on a worker thread. Implementations must
 * return one item result and must never report RESTORED before destination post-verification.
 */
interface CategoryRestorer {
    val categoryIds: Set<String>
    fun restore(item: VerifiedRestoreItem, approval: RestoreApproval? = null): RestoreItemResult
}

/** Restores file categories through app-owned MediaStore rows on Android 10 and newer. */
class MediaStoreRestorer(
    context: Context,
    private val approvalGate: RestoreApprovalGate,
) : CategoryRestorer {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override val categoryIds: Set<String> = setOf(
        "photos", "videos", "audio", "downloads",
    )

    override fun restore(item: VerifiedRestoreItem, approval: RestoreApproval?): RestoreItemResult {
        if (item.categoryId !in categoryIds) return failure(item, "UNSUPPORTED")
        if (!approvalGate.consume(approval, item)) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "RESTORE_CONFIRMATION_REQUIRED")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // API 26–28 needs a separate legacy public-directory implementation and permission flow.
            return failure(item, "API_UNAVAILABLE")
        }
        if (item.sizeBytes < 0L || !SHA256_PATTERN.matches(item.sha256)) {
            return failure(item, "INVALID_MANIFEST")
        }
        if (!item.payloadFile.isFile || item.payloadFile.length() != item.sizeBytes) return integrityFailure(item)

        val destination = destination(item) ?: return failure(item, "INVALID_MANIFEST")
        return try {
            val existing = findExisting(destination, item)
            if (existing != null) {
                val (uri, isPending) = existing
                if (!verify(uri, item)) return integrityFailure(item)
                if (isPending) {
                    if (!publish(uri)) return failure(item, "TARGET_WRITE_FAILED")
                    if (!verify(uri, item)) return integrityFailure(item)
                }
                RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
            } else {
                insertAndVerify(destination, item)
            }
        } catch (_: SecurityException) {
            failure(item, "PERMISSION_DENIED")
        } catch (_: IOException) {
            failure(item, "TARGET_WRITE_FAILED")
        } catch (_: IllegalArgumentException) {
            failure(item, "INVALID_MANIFEST")
        } catch (_: Exception) {
            failure(item, "RESTORE_FAILED")
        }
    }

    private fun insertAndVerify(destination: Destination, item: VerifiedRestoreItem): RestoreItemResult {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, destination.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, destination.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, destination.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(destination.collection, values)
            ?: throw IOException("MediaStore did not create a destination row")
        var published = false
        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("MediaStore destination could not be opened")
            val copiedDigest = item.payloadFile.inputStream().use { input ->
                output.use { target -> copyAndDigest(input, target) }
            }
            if (copiedDigest.first != item.sizeBytes || copiedDigest.second != item.sha256) {
                resolver.delete(uri, null, null)
                return integrityFailure(item)
            }
            if (!verify(uri, item)) {
                resolver.delete(uri, null, null)
                return integrityFailure(item)
            }
            if (!publish(uri)) throw IOException("MediaStore could not publish the verified item")
            // The result is not final until the published destination row can be
            // read back and its exact bytes still match the manifest.
            if (!verify(uri, item)) return integrityFailure(item)
            published = true
            return RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
        } finally {
            // A row created by this invocation is still private unless publish() succeeded.
            if (!published) runCatching { resolver.delete(uri, null, null) }
        }
    }

    private fun findExisting(destination: Destination, item: VerifiedRestoreItem): ExistingRow? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING)
        val cursor = resolver.query(
            destination.collection,
            projection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(destination.displayName, destination.relativePath),
            null,
        ) ?: throw IOException("MediaStore query returned no cursor")
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val pendingIndex = it.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            val pending = pendingIndex >= 0 && it.getInt(pendingIndex) == 1
            if (it.moveToNext()) throw IOException("TransferKit destination marker is ambiguous")
            ExistingRow(Uri.withAppendedPath(destination.collection, id.toString()), pending)
        }
    }

    private fun verify(uri: Uri, item: VerifiedRestoreItem): Boolean {
        val input = resolver.openInputStream(uri) ?: return false
        val (size, digest) = input.use(::digest)
        return size == item.sizeBytes && digest == item.sha256
    }

    private fun publish(uri: Uri): Boolean = resolver.update(
        uri,
        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
        null,
        null,
    ) == 1

    private fun destination(item: VerifiedRestoreItem): Destination? {
        val original = (item.metadata["path"] as? String)
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            .orEmpty()
            .ifBlank { "transferred-item" }
        val safeName = original
            .replace(UNSAFE_FILENAME, "_")
            .trim('.', ' ')
            .takeLast(MAX_FILENAME_CHARS)
            .ifBlank { "transferred-item" }
        val marker = sha256("${item.transferId}\u0000${item.categoryId}\u0000${item.itemId}".toByteArray()).take(24)
        val displayName = "tk_${marker}_$safeName"
        val mime = (item.metadata["mime_type"] as? String)
            ?.takeIf { it.length <= 128 && '\n' !in it && '\r' !in it }
            ?: "application/octet-stream"
        val (collection, path, requiredMimePrefix) = when (item.categoryId) {
            "photos" -> Triple(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "Pictures/TransferKit/",
                "image/",
            )
            "videos" -> Triple(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "Movies/TransferKit/",
                "video/",
            )
            "audio" -> Triple(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "Music/TransferKit/",
                "audio/",
            )
            "downloads" -> Triple(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "Download/TransferKit/",
                null,
            )
            else -> return null
        }
        if (requiredMimePrefix != null && !mime.startsWith(requiredMimePrefix)) return null
        return Destination(collection, path, displayName, mime)
    }

    private fun failure(item: VerifiedRestoreItem, reason: String) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED, reason)

    private fun integrityFailure(item: VerifiedRestoreItem) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")

    private data class Destination(
        val collection: Uri,
        val relativePath: String,
        val displayName: String,
        val mimeType: String,
    )

    private data class ExistingRow(val uri: Uri, val isPending: Boolean)

    private companion object {
        const val MAX_FILENAME_CHARS = 120
        val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        val UNSAFE_FILENAME = Regex("[^\\p{L}\\p{N}._ -]")

        fun copyAndDigest(input: InputStream, output: OutputStream): Pair<Long, String> {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var size = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val value = input.read()
                    if (value < 0) break
                    output.write(value)
                    digest.update(value.toByte())
                    size = Math.addExact(size, 1L)
                } else {
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    size = Math.addExact(size, count.toLong())
                }
            }
            output.flush()
            return size to digest.digest().toHex()
        }

        fun digest(input: InputStream): Pair<Long, String> {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var size = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val value = input.read()
                    if (value < 0) break
                    digest.update(value.toByte())
                    size = Math.addExact(size, 1L)
                } else {
                    digest.update(buffer, 0, count)
                    size = Math.addExact(size, count.toLong())
                }
            }
            return size to digest.digest().toHex()
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toHex()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Explicitly reports unsupported categories rather than silently treating receipt as restore. */
class RestorerRegistry(restorers: List<CategoryRestorer>) {
    constructor(mediaStoreRestorer: MediaStoreRestorer) : this(listOf(mediaStoreRestorer))

    private val adaptersByCategory: Map<String, CategoryRestorer> = buildMap {
        restorers.forEach { restorer ->
            restorer.categoryIds.forEach { categoryId ->
                require(!containsKey(categoryId)) { "More than one restorer claims category $categoryId" }
                put(categoryId, restorer)
            }
        }
    }

    fun restore(item: VerifiedRestoreItem, approval: RestoreApproval? = null): RestoreItemResult =
        adaptersByCategory[item.categoryId]?.restore(item, approval)
            ?: RestoreItemResult(item.itemId, RestoreItemStatus.UNAVAILABLE, "UNSUPPORTED")
}
