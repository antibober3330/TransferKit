package com.transferkit.client.receiver

import android.content.Context
import android.database.sqlite.SQLiteException
import com.transferkit.client.protocol.AckKind
import com.transferkit.client.protocol.Acknowledgement
import com.transferkit.client.protocol.MAX_MANIFEST_BYTES
import com.transferkit.client.protocol.JSON_CODE_POINT_ORDER
import com.transferkit.client.protocol.validateManifestItemCount
import com.transferkit.client.protocol.MAX_UINT32
import com.transferkit.client.protocol.ProtocolCodec
import com.transferkit.client.protocol.ProtocolException
import com.transferkit.client.restore.VerifiedRestoreItem
import com.transferkit.client.restore.isFileMediaRestoreCategory
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ReceivedTransferSummary(
    val transferId: String,
    val manifestSha256: String,
    val verifiedItemCount: Int,
    val restorableItemCount: Int,
    val restorableBytes: Long,
    val restoredItemCount: Int,
    val unavailableVerifiedItemCount: Int,
    val failedItemCount: Int,
    val restorableApkItemCount: Int = 0,
    val restorableApkBytes: Long = 0L,
    val restorableContactItemCount: Int = 0,
    val restorableContactBytes: Long = 0L,
    val restorableCalendarItemCount: Int = 0,
    val restorableCalendarBytes: Long = 0L,
)

/** Cumulative bytes durable in receiver checkpoints; retries never inflate this value. */
data class DurableTransferProgress(
    val transferId: String,
    val receivedBytes: Long,
    val totalBytes: Long,
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((receivedBytes.toDouble() / totalBytes) * 100.0)
            .toInt()
            .coerceIn(0, 100)
}

