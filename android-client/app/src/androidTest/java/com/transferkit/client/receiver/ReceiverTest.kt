package com.transferkit.client.receiver

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import com.transferkit.client.protocol.ProtocolCodec
import com.transferkit.client.transport.ReceiverStreamHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import com.transferkit.client.protocol.AckKind
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var receiver: AndroidTransferReceiver? = null

    @Before
    fun cleanPriorFixtureState() {
        cleanupReceiverState()
    }

    @After
    fun closeAndCleanFixtureState() {
        receiver?.close()
        receiver = null
        cleanupReceiverState()
    }

    @Test
    fun acceptsChunkVerifiesShaAndBuildsFinalReceipt() {
        val payload = "hello".toByteArray()
        val transferId = UUID.randomUUID()
        val active = newReceiver()

        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)
        val ack = active.acceptChunk(chunk(transferId, 0, 1, payload))

        assertEquals(ACK_OK, ack.status)
        val file = active.verifiedItemFile(transferId, CATEGORY_ID, ITEM_ID)
        assertNotNull(file)
        assertArrayEquals(payload, file!!.readBytes())

        val receipt = Json.parseToJsonElement(active.buildFinalReceipt(transferId).decodeToString()).jsonObject
        val category = receipt.getValue("categories").jsonObject.getValue(CATEGORY_ID).jsonObject
        assertEquals(1, category.getValue("items_received").jsonPrimitive.int)
        assertEquals(1, category.getValue("items_verified").jsonPrimitive.int)
        assertEquals(0, category.getValue("items_failed").jsonPrimitive.int)
        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)
    }

    @Test
    fun completedTransferInboxOffersOnlyManifestBoundVerifiedItems() {
        val payload = "hello".toByteArray()
        val transferId = UUID.randomUUID()
        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)
        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 0, 1, payload)).status)
        active.buildFinalReceipt(transferId)

        val summary = active.availableRestoreTransfers().single()
        assertEquals(transferId.toString(), summary.transferId)
        assertEquals(1, summary.verifiedItemCount)
        assertEquals(1, summary.restorableItemCount)
        assertEquals(payload.size.toLong(), summary.restorableBytes)

        val candidate = active.verifiedRestoreItems(transferId.toString()).single()
        assertEquals(transferId.toString(), candidate.transferId)
        assertEquals(summary.manifestSha256, candidate.manifestSha256)
        assertEquals(CATEGORY_ID, candidate.categoryId)
        assertEquals(ITEM_ID, candidate.itemId)
        assertEquals(payload.size.toLong(), candidate.sizeBytes)
        assertEquals(sha256(payload), candidate.sha256)
        assertArrayEquals(payload, candidate.payloadFile.readBytes())
    }

    @Test
    fun receiverStreamReportsOnlyDurableCumulativeBytesAndFinalReceiptProgress() {
        val payload = "durable progress fixture".toByteArray()
        val transferId = UUID.randomUUID()
        val input = ByteArrayOutputStream().apply {
            write(ProtocolCodec.encodeManifestFrame(manifest(transferId, payload)))
            write(chunk(transferId, 0, 1, payload))
            write(ProtocolCodec.encodeFinishRequest(transferId))
        }.toByteArray()
        val reports = mutableListOf<DurableTransferProgress>()
        val active = newReceiver()

        val output = ByteArrayOutputStream()
        ReceiverStreamHandler(active) { reports += it }.serve(
            ByteArrayInputStream(input),
            output,
            transferId,
        )

        val response = ByteArrayInputStream(output.toByteArray())
        assertEquals(0, ProtocolCodec.readAcknowledgement(response, AckKind.MANIFEST).status)
        assertEquals(0, ProtocolCodec.readAcknowledgement(response, AckKind.CHUNK).status)
        val receipt = ProtocolCodec.readReceiptResponse(response)
        assertEquals(transferId, receipt.transferId)
        assertEquals(0, receipt.status)
        assertTrue(receipt.payload.isNotEmpty())
        assertTrue("Initial and final durable progress callbacks must be reported", reports.size >= 2)
        assertEquals(0L, reports.first().receivedBytes)
        assertEquals(payload.size.toLong(), reports.last().receivedBytes)
        assertTrue(reports.zipWithNext().all { (previous, next) -> next.receivedBytes >= previous.receivedBytes })
        assertTrue(reports.all { it.transferId == transferId.toString() && it.totalBytes == payload.size.toLong() })
        assertEquals(0, reports.first().percent)
        assertEquals(100, reports.last().percent)
    }

    @Test
    fun durableProgressUsesCheckpointOffsetRatherThanRetriedChunkCount() {
        val payload = "abcdefghij".toByteArray()
        val transferId = UUID.randomUUID()
        val firstHalf = payload.copyOfRange(0, 5)
        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)

        val before = active.durableProgress(transferId)!!
        assertEquals(0L, before.receivedBytes)
        assertEquals(10L, before.totalBytes)
        assertEquals(0, before.percent)

        val firstChunk = chunk(transferId, 0, 2, firstHalf)
        assertEquals(ACK_OK, active.acceptChunk(firstChunk).status)
        assertEquals(ACK_OK, active.acceptChunk(firstChunk).status) // Exact retry must not add bytes.
        val partial = active.durableProgress(transferId)!!
        assertEquals(5L, partial.receivedBytes)
        assertEquals(50, partial.percent)

        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 1, 2, payload.copyOfRange(5, 10))).status)
        val complete = active.durableProgress(transferId)!!
        assertEquals(10L, complete.receivedBytes)
        assertEquals(100, complete.percent)
    }

    @Test
    fun postVerifiedRestoreDeletesOnlyItsAppPrivatePayloadAndHidesItFromRetryList() {
        val payload = "hello".toByteArray()
        val transferId = UUID.randomUUID()
        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)
        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 0, 1, payload)).status)
        active.buildFinalReceipt(transferId)
        val candidate = active.verifiedRestoreItems(transferId.toString()).single()
        assertTrue(candidate.payloadFile.isFile)

        assertTrue(active.recordPostVerifiedRestore(candidate))

        assertFalse(candidate.payloadFile.exists())
        assertTrue(active.verifiedRestoreItems(transferId.toString()).isEmpty())
        val summary = active.availableRestoreTransfers().single()
        assertEquals(1, summary.restoredItemCount)
        assertEquals(0, summary.restorableItemCount)
    }

    @Test
    fun abandonedTransferRequiresExactCurrentByteConfirmationAndRemovesOnlyItsStaging() {
        val payload = "abcdefghij".toByteArray()
        val transferId = UUID.randomUUID()
        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)
        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 0, 2, payload.copyOfRange(0, 5))).status)
        val expectedBytes = active.pendingTransferCleanupBytes(transferId.toString())
        assertEquals(5L, expectedBytes)
        val transferDirectory = File(context.filesDir, "transferkit-receiver/${transferId}")
        assertTrue(transferDirectory.isDirectory)

        assertNull(active.approveAbandonAfterUserConfirmation(transferId.toString(), expectedBytes!!, confirmedByUser = false))
        assertNull(active.approveAbandonAfterUserConfirmation(transferId.toString(), expectedBytes + 1, confirmedByUser = true))
        assertTrue(transferDirectory.isDirectory)

        val approval = active.approveAbandonAfterUserConfirmation(
            transferId.toString(),
            expectedBytes,
            confirmedByUser = true,
        )
        assertNotNull(approval)
        assertFalse(active.abandonTransfer(transferId.toString(), null))
        assertTrue(active.abandonTransfer(transferId.toString(), approval))
        assertFalse(transferDirectory.exists())
        assertEquals(ACK_INVALID_MANIFEST, active.acceptManifest(manifest(transferId, payload)).status)
    }

    @Test
    fun versionOneReceiverDatabaseMigratesRestoreStatusWithoutLosingTransferRows() {
        val payload = "hello".toByteArray()
        val transferId = UUID.randomUUID()
        val manifest = manifest(transferId, payload)
        val now = Instant.now().toString()
        val databaseFile = context.getDatabasePath("transferkit-receiver.db")
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { legacy ->
            legacy.execSQL(
                """CREATE TABLE received_transfers (
                    transfer_id TEXT PRIMARY KEY NOT NULL, manifest_sha256 TEXT NOT NULL,
                    manifest_bytes BLOB NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL, receipt_bytes BLOB
                )""".trimIndent(),
            )
            legacy.execSQL(
                """CREATE TABLE received_items (
                    transfer_id TEXT NOT NULL, category_id TEXT NOT NULL, item_id TEXT NOT NULL,
                    declared_size INTEGER NOT NULL, expected_sha256 TEXT NOT NULL, status TEXT NOT NULL,
                    next_expected_chunk INTEGER NOT NULL DEFAULT 0, next_byte_offset INTEGER NOT NULL DEFAULT 0,
                    total_chunks INTEGER, nominal_chunk_size INTEGER, failure_stage TEXT, failure_reason TEXT,
                    PRIMARY KEY (transfer_id, category_id, item_id),
                    FOREIGN KEY (transfer_id) REFERENCES received_transfers(transfer_id) ON DELETE CASCADE
                )""".trimIndent(),
            )
            legacy.execSQL(
                """CREATE TABLE received_chunks (
                    transfer_id TEXT NOT NULL, category_id TEXT NOT NULL, item_id TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL, byte_offset INTEGER NOT NULL, chunk_size INTEGER NOT NULL,
                    crc32 INTEGER NOT NULL, payload_sha256 TEXT NOT NULL,
                    PRIMARY KEY (transfer_id, category_id, item_id, chunk_index),
                    FOREIGN KEY (transfer_id, category_id, item_id)
                        REFERENCES received_items(transfer_id, category_id, item_id) ON DELETE CASCADE
                )""".trimIndent(),
            )
            legacy.execSQL(
                "INSERT INTO received_transfers VALUES (?, ?, ?, 'RUNNING', ?, ?, NULL)",
                arrayOf<Any?>(transferId.toString(), sha256(manifest), manifest, now, now),
            )
            legacy.execSQL(
                "INSERT INTO received_items VALUES (?, ?, ?, ?, ?, 'PENDING', 0, 0, NULL, NULL, NULL, NULL)",
                arrayOf<Any?>(transferId.toString(), CATEGORY_ID, ITEM_ID, payload.size, sha256(payload)),
            )
            legacy.version = 1
        }

        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest).status)
        val migrated = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
        migrated.use { db ->
            val itemColumns = buildSet {
                db.rawQuery("PRAGMA table_info(received_items)", null).use { rows ->
                    while (rows.moveToNext()) add(rows.getString(1))
                }
            }
            assertTrue(itemColumns.contains("restore_status"))
            db.rawQuery("SELECT COUNT(*) FROM received_transfers", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(1, rows.getInt(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM item_checkpoints", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(1, rows.getInt(0))
            }
        }
    }

    @Test
    fun recoverySchemaPersistsStagingLocatorCheckpointAndThirtyDayRetention() {
        val payload = "abcdefghij".toByteArray()
        val transferId = UUID.randomUUID()
        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)
        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 0, 2, payload.copyOfRange(0, 5))).status)

        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath("transferkit-receiver.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        database.use { db ->
            val transferColumns = buildSet {
                db.rawQuery("PRAGMA table_info(received_transfers)", null).use { rows ->
                    while (rows.moveToNext()) add(rows.getString(1))
                }
            }
            assertTrue(transferColumns.containsAll(setOf(
                "source_fingerprint", "target_fingerprint", "protocol_version", "app_version", "staging_dir",
                "last_checkpoint_at", "expires_at",
            )))
            db.rawQuery(
                "SELECT next_expected_chunk, next_byte_offset, total_chunks, staging_path, last_checkpoint_at " +
                    "FROM item_checkpoints WHERE transfer_id = ? AND category_id = ? AND item_id = ?",
                arrayOf(transferId.toString(), CATEGORY_ID, ITEM_ID),
            ).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(1L, row.getLong(0))
                assertEquals(5L, row.getLong(1))
                assertEquals(2L, row.getLong(2))
                assertEquals(".tmp/items/${sha256("$CATEGORY_ID\u0000$ITEM_ID".toByteArray())}.part", row.getString(3))
                val checkpointAt = Instant.parse(row.getString(4))
                db.rawQuery(
                    "SELECT last_checkpoint_at, expires_at, protocol_version, staging_dir " +
                        "FROM received_transfers WHERE transfer_id = ?",
                    arrayOf(transferId.toString()),
                ).use { transfer ->
                    assertTrue(transfer.moveToFirst())
                    assertEquals(row.getString(4), transfer.getString(0))
                    assertEquals(checkpointAt.plus(Duration.ofDays(30)), Instant.parse(transfer.getString(1)))
                    assertEquals("1", transfer.getString(2))
                    assertEquals(transferId.toString(), transfer.getString(3))
                }
            }
            val indexes = buildSet {
                db.rawQuery("PRAGMA index_list(received_transfers)", null).use { rows ->
                    while (rows.moveToNext()) add(rows.getString(1))
                }
            }
            assertTrue(indexes.contains("idx_received_transfers_recovery"))
            assertTrue(indexes.contains("idx_received_transfers_expiry"))
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL("UPDATE received_transfers SET status = 'INVALID' WHERE transfer_id = ?", arrayOf(transferId.toString()))
            }
        }
    }

    @Test
    fun durableChunkLedgerStoresExactOffsetSizeCrcAndDigest() {
        val payload = "abcdefghij".toByteArray()
        val chunkPayload = payload.copyOfRange(0, 5)
        val transferId = UUID.randomUUID()
        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)
        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 0, 2, chunkPayload)).status)
        val expectedCrc = CRC32().apply { update(chunkPayload) }.value

        SQLiteDatabase.openDatabase(
            context.getDatabasePath("transferkit-receiver.db").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery(
                "SELECT chunk_index, byte_offset, chunk_size, crc32, payload_sha256 " +
                    "FROM received_chunks WHERE transfer_id = ? AND category_id = ? AND item_id = ?",
                arrayOf(transferId.toString(), CATEGORY_ID, ITEM_ID),
            ).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0L, row.getLong(0))
                assertEquals(0L, row.getLong(1))
                assertEquals(5, row.getInt(2))
                assertEquals(expectedCrc, row.getLong(3))
                assertEquals(sha256(chunkPayload), row.getString(4))
                assertFalse(row.moveToNext())
            }
        }
    }

    @Test
    fun sqliteCommitFailureReturnsRejectedAckAndDoesNotAdvanceDurableCheckpoint() {
        val payload = "abcdefghij".toByteArray()
        val chunkPayload = payload.copyOfRange(0, 5)
        val transferId = UUID.randomUUID()
        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("transferkit-receiver.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.execSQL(
                """CREATE TRIGGER reject_test_chunk BEFORE INSERT ON received_chunks
                   BEGIN SELECT RAISE(ABORT, 'injected checkpoint failure'); END""".trimIndent(),
            )
        }

        val acknowledgement = active.acceptChunk(chunk(transferId, 0, 2, chunkPayload))
        assertEquals(ACK_TARGET_WRITE_FAILED, acknowledgement.status)
        val staging = File(
            context.filesDir,
            "transferkit-receiver/$transferId/.tmp/items/${sha256("$CATEGORY_ID\u0000$ITEM_ID".toByteArray())}.part",
        )
        assertTrue(staging.isFile)
        assertEquals(0L, staging.length())
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("transferkit-receiver.db").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery(
                "SELECT next_expected_chunk, next_byte_offset FROM item_checkpoints WHERE transfer_id = ?",
                arrayOf(transferId.toString()),
            ).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0L, row.getLong(0))
                assertEquals(0L, row.getLong(1))
            }
            db.rawQuery("SELECT COUNT(*) FROM received_chunks WHERE transfer_id = ?", arrayOf(transferId.toString())).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
        }
    }

    @Test
    fun versionTwoMigrationCarriesForwardDurablePartialChunkCheckpoint() {
        val payload = "abcdefghij".toByteArray()
        val transferId = UUID.randomUUID()
        val manifest = manifest(transferId, payload)
        val now = Instant.now().toString()
        val databaseFile = context.getDatabasePath("transferkit-receiver.db")
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { legacy ->
            legacy.execSQL(
                """CREATE TABLE received_transfers (
                    transfer_id TEXT PRIMARY KEY NOT NULL, manifest_sha256 TEXT NOT NULL,
                    manifest_bytes BLOB NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL, receipt_bytes BLOB
                )""".trimIndent(),
            )
            legacy.execSQL(
                """CREATE TABLE received_items (
                    transfer_id TEXT NOT NULL, category_id TEXT NOT NULL, item_id TEXT NOT NULL,
                    declared_size INTEGER NOT NULL, expected_sha256 TEXT NOT NULL, status TEXT NOT NULL,
                    restore_status TEXT NOT NULL DEFAULT 'PENDING', next_expected_chunk INTEGER NOT NULL DEFAULT 0,
                    next_byte_offset INTEGER NOT NULL DEFAULT 0, total_chunks INTEGER, nominal_chunk_size INTEGER,
                    failure_stage TEXT, failure_reason TEXT, PRIMARY KEY (transfer_id, category_id, item_id),
                    FOREIGN KEY (transfer_id) REFERENCES received_transfers(transfer_id) ON DELETE CASCADE
                )""".trimIndent(),
            )
            legacy.execSQL(
                """CREATE TABLE received_chunks (
                    transfer_id TEXT NOT NULL, category_id TEXT NOT NULL, item_id TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL, byte_offset INTEGER NOT NULL, chunk_size INTEGER NOT NULL,
                    crc32 INTEGER NOT NULL, payload_sha256 TEXT NOT NULL,
                    PRIMARY KEY (transfer_id, category_id, item_id, chunk_index),
                    FOREIGN KEY (transfer_id, category_id, item_id)
                        REFERENCES received_items(transfer_id, category_id, item_id) ON DELETE CASCADE
                )""".trimIndent(),
            )
            legacy.execSQL(
                "INSERT INTO received_transfers VALUES (?, ?, ?, 'RUNNING', ?, ?, NULL)",
                arrayOf(transferId.toString(), sha256(manifest), manifest, now, now),
            )
            legacy.execSQL(
                "INSERT INTO received_items VALUES (?, ?, ?, ?, ?, 'RECEIVING', 'PENDING', 1, 5, 2, 5, NULL, NULL)",
                arrayOf(transferId.toString(), CATEGORY_ID, ITEM_ID, payload.size, sha256(payload)),
            )
            val firstChunk = payload.copyOfRange(0, 5)
            val crc = java.util.zip.CRC32().apply { update(firstChunk) }.value
            legacy.execSQL(
                "INSERT INTO received_chunks VALUES (?, ?, ?, 0, 0, 5, ?, ?)",
                arrayOf(transferId.toString(), CATEGORY_ID, ITEM_ID, crc, sha256(firstChunk)),
            )
            legacy.version = 2
        }
        val stagingFile = File(
            context.filesDir,
            "transferkit-receiver/$transferId/.tmp/items/${sha256("$CATEGORY_ID\u0000$ITEM_ID".toByteArray())}.part",
        )
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeBytes(payload.copyOfRange(0, 5))

        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest).status)
        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 1, 2, payload.copyOfRange(5, 10))).status)
        assertArrayEquals(payload, active.verifiedItemFile(transferId, CATEGORY_ID, ITEM_ID)!!.readBytes())
        val migrated = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
        migrated.use { db ->
            db.rawQuery(
                "SELECT next_expected_chunk, next_byte_offset FROM item_checkpoints WHERE transfer_id = ?",
                arrayOf(transferId.toString()),
            ).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(2L, row.getLong(0))
                assertEquals(10L, row.getLong(1))
            }
        }
    }

    @Test
    fun crcMismatchIsRejectedWithoutAdvancingCheckpoint() {
        val payload = "hello".toByteArray()
        val transferId = UUID.randomUUID()
        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)

        val corrupt = chunk(transferId, 0, 1, payload).copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(ACK_CRC32_MISMATCH, active.acceptChunk(corrupt).status)
        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 0, 1, payload)).status)
        assertNotNull(active.verifiedItemFile(transferId, CATEGORY_ID, ITEM_ID))
    }

    @Test
    fun identicalDuplicateChunkIsAcknowledgedAndDifferentDuplicateIsRejected() {
        val payload = "abcdefghij".toByteArray()
        val transferId = UUID.randomUUID()
        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)
        val first = chunk(transferId, 0, 2, payload.copyOfRange(0, 5))

        assertEquals(ACK_OK, active.acceptChunk(first).status)
        assertEquals(ACK_OK, active.acceptChunk(first).status)
        assertEquals(ACK_REJECTED, active.acceptChunk(chunk(transferId, 0, 2, "12345".toByteArray())).status)
        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 1, 2, payload.copyOfRange(5, 10))).status)
        assertArrayEquals(payload, active.verifiedItemFile(transferId, CATEGORY_ID, ITEM_ID)!!.readBytes())
    }

    @Test
    fun shaMismatchIsRecordedAsFailedIntegrityInReceipt() {
        val expected = "good".toByteArray()
        val transferId = UUID.randomUUID()
        val active = newReceiver()
        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, expected)).status)
        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 0, 1, "evil".toByteArray())).status)
        assertNull(active.verifiedItemFile(transferId, CATEGORY_ID, ITEM_ID))

        val receipt = Json.parseToJsonElement(active.buildFinalReceipt(transferId).decodeToString()).jsonObject
        val category = receipt.getValue("categories").jsonObject.getValue(CATEGORY_ID).jsonObject
        assertEquals(1, category.getValue("items_failed").jsonPrimitive.int)
        assertEquals("SHA256_MISMATCH", category.getValue("failures").jsonArray[0].jsonObject
            .getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun invalidManifestIsRejectedAndOutOfOrderChunkDoesNotWrite() {
        val payload = "abcdefghij".toByteArray()
        val transferId = UUID.randomUUID()
        val active = newReceiver()
        val invalid = manifest(transferId, payload).decodeToString().replace(
            "\"version\":\"1.0\"",
            "\"version\":\"1.0\",\"unexpected\":true",
        ).toByteArray()
        assertEquals(ACK_INVALID_MANIFEST, active.acceptManifest(invalid).status)

        assertEquals(ACK_OK, active.acceptManifest(manifest(transferId, payload)).status)
        assertEquals(ACK_REJECTED, active.acceptChunk(chunk(transferId, 1, 2, payload.copyOfRange(5, 10))).status)
        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 0, 2, payload.copyOfRange(0, 5))).status)
        assertEquals(ACK_OK, active.acceptChunk(chunk(transferId, 1, 2, payload.copyOfRange(5, 10))).status)
    }

    @Test
    fun checkpointAndVerifiedItemSurviveReceiverRecreation() {
        val payload = "abcdefghij".toByteArray()
        val transferId = UUID.randomUUID()
        val firstReceiver = newReceiver()
        assertEquals(ACK_OK, firstReceiver.acceptManifest(manifest(transferId, payload)).status)
        assertEquals(ACK_OK, firstReceiver.acceptChunk(chunk(transferId, 0, 2, payload.copyOfRange(0, 5))).status)
        firstReceiver.close()
        receiver = null

        val resumedReceiver = newReceiver()
        assertEquals(ACK_OK, resumedReceiver.acceptManifest(manifest(transferId, payload)).status)
        assertEquals(ACK_OK, resumedReceiver.acceptChunk(chunk(transferId, 1, 2, payload.copyOfRange(5, 10))).status)
        assertArrayEquals(payload, resumedReceiver.verifiedItemFile(transferId, CATEGORY_ID, ITEM_ID)!!.readBytes())
    }

    private fun newReceiver(): AndroidTransferReceiver = AndroidTransferReceiver(context).also { receiver = it }

    private fun manifest(transferId: UUID, payload: ByteArray): ByteArray {
        val sha256 = sha256(payload)
        return """{"version":"1.0","transfer_id":"$transferId","created_at":"2026-11-12T00:00:00Z","source_device":{"manufacturer":"Fixture","model":"Fixture","sdk":33,"fingerprint":"fixture"},"categories":{"$CATEGORY_ID":{"provider":"test.provider","method":"fixture","items":[{"id":"$ITEM_ID","size":${payload.size},"sha256":"$sha256","metadata":{}}]}}}""".toByteArray()
    }

    private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun chunk(transferId: UUID, index: Long, total: Long, payload: ByteArray): ByteArray =
        com.transferkit.client.protocol.ProtocolCodec.encodeChunk(
            transferId,
            CATEGORY_ID,
            ITEM_ID,
            index,
            total,
            payload,
        )

    private fun cleanupReceiverState() {
        File(context.filesDir, "transferkit-receiver").deleteRecursively()
        context.deleteDatabase("transferkit-receiver.db")
    }

    private companion object {
        const val CATEGORY_ID = "photos"
        const val ITEM_ID = "fixture_item"
        const val ACK_OK = 0
        const val ACK_REJECTED = 1
        const val ACK_CRC32_MISMATCH = 2
        const val ACK_INVALID_MANIFEST = 3
        const val ACK_TARGET_WRITE_FAILED = 4
    }
}
