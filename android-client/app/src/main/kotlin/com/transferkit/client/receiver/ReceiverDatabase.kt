package com.transferkit.client.receiver

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

internal data class StoredTransfer(
    val transferId: String,
    val manifestSha256: String,
    val manifestBytes: ByteArray,
    val status: String,
    val receiptBytes: ByteArray?,
    val sourceFingerprint: String?,
    val targetFingerprint: String?,
    val protocolVersion: String?,
    val appVersion: String?,
    val stagingDirectory: String,
    val lastCheckpointAt: String,
    val expiresAt: String,
)

internal data class StoredItem(
    val transferId: String,
    val categoryId: String,
    val itemId: String,
    val declaredSize: Long,
    val expectedSha256: String,
    val status: String,
    val restoreStatus: String,
    val nextExpectedChunk: Long,
    val nextByteOffset: Long,
    val totalChunks: Long?,
    val nominalChunkSize: Int?,
    val stagingPath: String,
    val failureStage: String?,
    val failureReason: String?,
)

internal data class StoredChunk(
    val chunkSize: Int,
    val crc32: Long,
    val sha256: String,
)

internal data class ReceiptItem(
    val categoryId: String,
    val itemId: String,
    val status: String,
    val failureStage: String?,
    val failureReason: String?,
)