/** App-private receiver core. A transport owns the socket and sends ACKs returned here. */
class AndroidTransferReceiver(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val database = ReceiverDatabase(appContext)
    private val cleanupApprovalGate = StagingCleanupApprovalGate()
    private val manifests = HashMap<String, IncomingManifest>()
    private val receiverRoot = File(appContext.filesDir, RECEIVER_DIRECTORY)

    /** Validates and durably records a manifest before returning ACK status 0. */
    @Synchronized
    fun acceptManifest(payload: ByteArray, authenticatedTransferId: UUID? = null): Acknowledgement {
        val incoming = try {
            IncomingManifestValidator.parse(payload)
        } catch (_: Exception) {
            return manifestAck(UUID(0L, 0L), ACK_INVALID_MANIFEST)
        }
        if (authenticatedTransferId != null && incoming.transferId != authenticatedTransferId) {
            return manifestAck(incoming.transferId, ACK_TRANSFER_MISMATCH)
        }
        val transferId = incoming.transferId.toString()
        val digest = sha256Hex(payload)
        try {
            val existing = database.transfer(transferId)
            if (existing != null) {
                if (existing.status == ReceiverDatabase.TRANSFER_ABANDONED) {
                    return manifestAck(incoming.transferId, ACK_INVALID_MANIFEST)
                }
                if (existing.manifestSha256 == digest) {
                    manifests[transferId] = incoming
                    return manifestAck(incoming.transferId, ACK_OK)
                }
                return manifestAck(incoming.transferId, ACK_INVALID_MANIFEST)
            }
            val transferDirectory = File(receiverRoot, transferId)
            if (!File(transferDirectory, STAGING_DIRECTORY).mkdirs() &&
                !File(transferDirectory, STAGING_DIRECTORY).isDirectory
            ) {
                return manifestAck(incoming.transferId, ACK_TARGET_WRITE_FAILED)
            }
            if (!File(transferDirectory, FINAL_DIRECTORY).mkdirs() &&
                !File(transferDirectory, FINAL_DIRECTORY).isDirectory
            ) {
                transferDirectory.deleteRecursively()
                return manifestAck(incoming.transferId, ACK_TARGET_WRITE_FAILED)
            }
            database.insertManifest(
                transferId = transferId,
                manifestSha256 = digest,
                manifestBytes = payload,
                receivedAt = Instant.now().toString(),
                items = incoming.items.map { (key, item) ->
                    ReceiverDatabase.ManifestItemRecord(
                        categoryId = item.categoryId,
                        itemId = item.itemId,
                        sizeBytes = item.sizeBytes,
                        sha256 = item.sha256,
                        stagingPath = ".tmp/items/${itemToken(item.categoryId, item.itemId)}.part",
                    )
                },
            )
            manifests[transferId] = incoming
            return manifestAck(incoming.transferId, ACK_OK)
        } catch (_: Exception) {
            return manifestAck(incoming.transferId, ACK_TARGET_WRITE_FAILED)
        }
    }

    /**
     * CRC-valid chunks are written to a server-generated staging path and checkpointed in SQLite.
     * The successful chunk ACK means durable receipt only; it does not imply restore success.
     */
    @Synchronized
    fun acceptChunk(frame: ByteArray): Acknowledgement {
        val decoded = try {
            ProtocolCodec.decodeChunk(frame)
        } catch (failure: ProtocolException) {
            val status = if (failure.message?.contains("CRC32 mismatch") == true) {
                ACK_CRC32_MISMATCH
            } else {
                ACK_REJECTED
            }
            return malformedChunkAck(frame, status)
        } catch (_: Exception) {
            return malformedChunkAck(frame, ACK_REJECTED)
        }

        val header = decoded.header
        val transferId = header.transferId.toString()
        val transfer = try {
            database.transfer(transferId)
        } catch (_: SQLiteException) {
            null
        } ?: return chunkAck(decoded, ACK_TRANSFER_MISMATCH)
        if (transfer.status != ReceiverDatabase.TRANSFER_RUNNING) {
            return chunkAck(decoded, ACK_TRANSFER_MISMATCH)
        }

        val manifest = try {
            manifestFor(transfer)
        } catch (_: Exception) {
            return chunkAck(decoded, ACK_INVALID_MANIFEST)
        }
        val manifestItem = manifest.items[hashKey(header.categoryIdHash, header.itemIdHash)]
            ?: return chunkAck(decoded, ACK_INVALID_MANIFEST)
        val item = try {
            database.item(transferId, manifestItem.categoryId, manifestItem.itemId)
        } catch (_: SQLiteException) {
            null
        } ?: return chunkAck(decoded, ACK_INVALID_MANIFEST)

        val payloadHash = sha256Hex(decoded.payload)
        if (header.chunkIndex < item.nextExpectedChunk || item.status in TERMINAL_ITEM_STATUSES) {
            val prior = runCatching {
                database.chunk(transferId, item.categoryId, item.itemId, header.chunkIndex)
            }.getOrNull()
            return if (prior != null && prior.chunkSize == decoded.payload.size &&
                prior.crc32 == header.payloadCrc32 && prior.sha256 == payloadHash
            ) {
                if (item.status == ReceiverDatabase.ITEM_RECEIVING &&
                    item.totalChunks != null && item.nextExpectedChunk == item.totalChunks
                ) {
                    finalizeItem(item)
                }
                chunkAck(decoded, ACK_OK)
            } else {
                chunkAck(decoded, ACK_REJECTED)
            }
        }
        if (header.chunkIndex != item.nextExpectedChunk) return chunkAck(decoded, ACK_REJECTED)
        if (header.totalChunks !in 1L..MAX_UINT32 ||
            (item.declaredSize == 0L && header.totalChunks != 1L) ||
            (item.declaredSize > 0L && header.totalChunks > item.declaredSize)
        ) {
            return chunkAck(decoded, ACK_REJECTED)
        }

        val existingTotal = item.totalChunks
        if (existingTotal != null && existingTotal != header.totalChunks) return chunkAck(decoded, ACK_REJECTED)
        if (item.status == ReceiverDatabase.ITEM_PENDING && header.chunkIndex != 0L) {
            return chunkAck(decoded, ACK_REJECTED)
        }
        val nominalChunkSize = item.nominalChunkSize ?: decoded.payload.size
        val isLastChunk = header.chunkIndex == header.totalChunks - 1
        val chunkShapeValid = if (item.declaredSize == 0L) {
            isLastChunk && decoded.payload.isEmpty()
        } else if (isLastChunk) {
            decoded.payload.isNotEmpty() && decoded.payload.size <= nominalChunkSize
        } else {
            decoded.payload.isNotEmpty() && decoded.payload.size == nominalChunkSize
        }
        if (!chunkShapeValid || (item.totalChunks != null && item.nominalChunkSize != nominalChunkSize)) {
            return chunkAck(decoded, ACK_REJECTED)
        }

        val nextOffset = item.nextByteOffset + decoded.payload.size
        if (nextOffset < item.nextByteOffset || nextOffset > item.declaredSize ||
            (isLastChunk && nextOffset != item.declaredSize) || (!isLastChunk && nextOffset >= item.declaredSize)
        ) {
            return rejectItem(item, decoded, "SHA256_VERIFY", "SHA256_MISMATCH")
        }

        val partialFile = stagingFile(item)
        try {
            val parent = partialFile.parentFile ?: throw IOException("Missing staging parent")
            if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Could not create staging directory")
            RandomAccessFile(partialFile, "rw").use { file ->
                when {
                    file.length() < item.nextByteOffset -> {
                        return rejectItem(item, decoded, "RECEIVE", REASON_STAGING_CORRUPTED)
                    }
                    file.length() > item.nextByteOffset -> file.setLength(item.nextByteOffset)
                }
                file.seek(item.nextByteOffset)
                file.write(decoded.payload)
                file.fd.sync()
            }
            database.commitChunk(
                item = item,
                chunkIndex = header.chunkIndex,
                byteOffset = item.nextByteOffset,
                chunkSize = decoded.payload.size,
                crc32 = header.payloadCrc32,
                payloadSha256 = payloadHash,
                totalChunks = header.totalChunks,
                nominalChunkSize = nominalChunkSize,
                nextByteOffset = nextOffset,
            )
        } catch (_: Exception) {
            runCatching {
                if (partialFile.exists()) RandomAccessFile(partialFile, "rw").use { it.setLength(item.nextByteOffset) }
            }
            return chunkAck(decoded, ACK_TARGET_WRITE_FAILED)
        }

        if (isLastChunk) {
            try {
                finalizeItem(item)
            } catch (_: Exception) {
                return chunkAck(decoded, ACK_TARGET_WRITE_FAILED)
            }
        }
        return chunkAck(decoded, ACK_OK)
    }

    /** Returns only verified app-private bytes; category restorers consume this in a later stage. */
    @Synchronized
    fun verifiedItemFile(transferId: UUID, categoryId: String, itemId: String): File? {
        val row = database.item(transferId.toString(), categoryId, itemId) ?: return null
        if (row.status != ReceiverDatabase.ITEM_VERIFIED) return null
        return finalFile(row).takeIf(File::isFile)
    }

    /** Reads aggregate progress from durable item cursors, not from incoming frames or retry count. */
    @Synchronized
    fun durableProgress(transferId: UUID): DurableTransferProgress? {
        val transferIdText = transferId.toString()
        val transfer = database.transfer(transferIdText) ?: return null
        val manifest = manifestFor(transfer)
        val total = manifest.items.values.fold(0L) { sum, item -> sum.saturatingAdd(item.sizeBytes) }
        val received = database.itemsForTransfer(transferIdText).fold(0L) { sum, item ->
            sum.saturatingAdd(item.nextByteOffset.coerceIn(0L, item.declaredSize))
        }.coerceAtMost(total)
        return DurableTransferProgress(transferIdText, received, total)
    }

    /** Lists completed transfers and aggregate restore counts without exposing filenames or payload. */
    @Synchronized
    fun availableRestoreTransfers(): List<ReceivedTransferSummary> {
        cleanupRestoredPayloads()
        return database.completedTransfers().mapNotNull { transfer ->
            val manifest = runCatching { manifestFor(transfer) }.getOrNull() ?: return@mapNotNull null
            if (manifest.transferId.toString() != transfer.transferId) return@mapNotNull null
            val rows = database.itemsForTransfer(transfer.transferId)
            val verified = rows.filter { it.status == ReceiverDatabase.ITEM_VERIFIED }
            val restored = verified.count { it.restoreStatus == ReceiverDatabase.ITEM_RESTORED }
            val pendingRestore = verified.filter { it.restoreStatus != ReceiverDatabase.ITEM_RESTORED }
            val restorable = pendingRestore.filter { isFileMediaRestoreCategory(it.categoryId) }
            val restorableApks = pendingRestore.filter { it.categoryId == "apks" }
            val restorableContacts = pendingRestore.filter { it.categoryId == "contacts" }
            val restorableCalendar = pendingRestore.filter { it.categoryId == "calendar" }
            ReceivedTransferSummary(
                transferId = transfer.transferId,
                manifestSha256 = transfer.manifestSha256,
                verifiedItemCount = verified.size,
                restorableItemCount = restorable.size,
                restorableBytes = restorable.fold(0L) { total, item -> total.saturatingAdd(item.declaredSize) },
                restoredItemCount = restored,
                unavailableVerifiedItemCount = verified.size - restored - restorable.size - restorableApks.size - restorableContacts.size - restorableCalendar.size,
                failedItemCount = rows.count { it.status == ReceiverDatabase.ITEM_FAILED },
                restorableApkItemCount = restorableApks.size,
                restorableApkBytes = restorableApks.fold(0L) { total, item -> total.saturatingAdd(item.declaredSize) },
                restorableContactItemCount = restorableContacts.size,
                restorableContactBytes = restorableContacts.fold(0L) { total, item -> total.saturatingAdd(item.declaredSize) },
                restorableCalendarItemCount = restorableCalendar.size,
                restorableCalendarBytes = restorableCalendar.fold(0L) { total, item -> total.saturatingAdd(item.declaredSize) },
            )
        }
    }

    /** Returns receiver-verified items only after a durable final receipt exists. */
    @Synchronized
    fun verifiedRestoreItems(transferId: String): List<VerifiedRestoreItem> {
        cleanupRestoredPayloads()
        val transfer = database.transfer(transferId) ?: return emptyList()
        require(transfer.status == ReceiverDatabase.TRANSFER_COMPLETED && transfer.receiptBytes != null) {
            "Transfer is not complete and receipted"
        }
        val manifest = manifestFor(transfer)
        val byIdentity = manifest.items.values.associateBy { it.categoryId to it.itemId }
        return database.itemsForTransfer(transferId).asSequence()
            .filter { it.status == ReceiverDatabase.ITEM_VERIFIED }
            .filter { it.restoreStatus != ReceiverDatabase.ITEM_RESTORED }
            .mapNotNull { row ->
                val source = byIdentity[row.categoryId to row.itemId] ?: return@mapNotNull null
                if (row.declaredSize != source.sizeBytes || row.expectedSha256 != source.sha256) {
                    return@mapNotNull null
                }
                VerifiedRestoreItem(
                    transferId = transfer.transferId,
                    manifestSha256 = transfer.manifestSha256,
                    categoryId = row.categoryId,
                    itemId = row.itemId,
                    sizeBytes = row.declaredSize,
                    sha256 = row.expectedSha256,
                    metadata = source.metadata.toPlainMap(),
                    payloadFile = finalFile(row),
                )
            }
            .toList()
    }

    /** Record post-verified restore success, then remove only the matching app-private payload. */
    @Synchronized
    fun recordPostVerifiedRestore(item: VerifiedRestoreItem): Boolean {
        val transfer = database.transfer(item.transferId) ?: return false
        if (transfer.status != ReceiverDatabase.TRANSFER_COMPLETED || transfer.receiptBytes == null ||
            transfer.manifestSha256 != item.manifestSha256
        ) return false
        val manifest = manifestFor(transfer)
        val source = manifest.items.values.firstOrNull {
            it.categoryId == item.categoryId && it.itemId == item.itemId
        } ?: return false
        if (source.sizeBytes != item.sizeBytes || source.sha256 != item.sha256) return false
        val row = database.item(item.transferId, item.categoryId, item.itemId) ?: return false
        if (row.status != ReceiverDatabase.ITEM_VERIFIED || row.declaredSize != item.sizeBytes ||
            row.expectedSha256 != item.sha256
        ) return false
        if (row.restoreStatus != ReceiverDatabase.ITEM_RESTORED && !database.markItemRestored(row)) return false
        val payload = finalFile(row)
        return !payload.exists() || payload.delete()
    }

    /** Exact app-private bytes shown to the user before an abandon/cleanup confirmation. */
    @Synchronized
    fun pendingTransferCleanupBytes(transferId: String): Long? {
        val transfer = database.transfer(transferId) ?: return null
        if (transfer.status !in setOf(
                ReceiverDatabase.TRANSFER_RUNNING,
                ReceiverDatabase.TRANSFER_COMPLETED,
                ReceiverDatabase.TRANSFER_ABANDONED,
            )
        ) return null
        val directory = transferDirectory(transfer.transferId) ?: return null
        return directoryBytes(directory)
    }

    /** Create a short-lived one-time authorization from the exact amount currently shown. */
    @Synchronized
    fun approveAbandonAfterUserConfirmation(
        transferId: String,
        displayedBytes: Long,
        confirmedByUser: Boolean,
    ): StagingCleanupApproval? {
        if (!confirmedByUser || pendingTransferCleanupBytes(transferId) != displayedBytes) return null
        return cleanupApprovalGate.approveAfterUserConfirmation(transferId, displayedBytes, confirmedByUser = true)
    }

    /** Abandon only after explicit confirmation of the current exact byte count. */
    @Synchronized
    fun abandonTransfer(transferId: String, approval: StagingCleanupApproval?): Boolean {
        val transfer = database.transfer(transferId) ?: return false
        if (transfer.status !in setOf(
                ReceiverDatabase.TRANSFER_RUNNING,
                ReceiverDatabase.TRANSFER_COMPLETED,
                ReceiverDatabase.TRANSFER_ABANDONED,
            )
        ) return false
        val directory = transferDirectory(transfer.transferId) ?: return false
        if (!cleanupApprovalGate.consume(approval, transferId, directoryBytes(directory))) return false
        if (!database.markTransferAbandoned(transfer.transferId)) return false
        manifests.remove(transfer.transferId)
        return !directory.exists() || directory.deleteRecursively()
    }

    /** Builds and persists a final receipt only after every manifest item is classified. */
    @Synchronized
    fun buildFinalReceipt(transferId: UUID): ByteArray {
        val transfer = database.transfer(transferId.toString())
            ?: throw IllegalArgumentException("Unknown transfer ID")
        transfer.receiptBytes?.let { return it }
        require(transfer.status == ReceiverDatabase.TRANSFER_RUNNING) { "Transfer is not active" }
        val manifest = manifestFor(transfer)
        val rows = database.receiptItems(transfer.transferId)
        require(rows.size == manifest.items.size) { "Not every manifest item has a receiver result" }
        require(rows.all { it.status in TERMINAL_ITEM_STATUSES }) { "Transfer still has incomplete items" }

        val categories = buildJsonObject {
            manifest.categoryIds.forEach { categoryId ->
                val categoryItems = rows.filter { it.categoryId == categoryId }
                val failed = categoryItems.filter { it.status == ReceiverDatabase.ITEM_FAILED }
                put(
                    categoryId,
                    buildJsonObject {
                        put("items_received", JsonPrimitive(categoryItems.size))
                        put("items_verified", JsonPrimitive(categoryItems.count { it.status == ReceiverDatabase.ITEM_VERIFIED }))
                        put("items_failed", JsonPrimitive(failed.size))
                        put(
                            "failures",
                            buildJsonArray {
                                failed.forEach { item ->
                                    add(
                                        buildJsonObject {
                                            put("item_id", JsonPrimitive(item.itemId))
                                            put("stage", JsonPrimitive(item.failureStage ?: "RECEIVE"))
                                            put("reason", JsonPrimitive(item.failureReason ?: "IO_ERROR"))
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }
        val receipt = buildJsonObject {
            put("version", JsonPrimitive("1.0"))
            put("transfer_id", JsonPrimitive(transfer.transferId))
            put("received_at", JsonPrimitive(Instant.now().toString()))
            put("categories", categories)
        }
        val bytes = RECEIPT_JSON.encodeToString(JsonObject.serializer(), receipt).toByteArray(StandardCharsets.UTF_8)
        database.storeReceipt(transfer.transferId, bytes)
        return bytes
    }

    @Synchronized
    override fun close() = database.close()

    private fun manifestFor(transfer: StoredTransfer): IncomingManifest {
        manifests[transfer.transferId]?.let { return it }
        return IncomingManifestValidator.parse(transfer.manifestBytes).also { manifests[transfer.transferId] = it }
    }

    private fun cleanupRestoredPayloads() {
        database.completedTransfers().forEach { transfer ->
            database.itemsForTransfer(transfer.transferId)
                .filter { it.status == ReceiverDatabase.ITEM_VERIFIED && it.restoreStatus == ReceiverDatabase.ITEM_RESTORED }
                .forEach { item ->
                    val payload = finalFile(item)
                    if (payload.exists()) payload.delete()
                }
        }
    }

    private fun transferDirectory(transferId: String): File? {
        val parsed = runCatching { UUID.fromString(transferId) }.getOrNull() ?: return null
        if (parsed.toString() != transferId) return null
        return File(receiverRoot, transferId)
    }

    private fun directoryBytes(directory: File): Long {
        if (!directory.exists()) return 0L
        var total = 0L
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            val size = file.length()
            total = if (size > Long.MAX_VALUE - total) Long.MAX_VALUE else total + size
        }
        return total
    }

    private fun finalizeItem(original: StoredItem) {
        val current = database.item(original.transferId, original.categoryId, original.itemId) ?: return
        val totalChunks = current.totalChunks ?: return
        if (current.status != ReceiverDatabase.ITEM_RECEIVING || current.nextExpectedChunk != totalChunks) return
        val source = stagingFile(current)
        val destination = finalFile(current)
        if (destination.isFile) {
            val existing = runCatching { digestFile(destination) }.getOrNull()
            if (existing != null && existing.first == current.declaredSize &&
                existing.second == current.expectedSha256
            ) {
                source.delete()
                database.markItemVerified(current)
            } else {
                database.markItemFailed(current, "RECEIVE", REASON_STAGING_CORRUPTED)
            }
            return
        }
        val digest = runCatching { digestFile(source) }.getOrElse {
            database.markItemFailed(current, "RECEIVE", REASON_STAGING_CORRUPTED)
            return
        }
        if (digest.first != current.declaredSize || digest.second != current.expectedSha256) {
            database.markItemFailed(current, "SHA256_VERIFY", "SHA256_MISMATCH")
            source.delete()
            return
        }

        val parent = destination.parentFile
        if (parent == null || (!parent.mkdirs() && !parent.isDirectory)) {
            database.markItemFailed(current, "RECEIVE", "TARGET_WRITE_FAILED")
            return
        }
        if (destination.exists()) {
            val existing = runCatching { digestFile(destination) }.getOrNull()
            if (existing?.first != current.declaredSize || existing.second != current.expectedSha256) {
                database.markItemFailed(current, "RECEIVE", REASON_STAGING_CORRUPTED)
                return
            }
            source.delete()
        } else if (!source.renameTo(destination)) {
            database.markItemFailed(current, "RECEIVE", "TARGET_WRITE_FAILED")
            return
        }
        database.markItemVerified(current)
    }

    private fun rejectItem(
        item: StoredItem,
        decoded: com.transferkit.client.protocol.DecodedChunk,
        stage: String,
        reason: String,
    ): Acknowledgement {
        try {
            database.markItemFailed(item, stage, reason)
        } catch (_: Exception) {
            return chunkAck(decoded, ACK_TARGET_WRITE_FAILED)
        }
        stagingFile(item).delete()
        return chunkAck(decoded, ACK_REJECTED)
    }

    private fun stagingFile(item: StoredItem): File {
        require(!File(item.stagingPath).isAbsolute) { "Receiver staging locator must be relative" }
        require(item.stagingPath.startsWith("$STAGING_DIRECTORY/$ITEMS_DIRECTORY/")) {
            "Receiver staging locator must remain in the owned staging area"
        }
        require(item.stagingPath.split('/', '\\').none { it == ".." || it.isEmpty() }) {
            "Receiver staging locator contains an invalid path segment"
        }
        val transferDirectory = File(receiverRoot, item.transferId).canonicalFile
        val payload = File(transferDirectory, item.stagingPath).canonicalFile
        require(payload.path.startsWith(transferDirectory.path + File.separator)) {
            "Receiver staging locator escapes its transfer directory"
        }
        return payload
    }

    private fun finalFile(item: StoredItem): File = File(
        File(File(receiverRoot, item.transferId), FINAL_DIRECTORY),
        "${itemToken(item.categoryId, item.itemId)}.bin",
    )

    private fun itemToken(categoryId: String, itemId: String): String =
        sha256Hex("$categoryId\u0000$itemId".toByteArray(StandardCharsets.UTF_8))

    private fun manifestAck(transferId: UUID, status: Int) = Acknowledgement(
        kind = AckKind.MANIFEST,
        transferId = transferId,
        categoryIdHash = ZERO_HASH,
        itemIdHash = ZERO_HASH,
        chunkIndex = 0L,
        payloadCrc32 = 0L,
        status = status,
    )

    private fun chunkAck(decoded: com.transferkit.client.protocol.DecodedChunk, status: Int) = Acknowledgement(
        kind = AckKind.CHUNK,
        transferId = decoded.header.transferId,
        categoryIdHash = decoded.header.categoryIdHash,
        itemIdHash = decoded.header.itemIdHash,
        chunkIndex = decoded.header.chunkIndex,
        payloadCrc32 = decoded.header.payloadCrc32,
        status = status,
    )

    private fun malformedChunkAck(frame: ByteArray, status: Int): Acknowledgement {
        if (frame.size < CHUNK_HEADER_BYTES) {
            return Acknowledgement(AckKind.CHUNK, UUID(0L, 0L), ZERO_HASH, ZERO_HASH, 0L, 0L, status)
        }
        return runCatching {
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            buffer.position(5)
            val id = UUID(buffer.long, buffer.long)
            val categoryHash = ByteArray(4).also(buffer::get)
            val itemHash = ByteArray(4).also(buffer::get)
            val chunkIndex = buffer.int.toLong() and MAX_UINT32
            Acknowledgement(
                AckKind.CHUNK,
                id,
                categoryHash,
                itemHash,
                chunkIndex,
                frame.copyOfRange(41, 45).let { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).int.toLong() and MAX_UINT32 },
                status,
            )
        }.getOrElse {
            Acknowledgement(AckKind.CHUNK, UUID(0L, 0L), ZERO_HASH, ZERO_HASH, 0L, 0L, status)
        }
    }

    private fun digestFile(file: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                count += read
                digest.update(buffer, 0, read)
            }
        }
        return count to digest.digest().toHex()
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun Long.saturatingAdd(value: Long): Long =
        if (value > Long.MAX_VALUE - this) Long.MAX_VALUE else this + value

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hashKey(categoryHash: ByteArray, itemHash: ByteArray): String = categoryHash.toHex() + itemHash.toHex()

    private companion object {
        const val RECEIVER_DIRECTORY = "transferkit-receiver"
        const val STAGING_DIRECTORY = ".tmp"
        const val FINAL_DIRECTORY = "items"
        const val ITEMS_DIRECTORY = "items"
        const val CHUNK_HEADER_BYTES = 45
        const val DIGEST_BUFFER_SIZE = 128 * 1024
        const val ACK_OK = 0
        const val ACK_REJECTED = 1
        const val ACK_CRC32_MISMATCH = 2
        const val ACK_INVALID_MANIFEST = 3
        const val ACK_TARGET_WRITE_FAILED = 4
        const val ACK_TRANSFER_MISMATCH = 5
        const val REASON_STAGING_CORRUPTED = "STAGING_CORRUPTED"
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        val ZERO_HASH = ByteArray(4)
        val TERMINAL_ITEM_STATUSES = setOf(ReceiverDatabase.ITEM_VERIFIED, ReceiverDatabase.ITEM_FAILED)
        val RECEIPT_JSON = Json { encodeDefaults = true; prettyPrint = false }
    }
}

internal data class IncomingManifest(
    val transferId: UUID,
    val createdAt: String,
    val categoryIds: List<String>,
    val items: Map<String, IncomingItem>,
)

internal data class IncomingItem(
    val categoryId: String,
    val itemId: String,
    val sizeBytes: Long,
    val sha256: String,
    val metadata: JsonObject,
)

/** Strict local validation mirrors the shared manifest schema and semantic contract. */
internal object IncomingManifestValidator {
    private val json = Json { isLenient = false; ignoreUnknownKeys = false }
    private val allowedCategories = setOf(
        "photos", "videos", "audio", "documents", "downloads", "contacts", "calendar", "sms",
        "call_log", "apks", "settings", "app_data", "wifi_passwords", "launcher_layout", "accounts",
        "drm", "banking_apps", "two_factor_tokens",
    )
    private val shaPattern = Regex("^[a-f0-9]{64}$")
    private val canonicalTimestampPattern =
        Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{6})?Z$")
    private val canonicalJson = Json { encodeDefaults = true; prettyPrint = false }

    fun parse(bytes: ByteArray): IncomingManifest {
        require(bytes.isNotEmpty() && bytes.size <= MAX_MANIFEST_BYTES) { "Manifest size is invalid" }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Manifest root must be an object")
        require(root.keys == setOf("version", "transfer_id", "created_at", "source_device", "categories"))
        require(root.string("version") == "1.0")
        val transferIdText = root.string("transfer_id")
        val transferId = UUID.fromString(transferIdText)
        require(transferId.toString() == transferIdText)
        val createdAt = root.string("created_at")
        require(canonicalTimestampPattern.matches(createdAt))
        Instant.parse(createdAt)

        val device = root.objectValue("source_device")
        require(device.keys == setOf("manufacturer", "model", "sdk", "fingerprint"))
        require(device.string("manufacturer").isNotBlank() && device.string("manufacturer").codePointLength() <= 128)
        require(device.string("model").isNotBlank() && device.string("model").codePointLength() <= 128)
        require(device.integer("sdk") >= 26)
        require(device.string("fingerprint").isNotBlank() && device.string("fingerprint").codePointLength() <= 512)

        val categories = root.objectValue("categories")
        require(categories.isNotEmpty() && categories.keys.all { it in allowedCategories })
        val itemIndex = LinkedHashMap<String, IncomingItem>()
        var totalItems = 0L
        categories.forEach { (categoryId, rawCategory) ->
            val category = rawCategory as? JsonObject ?: error("Category must be an object")
            require(category.keys == setOf("provider", "method", "items"))
            require(category.string("provider").isNotBlank() && category.string("provider").codePointLength() <= 128)
            require(category.string("method").isNotBlank() && category.string("method").codePointLength() <= 128)
            val items = category["items"] as? JsonArray ?: error("Category items must be an array")
            totalItems += items.size.toLong()
            validateManifestItemCount(totalItems)
            val seenItemIds = HashSet<String>()
            items.forEach { rawItem ->
                val item = rawItem as? JsonObject ?: error("Manifest item must be an object")
                require(item.keys == setOf("id", "size", "sha256", "metadata"))
                val itemId = item.string("id")
                require(itemId.isNotBlank() && itemId.codePointLength() <= 512 && seenItemIds.add(itemId))
                val sizeBytes = item.long("size")
                require(sizeBytes in 0..MAX_SAFE_INTEGER)
                val sha256 = item.string("sha256")
                require(shaPattern.matches(sha256))
                val metadata = item.objectValue("metadata")
                val metadataBytes = canonicalJson.encodeToString(JsonElement.serializer(), canonicalize(metadata))
                    .toByteArray(StandardCharsets.UTF_8)
                require(metadataBytes.size <= MAX_METADATA_BYTES)
                val incoming = IncomingItem(categoryId, itemId, sizeBytes, sha256, metadata)
                val key = hashKey(ProtocolCodec.idHash(categoryId), ProtocolCodec.idHash(itemId))
                require(itemIndex.put(key, incoming) == null) { "Manifest contains a wire ID hash collision" }
            }
        }
        return IncomingManifest(transferId, createdAt, categories.keys.sorted(), itemIndex)
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$key must be a string")

    private fun JsonObject.objectValue(key: String): JsonObject =
        this[key] as? JsonObject ?: error("$key must be an object")

    private fun JsonObject.integer(key: String): Int {
        val value = this[key] as? JsonPrimitive ?: error("$key must be an integer")
        require(!value.isString)
        return try {
            BigDecimal(value.content).intValueExact()
        } catch (_: NumberFormatException) {
            error("$key must be an integer")
        } catch (_: ArithmeticException) {
            error("$key must be an integer")
        }
    }

    private fun JsonObject.long(key: String): Long {
        val value = this[key] as? JsonPrimitive ?: error("$key must be an integer")
        require(!value.isString)
        return try {
            BigDecimal(value.content).longValueExact()
        } catch (_: NumberFormatException) {
            error("$key must be an integer")
        } catch (_: ArithmeticException) {
            error("$key must be an integer")
        }
    }

    private fun canonicalize(element: JsonElement, depth: Int = 0): JsonElement {
        require(depth <= MAX_METADATA_DEPTH) { "Manifest metadata exceeds the maximum nesting depth" }
        return when (element) {
        is JsonObject -> JsonObject(element.toSortedMap(JSON_CODE_POINT_ORDER).mapValues { (_, value) -> canonicalize(value, depth + 1) })
        is JsonArray -> JsonArray(element.map { canonicalize(it, depth + 1) })
        JsonNull -> JsonNull
        else -> element
        }
    }

    private fun hashKey(categoryHash: ByteArray, itemHash: ByteArray): String =
        categoryHash.joinToString("") { "%02x".format(it.toInt() and 0xff) } +
            itemHash.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val MAX_METADATA_BYTES = 64 * 1024
    private const val MAX_METADATA_DEPTH = 32
}

private fun String.codePointLength(): Int = codePointCount(0, length)

private fun JsonObject.toPlainMap(): Map<String, Any?> =
    entries.associate { (key, value) -> key to value.toPlainValue() }

private fun JsonElement.toPlainValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> toPlainMap()
    is JsonArray -> map { it.toPlainValue() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull!!
        longOrNull != null -> longOrNull!!
        doubleOrNull != null -> doubleOrNull!!
        else -> content
    }
}
