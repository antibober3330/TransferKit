package com.transferkit.client.restore

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /** Optional user-selected target details for categories that need a destination container. */
    fun restore(
        item: VerifiedRestoreItem,
        approval: RestoreApproval?,
        context: RestoreContext,
    ): RestoreItemResult = restore(item, approval)
}

data class RestoreContext(
    val calendarDestinationId: Long? = null,
    val documentTreeUri: Uri? = null,
)

/** Supported by the registered API-specific adapters on the app's minSdk 26 range. */
internal fun isFileMediaRestoreCategory(categoryId: String): Boolean =
    categoryId in setOf("photos", "videos", "audio", "downloads")

internal fun isSafDocumentRestoreCategory(categoryId: String): Boolean = categoryId == "documents"

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

/** Restores media to public folders on Android 8–9, where scoped MediaStore inserts do not exist. */
class LegacyMediaRestorer(
    context: Context,
    private val approvalGate: RestoreApprovalGate,
    private val mediaScanTimeoutMillis: Long = DEFAULT_MEDIA_SCAN_TIMEOUT_MILLIS,
) : CategoryRestorer {
    private val appContext = context.applicationContext

    init {
        require(mediaScanTimeoutMillis > 0L) { "mediaScanTimeoutMillis must be positive" }
    }

    override val categoryIds: Set<String> = setOf("photos", "videos", "audio", "downloads")

    override fun restore(item: VerifiedRestoreItem, approval: RestoreApproval?): RestoreItemResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return failure(item, "API_UNAVAILABLE")
        if (item.categoryId !in categoryIds) return failure(item, "UNSUPPORTED")
        if (appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "WRITE_EXTERNAL_STORAGE_REQUIRED")
        }
        if (!approvalGate.consume(approval, item)) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "RESTORE_CONFIRMATION_REQUIRED")
        }
        if (item.sizeBytes < 0L || !LEGACY_SHA256_PATTERN.matches(item.sha256)) {
            return failure(item, "INVALID_MANIFEST")
        }
        if (!item.payloadFile.isFile || item.payloadFile.length() != item.sizeBytes) return integrityFailure(item)

        val destination = runCatching { LegacyMediaDestination.from(item) }.getOrNull()
            ?: return failure(item, "INVALID_MANIFEST")
        return try {
            if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
                return failure(item, "TARGET_WRITE_FAILED")
            }
            val publicRoot = Environment.getExternalStorageDirectory().canonicalFile
            val targetDirectory = File(publicRoot, destination.relativeDirectory).canonicalFile
            if (!targetDirectory.toPath().startsWith(publicRoot.toPath())) return failure(item, "INVALID_MANIFEST")
            if (!targetDirectory.isDirectory && !targetDirectory.mkdirs()) return failure(item, "TARGET_WRITE_FAILED")

            val target = File(targetDirectory, destination.displayName)
            if (target.exists()) {
                if (!verify(target, item)) return integrityFailure(item)
                return if (scan(target, destination.mimeType)) {
                    RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
                } else {
                    failure(item, "MEDIA_SCAN_FAILED")
                }
            }

            val pending = File(targetDirectory, ".pending_${UUID.randomUUID()}.part")
            try {
                if (!pending.createNewFile()) return failure(item, "TARGET_WRITE_FAILED")
                val copied = item.payloadFile.inputStream().use { input ->
                    FileOutputStream(pending).use { output ->
                        copyAndDigest(input, output).also { output.fd.sync() }
                    }
                }
                if (copied.first != item.sizeBytes || copied.second != item.sha256) {
                    pending.delete()
                    return integrityFailure(item)
                }
                if (!verify(pending, item)) {
                    pending.delete()
                    return integrityFailure(item)
                }
                // Files.move without REPLACE_EXISTING fails if a user-created file won the race.
                // Both paths are in the same public directory, so this is a same-volume move.
                Files.move(pending.toPath(), target.toPath())
            } finally {
                // Only the UUID-named temporary file created by this invocation is removed.
                if (pending.exists()) pending.delete()
            }

            if (!verify(target, item)) return integrityFailure(item)
            if (!scan(target, destination.mimeType)) return failure(item, "MEDIA_SCAN_FAILED")
            // Read back once more after the system scanner has indexed the public destination.
            if (!verify(target, item)) return integrityFailure(item)
            RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
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

    private fun scan(file: File, mimeType: String): Boolean {
        val latch = CountDownLatch(1)
        var scannedUri: Uri? = null
        MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), arrayOf(mimeType)) { _, uri ->
            scannedUri = uri
            latch.countDown()
        }
        if (!latch.await(mediaScanTimeoutMillis, TimeUnit.MILLISECONDS)) return false
        return scannedUri != null
    }

    private fun verify(file: File, item: VerifiedRestoreItem): Boolean {
        if (!file.isFile || file.length() != item.sizeBytes) return false
        val (size, digest) = file.inputStream().use(::digest)
        return size == item.sizeBytes && digest == item.sha256
    }

    private fun failure(item: VerifiedRestoreItem, reason: String) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED, reason)

    private fun integrityFailure(item: VerifiedRestoreItem) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")

    private companion object {
        const val DEFAULT_MEDIA_SCAN_TIMEOUT_MILLIS = 30_000L
        val LEGACY_SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

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

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Pure path/name policy so legacy destinations can be checked without writing to public storage. */
internal data class LegacyMediaDestination(
    val relativeDirectory: String,
    val displayName: String,
    val mimeType: String,
) {
    companion object {
        private const val MAX_FILENAME_CHARS = 120
        private val UNSAFE_FILENAME = Regex("[^\\p{L}\\p{N}._ -]")

        fun from(item: VerifiedRestoreItem): LegacyMediaDestination? {
            val original = (item.metadata["path"] as? String)
                ?.replace('\\', '/')
                ?.substringAfterLast('/')
                .orEmpty()
                .ifBlank { "transferred-item" }
            val safeName = original.replace(UNSAFE_FILENAME, "_")
                .trim('.', ' ')
                .takeLast(MAX_FILENAME_CHARS)
                .ifBlank { "transferred-item" }
            val marker = MessageDigest.getInstance("SHA-256")
                .digest("${item.transferId}\u0000${item.categoryId}\u0000${item.itemId}".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                .take(24)
            val mime = (item.metadata["mime_type"] as? String)
                ?.takeIf { it.length <= 128 && '\n' !in it && '\r' !in it }
                ?: "application/octet-stream"
            val (directory, prefix) = when (item.categoryId) {
                "photos" -> "Pictures" to "image/"
                "videos" -> "Movies" to "video/"
                "audio" -> "Music" to "audio/"
                "downloads" -> "Download" to null
                else -> return null
            }
            if (prefix != null && !mime.startsWith(prefix)) return null
            return LegacyMediaDestination(
                relativeDirectory = "$directory/TransferKit",
                displayName = "tk_${marker}_$safeName",
                mimeType = mime,
            )
        }
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

    val supportedCategoryIds: Set<String>
        get() = adaptersByCategory.keys

    fun restore(
        item: VerifiedRestoreItem,
        approval: RestoreApproval? = null,
        context: RestoreContext = RestoreContext(),
    ): RestoreItemResult =
        adaptersByCategory[item.categoryId]?.restore(item, approval, context)
            ?: RestoreItemResult(item.itemId, RestoreItemStatus.UNAVAILABLE, "UNSUPPORTED")
}
