package com.transferkit.client.restore

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

data class SafDocumentTreeSelection(
    val uri: Uri,
    val displayName: String,
    internal val releaseGrantOnFinish: Boolean,
)

/** Owns only the persistable grant acquired for the active user-confirmed document restore. */
object SafDocumentTreeAccess {
    private val requestedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private const val PREFERENCES = "saf_restore_destination"
    private const val KEY_URI = "tree_uri"
    private const val KEY_RELEASE_ON_FINISH = "release_on_finish"

    fun acquire(context: Context, treeUri: Uri): SafDocumentTreeSelection {
        val resolver = context.applicationContext.contentResolver
        require(DocumentsContract.isTreeUri(treeUri)) { "The system picker did not return a document tree" }
        val existingGrant = resolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        val alreadyPersisted = existingGrant?.isReadPermission == true && existingGrant.isWritePermission
        if (!alreadyPersisted) resolver.takePersistableUriPermission(treeUri, requestedFlags)

        try {
            val destinationName = validateDestinationTree(context, treeUri)
            val selection = SafDocumentTreeSelection(treeUri, destinationName, releaseGrantOnFinish = !alreadyPersisted)
            save(context, selection)
            return selection
        } catch (failure: Exception) {
            if (!alreadyPersisted) runCatching { resolver.releasePersistableUriPermission(treeUri, requestedFlags) }
            throw failure
        }
    }

    fun restoreSaved(context: Context): SafDocumentTreeSelection? {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val rawUri = preferences.getString(KEY_URI, null) ?: return null
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: run {
            preferences.edit().clear().apply()
            return null
        }
        val releaseOnFinish = preferences.getBoolean(KEY_RELEASE_ON_FINISH, false)
        val resolver = context.applicationContext.contentResolver
        val hasReadWriteGrant = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!hasReadWriteGrant) {
            preferences.edit().clear().apply()
            return null
        }
        return runCatching {
            val displayName = validateDestinationTree(context, uri)
            SafDocumentTreeSelection(uri, displayName, releaseOnFinish)
        }.getOrElse {
            releaseGrant(context, uri, releaseOnFinish)
            preferences.edit().clear().apply()
            null
        }
    }

    fun releaseIfOwned(context: Context, selection: SafDocumentTreeSelection?) {
        if (selection == null) return
        if (selection.releaseGrantOnFinish) {
            releaseGrant(context, selection.uri, owned = true)
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    private fun save(context: Context, selection: SafDocumentTreeSelection) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, selection.uri.toString())
            .putBoolean(KEY_RELEASE_ON_FINISH, selection.releaseGrantOnFinish)
            .apply()
    }

    private fun validateDestinationTree(context: Context, treeUri: Uri): String {
        val resolver = context.applicationContext.contentResolver
        require(DocumentsContract.isTreeUri(treeUri)) { "The system picker did not return a document tree" }
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        return resolver.query(root, projection, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "The selected folder is not available" }
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
            require(mime == DocumentsContract.Document.MIME_TYPE_DIR) { "The selected destination is not a folder" }
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            require(flagsIndex >= 0) { "The selected provider does not report folder capabilities" }
            val flags = cursor.getInt(flagsIndex)
            require(flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
                "The selected folder does not allow creating documents"
            }
            require(flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) {
                "The selected provider does not support publishing verified documents by rename"
            }
            cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                .orEmpty()
                .ifBlank { "вибрана папка" }
        } ?: throw IOException("The selected provider returned no folder information")
    }

    private fun releaseGrant(context: Context, uri: Uri, owned: Boolean) {
        if (!owned) return
        runCatching {
            context.applicationContext.contentResolver.releasePersistableUriPermission(uri, requestedFlags)
        }
    }
}