/** Durable item/chunk state. ACKs are sent only after the corresponding transaction commits. */
internal class ReceiverDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE received_transfers (
                transfer_id TEXT PRIMARY KEY NOT NULL,
                manifest_sha256 TEXT NOT NULL,
                manifest_bytes BLOB NOT NULL,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                receipt_bytes BLOB,
                source_fingerprint TEXT,
                target_fingerprint TEXT,
                protocol_version TEXT,
                app_version TEXT,
                staging_dir TEXT NOT NULL,
                last_checkpoint_at TEXT NOT NULL,
                expires_at TEXT NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE received_items (
                transfer_id TEXT NOT NULL,
                category_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                declared_size INTEGER NOT NULL,
                expected_sha256 TEXT NOT NULL,
                status TEXT NOT NULL,
                restore_status TEXT NOT NULL DEFAULT 'PENDING',
                next_expected_chunk INTEGER NOT NULL DEFAULT 0,
                next_byte_offset INTEGER NOT NULL DEFAULT 0,
                total_chunks INTEGER,
                nominal_chunk_size INTEGER,
                failure_stage TEXT,
                failure_reason TEXT,
                PRIMARY KEY (transfer_id, category_id, item_id),
                FOREIGN KEY (transfer_id) REFERENCES received_transfers(transfer_id) ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE item_checkpoints (
                transfer_id TEXT NOT NULL,
                category_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                next_expected_chunk INTEGER NOT NULL DEFAULT 0 CHECK(next_expected_chunk >= 0),
                next_byte_offset INTEGER NOT NULL DEFAULT 0 CHECK(next_byte_offset >= 0),
                total_chunks INTEGER CHECK(total_chunks IS NULL OR total_chunks > 0),
                nominal_chunk_size INTEGER CHECK(nominal_chunk_size IS NULL OR nominal_chunk_size > 0),
                staging_path TEXT NOT NULL,
                last_checkpoint_at TEXT NOT NULL,
                PRIMARY KEY (transfer_id, category_id, item_id),
                FOREIGN KEY (transfer_id, category_id, item_id)
                    REFERENCES received_items(transfer_id, category_id, item_id) ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE received_chunks (
                transfer_id TEXT NOT NULL,
                category_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                byte_offset INTEGER NOT NULL,
                chunk_size INTEGER NOT NULL,
                crc32 INTEGER NOT NULL,
                payload_sha256 TEXT NOT NULL,
                PRIMARY KEY (transfer_id, category_id, item_id, chunk_index),
                FOREIGN KEY (transfer_id, category_id, item_id)
                    REFERENCES received_items(transfer_id, category_id, item_id) ON DELETE CASCADE
            )""".trimIndent(),
        )
        createRecoveryIndexesAndGuards(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2 && newVersion >= 2) {
            db.execSQL("ALTER TABLE received_items ADD COLUMN restore_status TEXT NOT NULL DEFAULT 'PENDING'")
        }
        if (oldVersion < 3 && newVersion >= 3) {
            migrateRecoverySchema(db)
        }
        if (newVersion != DATABASE_VERSION) {
            throw IllegalStateException("Receiver database migration is required: $oldVersion -> $newVersion")
        }
    }

    private fun migrateRecoverySchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE received_transfers ADD COLUMN source_fingerprint TEXT")
        db.execSQL("ALTER TABLE received_transfers ADD COLUMN target_fingerprint TEXT")
        db.execSQL("ALTER TABLE received_transfers ADD COLUMN protocol_version TEXT")
        db.execSQL("ALTER TABLE received_transfers ADD COLUMN app_version TEXT")
        db.execSQL("ALTER TABLE received_transfers ADD COLUMN staging_dir TEXT")
        db.execSQL("ALTER TABLE received_transfers ADD COLUMN last_checkpoint_at TEXT")
        db.execSQL("ALTER TABLE received_transfers ADD COLUMN expires_at TEXT")
        db.execSQL("UPDATE received_transfers SET staging_dir = transfer_id WHERE staging_dir IS NULL")
        db.execSQL("UPDATE received_transfers SET protocol_version = ? WHERE protocol_version IS NULL", arrayOf(CURRENT_PROTOCOL_VERSION))

        db.query("received_transfers", arrayOf("transfer_id", "updated_at"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val transferId = cursor.getString(0)
                val updatedAt = cursor.getString(1)
                val checkpointAt = normalizeTimestamp(updatedAt)
                db.execSQL(
                    "UPDATE received_transfers SET last_checkpoint_at = ?, expires_at = ? WHERE transfer_id = ?",
                    arrayOf(checkpointAt, retentionExpiry(checkpointAt), transferId),
                )
            }
        }

        db.execSQL(
            """CREATE TABLE item_checkpoints (
                transfer_id TEXT NOT NULL,
                category_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                next_expected_chunk INTEGER NOT NULL DEFAULT 0 CHECK(next_expected_chunk >= 0),
                next_byte_offset INTEGER NOT NULL DEFAULT 0 CHECK(next_byte_offset >= 0),
                total_chunks INTEGER CHECK(total_chunks IS NULL OR total_chunks > 0),
                nominal_chunk_size INTEGER CHECK(nominal_chunk_size IS NULL OR nominal_chunk_size > 0),
                staging_path TEXT NOT NULL,
                last_checkpoint_at TEXT NOT NULL,
                PRIMARY KEY (transfer_id, category_id, item_id),
                FOREIGN KEY (transfer_id, category_id, item_id)
                    REFERENCES received_items(transfer_id, category_id, item_id) ON DELETE CASCADE
            )""".trimIndent(),
        )

        db.rawQuery(
            """SELECT i.transfer_id, i.category_id, i.item_id, i.next_expected_chunk, i.next_byte_offset,
                      i.total_chunks, i.nominal_chunk_size, t.last_checkpoint_at
                 FROM received_items i JOIN received_transfers t ON t.transfer_id = i.transfer_id""",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val transferId = cursor.getString(0)
                val categoryId = cursor.getString(1)
                val itemId = cursor.getString(2)
                val parentTimestamp = cursor.getString(7)
                db.insertOrThrow(
                    "item_checkpoints",
                    null,
                    ContentValues().apply {
                        put("transfer_id", transferId)
                        put("category_id", categoryId)
                        put("item_id", itemId)
                        put("next_expected_chunk", cursor.getLong(3))
                        put("next_byte_offset", cursor.getLong(4))
                        if (cursor.isNull(5)) putNull("total_chunks") else put("total_chunks", cursor.getLong(5))
                        if (cursor.isNull(6)) putNull("nominal_chunk_size") else put("nominal_chunk_size", cursor.getInt(6))
                        put("staging_path", stagingPath(categoryId, itemId))
                        put("last_checkpoint_at", parentTimestamp)
                    },
                )
            }
        }
        createRecoveryIndexesAndGuards(db)
    }

    private fun createRecoveryIndexesAndGuards(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_received_transfers_recovery ON received_transfers(status, last_checkpoint_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_received_transfers_expiry ON received_transfers(status, expires_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_item_checkpoints_transfer ON item_checkpoints(transfer_id, category_id)")
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS trg_received_transfer_status_insert
                BEFORE INSERT ON received_transfers
                WHEN NEW.status NOT IN ('RUNNING', 'PAUSED', 'COMPLETED', 'ABANDONED', 'SUPERSEDED')
                BEGIN SELECT RAISE(ABORT, 'invalid transfer status'); END""".trimIndent(),
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS trg_received_transfer_status_update
                BEFORE UPDATE OF status ON received_transfers
                WHEN NEW.status NOT IN ('RUNNING', 'PAUSED', 'COMPLETED', 'ABANDONED', 'SUPERSEDED')
                BEGIN SELECT RAISE(ABORT, 'invalid transfer status'); END""".trimIndent(),
        )
    }

    private fun normalizeTimestamp(value: String): String = runCatching {
        Instant.parse(value).toString()
    }.getOrElse { Instant.now().toString() }

    private fun retentionExpiry(checkpointAt: String): String =
        Instant.parse(checkpointAt).plus(RETENTION_TTL).toString()

    private fun stagingPath(categoryId: String, itemId: String): String =
        ".tmp/items/${itemToken(categoryId, itemId)}.part"

    private fun itemToken(categoryId: String, itemId: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$categoryId\u0000$itemId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun transfer(transferId: String): StoredTransfer? = readableDatabase.query(
        "received_transfers",
        TRANSFER_COLUMNS,
        "transfer_id = ?",
        arrayOf(transferId),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        StoredTransfer(
            transferId = cursor.getString(0),
            manifestSha256 = cursor.getString(1),
            manifestBytes = cursor.getBlob(2),
            status = cursor.getString(3),
            receiptBytes = if (cursor.isNull(4)) null else cursor.getBlob(4),
            sourceFingerprint = if (cursor.isNull(5)) null else cursor.getString(5),
            targetFingerprint = if (cursor.isNull(6)) null else cursor.getString(6),
            protocolVersion = if (cursor.isNull(7)) null else cursor.getString(7),
            appVersion = if (cursor.isNull(8)) null else cursor.getString(8),
            stagingDirectory = cursor.getString(9),
            lastCheckpointAt = cursor.getString(10),
            expiresAt = cursor.getString(11),
        )
    }

    fun completedTransfers(): List<StoredTransfer> = readableDatabase.query(
        "received_transfers",
        TRANSFER_COLUMNS,
        "status = ? AND receipt_bytes IS NOT NULL",
        arrayOf(TRANSFER_COMPLETED),
        null,
        null,
        "updated_at DESC, transfer_id ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    StoredTransfer(
                        transferId = cursor.getString(0),
                        manifestSha256 = cursor.getString(1),
                        manifestBytes = cursor.getBlob(2),
                        status = cursor.getString(3),
                        receiptBytes = if (cursor.isNull(4)) null else cursor.getBlob(4),
                        sourceFingerprint = if (cursor.isNull(5)) null else cursor.getString(5),
                        targetFingerprint = if (cursor.isNull(6)) null else cursor.getString(6),
                        protocolVersion = if (cursor.isNull(7)) null else cursor.getString(7),
                        appVersion = if (cursor.isNull(8)) null else cursor.getString(8),
                        stagingDirectory = cursor.getString(9),
                        lastCheckpointAt = cursor.getString(10),
                        expiresAt = cursor.getString(11),
                    ),
                )
            }
        }
    }

    fun insertManifest(
        transferId: String,
        manifestSha256: String,
        manifestBytes: ByteArray,
        receivedAt: String,
        items: List<ManifestItemRecord>,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = normalizeTimestamp(receivedAt)
            db.insertOrThrow(
                "received_transfers",
                null,
                ContentValues().apply {
                    put("transfer_id", transferId)
                    put("manifest_sha256", manifestSha256)
                    put("manifest_bytes", manifestBytes)
                    put("status", TRANSFER_RUNNING)
                    put("created_at", now)
                    put("updated_at", now)
                    put("protocol_version", CURRENT_PROTOCOL_VERSION)
                    put("staging_dir", transferId)
                    put("last_checkpoint_at", now)
                    put("expires_at", retentionExpiry(now))
                },
            )
            items.forEach { item ->
                db.insertOrThrow(
                    "received_items",
                    null,
                    ContentValues().apply {
                        put("transfer_id", transferId)
                        put("category_id", item.categoryId)
                        put("item_id", item.itemId)
                        put("declared_size", item.sizeBytes)
                        put("expected_sha256", item.sha256)
                        put("status", ITEM_PENDING)
                    },
                )
                db.insertOrThrow(
                    "item_checkpoints",
                    null,
                    ContentValues().apply {
                        put("transfer_id", transferId)
                        put("category_id", item.categoryId)
                        put("item_id", item.itemId)
                        put("next_expected_chunk", 0L)
                        put("next_byte_offset", 0L)
                        putNull("total_chunks")
                        putNull("nominal_chunk_size")
                        put("staging_path", item.stagingPath)
                        put("last_checkpoint_at", now)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun item(transferId: String, categoryId: String, itemId: String): StoredItem? = readableDatabase.rawQuery(
        ITEM_SELECT + " WHERE r.transfer_id = ? AND r.category_id = ? AND r.item_id = ? LIMIT 1",
        arrayOf(transferId, categoryId, itemId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        cursor.toStoredItem()
    }

    fun itemsForTransfer(transferId: String): List<StoredItem> = readableDatabase.rawQuery(
        ITEM_SELECT + " WHERE r.transfer_id = ? ORDER BY r.category_id ASC, r.item_id ASC",
        arrayOf(transferId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.toStoredItem())
            }
        }
    }

    private fun android.database.Cursor.toStoredItem(): StoredItem = StoredItem(
        transferId = getString(0),
        categoryId = getString(1),
        itemId = getString(2),
        declaredSize = getLong(3),
        expectedSha256 = getString(4),
        status = getString(5),
        restoreStatus = getString(6),
        nextExpectedChunk = getLong(7),
        nextByteOffset = getLong(8),
        totalChunks = if (isNull(9)) null else getLong(9),
        nominalChunkSize = if (isNull(10)) null else getInt(10),
        stagingPath = getString(11),
        failureStage = if (isNull(12)) null else getString(12),
        failureReason = if (isNull(13)) null else getString(13),
    )

    fun chunk(transferId: String, categoryId: String, itemId: String, chunkIndex: Long): StoredChunk? =
        readableDatabase.query(
            "received_chunks",
            arrayOf("chunk_size", "crc32", "payload_sha256"),
            "transfer_id = ? AND category_id = ? AND item_id = ? AND chunk_index = ?",
            arrayOf(transferId, categoryId, itemId, chunkIndex.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            StoredChunk(cursor.getInt(0), cursor.getLong(1), cursor.getString(2))
        }

    fun commitChunk(
        item: StoredItem,
        chunkIndex: Long,
        byteOffset: Long,
        chunkSize: Int,
        crc32: Long,
        payloadSha256: String,
        totalChunks: Long,
        nominalChunkSize: Int,
        nextByteOffset: Long,
    ) {
        val db = writableDatabase
        val now = Instant.now().toString()
        db.beginTransaction()
        try {
            db.insertOrThrow(
                "received_chunks",
                null,
                ContentValues().apply {
                    put("transfer_id", item.transferId)
                    put("category_id", item.categoryId)
                    put("item_id", item.itemId)
                    put("chunk_index", chunkIndex)
                    put("byte_offset", byteOffset)
                    put("chunk_size", chunkSize)
                    put("crc32", crc32)
                    put("payload_sha256", payloadSha256)
                },
            )
            // Keep v2 cursor columns synchronized for migration compatibility; Recovery reads item_checkpoints.
            val updated = db.update(
                "received_items",
                ContentValues().apply {
                    put("status", ITEM_RECEIVING)
                    put("next_expected_chunk", chunkIndex + 1)
                    put("next_byte_offset", nextByteOffset)
                    put("total_chunks", totalChunks)
                    put("nominal_chunk_size", nominalChunkSize)
                },
                "transfer_id = ? AND category_id = ? AND item_id = ? AND next_expected_chunk = ?",
                arrayOf(item.transferId, item.categoryId, item.itemId, chunkIndex.toString()),
            )
            check(updated == 1) { "Receiver checkpoint changed concurrently" }
            val checkpointUpdated = db.update(
                "item_checkpoints",
                ContentValues().apply {
                    put("next_expected_chunk", chunkIndex + 1)
                    put("next_byte_offset", nextByteOffset)
                    put("total_chunks", totalChunks)
                    put("nominal_chunk_size", nominalChunkSize)
                    put("last_checkpoint_at", now)
                },
                "transfer_id = ? AND category_id = ? AND item_id = ? AND next_expected_chunk = ?",
                arrayOf(item.transferId, item.categoryId, item.itemId, chunkIndex.toString()),
            )
            check(checkpointUpdated == 1) { "Recovery checkpoint changed concurrently" }
            val transferUpdated = db.update(
                "received_transfers",
                ContentValues().apply {
                    put("updated_at", now)
                    put("last_checkpoint_at", now)
                    put("expires_at", retentionExpiry(now))
                },
                "transfer_id = ?",
                arrayOf(item.transferId),
            )
            check(transferUpdated == 1) { "Recovery transfer checkpoint is missing" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markItemFailed(item: StoredItem, stage: String, reason: String) {
        val updated = writableDatabase.update(
            "received_items",
            ContentValues().apply {
                put("status", ITEM_FAILED)
                put("failure_stage", stage)
                put("failure_reason", reason)
            },
            "transfer_id = ? AND category_id = ? AND item_id = ? AND status IN (?, ?)",
            arrayOf(item.transferId, item.categoryId, item.itemId, ITEM_PENDING, ITEM_RECEIVING),
        )
        check(updated == 1) { "Could not persist receiver item failure" }
    }

    fun markItemVerified(item: StoredItem) {
        val updated = writableDatabase.update(
            "received_items",
            ContentValues().apply { put("status", ITEM_VERIFIED) },
            "transfer_id = ? AND category_id = ? AND item_id = ? AND status = ?",
            arrayOf(item.transferId, item.categoryId, item.itemId, ITEM_RECEIVING),
        )
        check(updated == 1) { "Could not persist verified receiver item" }
    }

    fun markItemRestored(item: StoredItem): Boolean = writableDatabase.update(
        "received_items",
        ContentValues().apply { put("restore_status", ITEM_RESTORED) },
        "transfer_id = ? AND category_id = ? AND item_id = ? AND status = ? AND restore_status = ?",
        arrayOf(item.transferId, item.categoryId, item.itemId, ITEM_VERIFIED, ITEM_RESTORE_PENDING),
    ) == 1

    fun receiptItems(transferId: String): List<ReceiptItem> = readableDatabase.query(
        "received_items",
        arrayOf("category_id", "item_id", "status", "failure_stage", "failure_reason"),
        "transfer_id = ?",
        arrayOf(transferId),
        null,
        null,
        "category_id ASC, item_id ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ReceiptItem(
                        categoryId = cursor.getString(0),
                        itemId = cursor.getString(1),
                        status = cursor.getString(2),
                        failureStage = if (cursor.isNull(3)) null else cursor.getString(3),
                        failureReason = if (cursor.isNull(4)) null else cursor.getString(4),
                    ),
                )
            }
        }
    }

    fun storeReceipt(transferId: String, receiptBytes: ByteArray) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val updated = db.update(
                "received_transfers",
                ContentValues().apply {
                    put("status", TRANSFER_COMPLETED)
                    put("updated_at", java.time.Instant.now().toString())
                    put("receipt_bytes", receiptBytes)
                },
                "transfer_id = ? AND status = ?",
                arrayOf(transferId, TRANSFER_RUNNING),
            )
            check(updated == 1) { "Could not persist final receipt" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Preserve a small tombstone while removing manifests, item rows, and chunk checkpoints. */
    fun markTransferAbandoned(transferId: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = transfer(transferId) ?: return false
            if (current.status == TRANSFER_ABANDONED) {
                db.setTransactionSuccessful()
                return true
            }
            if (current.status !in setOf(TRANSFER_RUNNING, TRANSFER_COMPLETED)) return false
            val updated = db.update(
                "received_transfers",
                ContentValues().apply {
                    put("status", TRANSFER_ABANDONED)
                    put("manifest_bytes", ByteArray(0))
                    putNull("receipt_bytes")
                    put("updated_at", java.time.Instant.now().toString())
                },
                "transfer_id = ? AND status IN (?, ?)",
                arrayOf(transferId, TRANSFER_RUNNING, TRANSFER_COMPLETED),
            )
            if (updated != 1) return false
            db.delete("received_items", "transfer_id = ?", arrayOf(transferId))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    data class ManifestItemRecord(
        val categoryId: String,
        val itemId: String,
        val sizeBytes: Long,
        val sha256: String,
        val stagingPath: String,
    )

    companion object {
        const val TRANSFER_RUNNING = "RUNNING"
        const val TRANSFER_PAUSED = "PAUSED"
        const val TRANSFER_COMPLETED = "COMPLETED"
        const val TRANSFER_ABANDONED = "ABANDONED"
        const val TRANSFER_SUPERSEDED = "SUPERSEDED"
        const val ITEM_PENDING = "PENDING"
        const val ITEM_RECEIVING = "RECEIVING"
        const val ITEM_VERIFIED = "VERIFIED"
        const val ITEM_FAILED = "FAILED"
        const val ITEM_RESTORE_PENDING = "PENDING"
        const val ITEM_RESTORED = "RESTORED"

        private const val CURRENT_PROTOCOL_VERSION = "1"
        private val RETENTION_TTL: Duration = Duration.ofDays(30)
        private val TRANSFER_COLUMNS = arrayOf(
            "transfer_id", "manifest_sha256", "manifest_bytes", "status", "receipt_bytes",
            "source_fingerprint", "target_fingerprint", "protocol_version", "app_version", "staging_dir",
            "last_checkpoint_at", "expires_at",
        )
        private const val ITEM_SELECT = """
            SELECT r.transfer_id, r.category_id, r.item_id, r.declared_size, r.expected_sha256,
                   r.status, r.restore_status, c.next_expected_chunk, c.next_byte_offset,
                   c.total_chunks, c.nominal_chunk_size, c.staging_path, r.failure_stage, r.failure_reason
            FROM received_items AS r
            JOIN item_checkpoints AS c
              ON c.transfer_id = r.transfer_id AND c.category_id = r.category_id AND c.item_id = r.item_id
        """
        private const val DATABASE_NAME = "transferkit-receiver.db"
        private const val DATABASE_VERSION = 3
    }
}