/** Writes user-selected document items only into a tree explicitly chosen with the SAF picker. */
class SafDocumentRestorer(
    context: Context,
    private val approvalGate: RestoreApprovalGate,
) : CategoryRestorer {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override val categoryIds: Set<String> = setOf("documents")

    override fun restore(item: VerifiedRestoreItem, approval: RestoreApproval?): RestoreItemResult =
        restore(item, approval, RestoreContext())

    override fun restore(
        item: VerifiedRestoreItem,
        approval: RestoreApproval?,
        context: RestoreContext,
    ): RestoreItemResult {
        if (item.categoryId !in categoryIds) return failure(item, "UNSUPPORTED")
        val treeUri = context.documentTreeUri
            ?: return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "SAF_DESTINATION_REQUIRED")
        if (!approvalGate.consume(approval, item)) {
            return RestoreItemResult(item.itemId, RestoreItemStatus.USER_ACTION_REQUIRED, "RESTORE_CONFIRMATION_REQUIRED")
        }
        if (item.sizeBytes < 0L || !SHA256_PATTERN.matches(item.sha256)) return failure(item, "INVALID_MANIFEST")
        if (!item.payloadFile.isFile || item.payloadFile.length() != item.sizeBytes) return integrityFailure(item)

        val payloadDigest = runCatching { item.payloadFile.inputStream().use(::digest) }.getOrNull()
            ?: return failure(item, "SOURCE_READ_FAILED")
        if (payloadDigest.first != item.sizeBytes || payloadDigest.second != item.sha256) return integrityFailure(item)

        val file = SafDocumentDestination.from(item) ?: return failure(item, "INVALID_MANIFEST")
        return try {
            val destination = validateTree(treeUri)
            val existing = findExisting(destination, file.displayName)
            if (existing != null) {
                if (existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return failure(item, "TARGET_WRITE_FAILED")
                }
                return if (verify(existing.uri, item)) {
                    RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
                } else {
                    integrityFailure(item)
                }
            }

            val pendingName = SafDocumentDestination.pendingName(file.displayName)
            val pendingUri = DocumentsContract.createDocument(
                resolver,
                destination.rootUri,
                file.mimeType,
                pendingName,
            ) ?: throw IOException("Selected SAF provider did not create the destination file")
            var publishedUri: Uri? = null
            var restored = false
            try {
                val pendingFlags = queryFlags(pendingUri)
                if (pendingFlags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME == 0) {
                    throw IOException("Selected SAF provider cannot atomically publish the pending file")
                }
                val copiedDigest = writeAndSync(pendingUri, item.payloadFile)
                if (copiedDigest.first != item.sizeBytes || copiedDigest.second != item.sha256) {
                    return integrityFailure(item)
                }

                // Recheck immediately before publication. The final name contains a transfer/item
                // marker, and an existing marker is never overwritten or silently renamed.
                val appeared = findExisting(destination, file.displayName)
                if (appeared != null) {
                    return if (appeared.mimeType != DocumentsContract.Document.MIME_TYPE_DIR &&
                        verify(appeared.uri, item)
                    ) {
                        RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
                    } else {
                        integrityFailure(item)
                    }
                }
                publishedUri = DocumentsContract.renameDocument(resolver, pendingUri, file.displayName)
                    ?: throw IOException("Selected SAF provider could not publish the verified document")
                if (!verify(publishedUri, item)) return integrityFailure(item)
                restored = true
                RestoreItemResult(item.itemId, RestoreItemStatus.RESTORED)
            } finally {
                if (!restored) {
                    publishedUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                    runCatching { DocumentsContract.deleteDocument(resolver, pendingUri) }
                }
            }
        } catch (_: SecurityException) {
            failure(item, "PERMISSION_DENIED")
        } catch (_: IOException) {
            failure(item, "TARGET_WRITE_FAILED")
        } catch (_: IllegalArgumentException) {
            // Source fields were validated before provider access. An argument error after that
            // point is a destination/provider rejection, not evidence of a bad source manifest.
            failure(item, "TARGET_WRITE_FAILED")
        } catch (_: Exception) {
            failure(item, "RESTORE_FAILED")
        }
    }

    private fun validateTree(treeUri: Uri): TreeDestination {
        if (!DocumentsContract.isTreeUri(treeUri)) throw IllegalArgumentException("A SAF tree URI is required")
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        val flags = resolver.query(rootUri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("Selected SAF folder is no longer available")
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (cursor.getString(mimeIndex) != DocumentsContract.Document.MIME_TYPE_DIR) {
                throw IllegalArgumentException("Selected SAF destination is not a folder")
            }
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            if (flagsIndex < 0) 0 else cursor.getInt(flagsIndex)
        } ?: throw IOException("Selected SAF provider returned no folder metadata")
        if (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
            throw IOException("Selected SAF folder does not support creating files")
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        return TreeDestination(treeUri, rootUri, childrenUri)
    }

    private fun findExisting(destination: TreeDestination, displayName: String): ExistingDocument? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cursor = resolver.query(destination.childrenUri, projection, null, null, null)
            ?: throw IOException("Selected SAF provider returned no child listing")
        return cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            var found: ExistingDocument? = null
            while (it.moveToNext()) {
                if (it.getString(nameIndex) != displayName) continue
                if (found != null) throw IOException("SAF destination marker is ambiguous")
                val id = it.getString(idIndex) ?: throw IOException("SAF provider returned an empty document ID")
                found = ExistingDocument(
                    uri = DocumentsContract.buildDocumentUriUsingTree(destination.treeUri, id),
                    mimeType = it.getString(mimeIndex),
                )
            }
            found
        }
    }

    private fun verify(uri: Uri, item: VerifiedRestoreItem): Boolean {
        val input = resolver.openInputStream(uri) ?: return false
        val (size, sha256) = input.use(::digest)
        return size == item.sizeBytes && sha256 == item.sha256
    }

    private fun queryFlags(uri: Uri): Int {
        val flags = resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("SAF provider lost the pending document")
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            if (index < 0) 0 else cursor.getInt(index)
        } ?: throw IOException("SAF provider returned no pending-document metadata")
        return flags
    }

    private fun writeAndSync(uri: Uri, source: java.io.File): Pair<Long, String> {
        val descriptor = resolver.openFileDescriptor(uri, "w")
            ?: throw IOException("Selected SAF provider did not open the pending document")
        val output = FileOutputStream(descriptor.fileDescriptor)
        return try {
            val copied = source.inputStream().use { input -> copyAndDigest(input, output) }
            output.fd.sync()
            copied
        } finally {
            runCatching { output.close() }
            runCatching { descriptor.close() }
        }
    }

    private fun failure(item: VerifiedRestoreItem, reason: String) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED, reason)

    private fun integrityFailure(item: VerifiedRestoreItem) =
        RestoreItemResult(item.itemId, RestoreItemStatus.FAILED_INTEGRITY, "SHA256_MISMATCH")

    private data class TreeDestination(val treeUri: Uri, val rootUri: Uri, val childrenUri: Uri)
    private data class ExistingDocument(val uri: Uri, val mimeType: String)

    private companion object {
        val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

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

/** Deterministic, traversal-safe name for an item that belongs to one TransferKit transfer. */
internal data class SafDocumentDestination(val displayName: String, val mimeType: String) {
    companion object {
        private const val MAX_FILENAME_CHARS = 120
        private val UNSAFE_FILENAME = Regex("[^\\p{L}\\p{N}._ -]")
        private val MIME_PATTERN = Regex("^[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64}$")

        fun from(item: VerifiedRestoreItem): SafDocumentDestination? {
            if (item.categoryId != "documents") return null
            val original = (item.metadata["path"] as? String)
                ?.replace('\\', '/')
                ?.substringAfterLast('/')
                .orEmpty()
                .ifBlank { "transferred-document" }
            val safeName = original.replace(UNSAFE_FILENAME, "_")
                .trim('.', ' ')
                .ifBlank { "transferred-document" }
            val marker = MessageDigest.getInstance("SHA-256")
                .digest("${item.transferId}\u0000${item.categoryId}\u0000${item.itemId}".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                .take(24)
            val prefix = "tk_${marker}_"
            val boundedName = safeName.take((MAX_FILENAME_CHARS - prefix.length).coerceAtLeast(1))
            val mime = (item.metadata["mime_type"] as? String)
                ?.takeIf { it.length <= 128 && MIME_PATTERN.matches(it) }
                ?: "application/octet-stream"
            return SafDocumentDestination("$prefix$boundedName", mime)
        }

        fun pendingName(finalName: String): String = "tk_pending_${UUID.randomUUID()}_${finalName.take(72)}.part"
    }
}
