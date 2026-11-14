package com.transferkit.client.restore

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreRestorerTest {
    private val fixture = Fixture()

    @After
    fun cleanFixture() {
        fixture.root.deleteRecursively()
    }

    @Test
    fun writesPendingRowVerifiesBeforeAndAfterPublish() {
        val item = fixture.item()
        val environment = FakeEnvironment()
        val gate = RestoreApprovalGate()
        val restorer = MediaStoreRestorer(environment, gate)

        val result = restorer.restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.RESTORED, result.status)
        val row = environment.rows.values.single()
        assertArrayEquals(fixture.bytes, row.bytes)
        assertFalse(row.pending)
        assertEquals(1, environment.insertCount)
        assertEquals(1, environment.publishCount)
        assertEquals(0, environment.deleteCount)
        assertEquals(2, environment.readCount)
        assertEquals(listOf("find", "insert", "write", "read", "publish", "read"), environment.operations)
    }

    @Test
    fun verifiedExistingMarkerIsAnIdempotentRetryWithoutAnotherInsert() {
        val item = fixture.item()
        val environment = FakeEnvironment()
        environment.addExisting(item, fixture.bytes, pending = false)
        val gate = RestoreApprovalGate()
        val restorer = MediaStoreRestorer(environment, gate)

        val result = restorer.restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.RESTORED, result.status)
        assertEquals(0, environment.insertCount)
        assertEquals(0, environment.publishCount)
        assertEquals(0, environment.deleteCount)
        assertEquals(1, environment.readCount)
    }

    @Test
    fun verifiedPendingMarkerIsPublishedOnlyAfterReadback() {
        val item = fixture.item()
        val environment = FakeEnvironment()
        environment.addExisting(item, fixture.bytes, pending = true)
        val gate = RestoreApprovalGate()
        val restorer = MediaStoreRestorer(environment, gate)

        val result = restorer.restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.RESTORED, result.status)
        assertEquals(listOf("find", "read", "publish", "read"), environment.operations)
        assertFalse(environment.rows.values.single().pending)
    }

    @Test
    fun corruptedExistingMarkerFailsIntegrityWithoutChangingOrDeletingIt() {
        val item = fixture.item()
        val corrupted = fixture.bytes.copyOf().apply { this[0] = (this[0].toInt() xor 0xff).toByte() }
        val environment = FakeEnvironment()
        environment.addExisting(item, corrupted, pending = false)
        val gate = RestoreApprovalGate()
        val restorer = MediaStoreRestorer(environment, gate)

        val result = restorer.restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.FAILED_INTEGRITY, result.status)
        assertEquals("SHA256_MISMATCH", result.errorReason)
        assertArrayEquals(corrupted, environment.rows.values.single().bytes)
        assertEquals(0, environment.deleteCount)
        assertEquals(0, environment.insertCount)
    }

    @Test
    fun sameSizeSourceMutationIsRejectedAndOnlyNewPendingRowIsRemoved() {
        val item = fixture.item()
        val changed = fixture.bytes.copyOf().apply { this[0] = (this[0].toInt() xor 0xff).toByte() }
        item.payloadFile.writeBytes(changed)
        val environment = FakeEnvironment()
        val gate = RestoreApprovalGate()
        val restorer = MediaStoreRestorer(environment, gate)

        val result = restorer.restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.FAILED_INTEGRITY, result.status)
        assertEquals("SHA256_MISMATCH", result.errorReason)
        assertEquals(1, environment.insertCount)
        assertEquals(1, environment.deleteCount)
        assertTrue(environment.rows.isEmpty())
        assertEquals(0, environment.publishCount)
    }

    @Test
    fun destinationReadbackMismatchBeforePublishFailsIntegrityAndRemovesPrivateRow() {
        val item = fixture.item()
        val environment = FakeEnvironment(corruptPendingReadback = true)
        val gate = RestoreApprovalGate()
        val restorer = MediaStoreRestorer(environment, gate)

        val result = restorer.restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.FAILED_INTEGRITY, result.status)
        assertTrue(environment.rows.isEmpty())
        assertEquals(0, environment.publishCount)
        assertEquals(1, environment.deleteCount)
    }

    @Test
    fun postPublishReadbackMismatchFailsIntegrityAndRemovesNewRow() {
        val item = fixture.item()
        val environment = FakeEnvironment(corruptPublishedReadback = true)
        val gate = RestoreApprovalGate()
        val restorer = MediaStoreRestorer(environment, gate)

        val result = restorer.restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.FAILED_INTEGRITY, result.status)
        assertEquals(1, environment.publishCount)
        assertEquals(1, environment.deleteCount)
        assertTrue(environment.rows.isEmpty())
    }

    @Test
    fun failedPublishIsReportedAndPendingRowIsRemoved() {
        val item = fixture.item()
        val environment = FakeEnvironment(publishSucceeds = false)
        val gate = RestoreApprovalGate()
        val restorer = MediaStoreRestorer(environment, gate)

        val result = restorer.restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.FAILED, result.status)
        assertEquals("TARGET_WRITE_FAILED", result.errorReason)
        assertEquals(1, environment.deleteCount)
        assertTrue(environment.rows.isEmpty())
    }

    @Test
    fun missingApprovalDoesNotTouchMediaStore() {
        val item = fixture.item()
        val environment = FakeEnvironment()
        val result = MediaStoreRestorer(environment, RestoreApprovalGate()).restore(item, null)

        assertEquals(RestoreItemStatus.USER_ACTION_REQUIRED, result.status)
        assertEquals("RESTORE_CONFIRMATION_REQUIRED", result.errorReason)
        assertTrue(environment.operations.isEmpty())
    }

    @Test
    fun unsupportedApiAndMimeCategoryAreRejectedBeforeDestinationMutation() {
        val item = fixture.item()
        val gate = RestoreApprovalGate()
        val legacyEnvironment = FakeEnvironment(sdkInt = 28)
        val legacyResult = MediaStoreRestorer(legacyEnvironment, gate)
            .restore(item, fixture.approval(gate, item))
        assertEquals("API_UNAVAILABLE", legacyResult.errorReason)
        assertTrue(legacyEnvironment.operations.isEmpty())

        val wrongMimeItem = fixture.item(mimeType = "video/mp4")
        val modernEnvironment = FakeEnvironment()
        val mimeGate = RestoreApprovalGate()
        val mimeResult = MediaStoreRestorer(modernEnvironment, mimeGate)
            .restore(wrongMimeItem, fixture.approval(mimeGate, wrongMimeItem))
        assertEquals("INVALID_MANIFEST", mimeResult.errorReason)
        assertTrue(modernEnvironment.operations.isEmpty())
    }

    @Test
    fun zeroByteItemUsesValidEmptyDigestAndPublishes() {
        val item = fixture.item(bytes = byteArrayOf())
        val environment = FakeEnvironment()
        val gate = RestoreApprovalGate()
        val result = MediaStoreRestorer(environment, gate)
            .restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.RESTORED, result.status)
        assertNotNull(environment.rows.values.single())
        assertArrayEquals(byteArrayOf(), environment.rows.values.single().bytes)
    }

    @Test
    fun existingPendingMarkerWithBadBytesIsNotPublishedOrDeleted() {
        val item = fixture.item()
        val corrupted = fixture.bytes.copyOf().apply { this[1] = (this[1].toInt() xor 0xff).toByte() }
        val environment = FakeEnvironment()
        environment.addExisting(item, corrupted, pending = true)
        val gate = RestoreApprovalGate()

        val result = MediaStoreRestorer(environment, gate)
            .restore(item, fixture.approval(gate, item))

        assertEquals(RestoreItemStatus.FAILED_INTEGRITY, result.status)
        assertTrue(environment.rows.values.single().pending)
        assertEquals(0, environment.publishCount)
        assertEquals(0, environment.deleteCount)
    }

    private class Fixture {
        val root: File = Files.createTempDirectory("transferkit-media-store-restorer").toFile()
        val bytes = "synthetic-media-payload".toByteArray()
        private var nextId = 0

        fun item(
            bytes: ByteArray = this.bytes,
            mimeType: String = "image/jpeg",
        ): VerifiedRestoreItem {
            val payload = File(root, "source-${nextId++}.bin").apply { writeBytes(bytes) }
            return VerifiedRestoreItem(
                transferId = "transfer-${nextId}",
                manifestSha256 = "a".repeat(64),
                categoryId = "photos",
                itemId = "photo-${nextId}",
                sizeBytes = bytes.size.toLong(),
                sha256 = bytes.sha256(),
                metadata = mapOf("path" to "Pictures/Source/photo.jpg", "mime_type" to mimeType),
                payloadFile = payload,
            )
        }

        fun approval(gate: RestoreApprovalGate, item: VerifiedRestoreItem): RestoreApproval? =
            gate.approveAfterUserConfirmation(
                item.transferId,
                item.manifestSha256,
                listOf(item),
                confirmedByUser = true,
            )
    }

    private data class FakeRow(var bytes: ByteArray, var pending: Boolean)

    private class FakeEnvironment(
        override val sdkInt: Int = 34,
        private val corruptPendingReadback: Boolean = false,
        private val corruptPublishedReadback: Boolean = false,
        private val publishSucceeds: Boolean = true,
    ) : MediaStoreEnvironment {
        val rows = linkedMapOf<String, FakeRow>()
        val operations = mutableListOf<String>()
        var insertCount = 0
            private set
        var publishCount = 0
            private set
        var deleteCount = 0
            private set
        var readCount = 0
            private set
        private var nextId = 0

        override fun collectionFor(categoryId: String): String? = when (categoryId) {
            "photos" -> "synthetic://images"
            "videos" -> "synthetic://videos"
            "audio" -> "synthetic://audio"
            "downloads" -> "synthetic://downloads"
            else -> null
        }

        override fun findExisting(destination: MediaStoreDestination): MediaStoreRow? {
            operations += "find"
            val matching = rows.entries.filter { (id, _) -> id == markerFor(destination) }
            check(matching.size <= 1) { "synthetic ambiguous TransferKit marker" }
            return matching.singleOrNull()?.let { (id, row) -> MediaStoreRow(id, row.pending) }
        }

        override fun insertPending(destination: MediaStoreDestination): MediaStoreRow {
            operations += "insert"
            insertCount++
            val id = markerFor(destination).ifEmpty { "row-${nextId++}" }
            rows[id] = FakeRow(byteArrayOf(), pending = true)
            return MediaStoreRow(id, isPending = true)
        }

        override fun openOutput(row: MediaStoreRow): OutputStream {
            operations += "write"
            val buffer = ByteArrayOutputStream()
            return object : OutputStream() {
                override fun write(value: Int) = buffer.write(value)
                override fun write(bytes: ByteArray, offset: Int, length: Int) = buffer.write(bytes, offset, length)
                override fun flush() = buffer.flush()
                override fun close() {
                    buffer.close()
                    rows.getValue(row.uri).bytes = buffer.toByteArray()
                }
            }
        }

        override fun openInput(row: MediaStoreRow): InputStream {
            operations += "read"
            readCount++
            val record = rows.getValue(row.uri)
            val shouldCorrupt = (record.pending && corruptPendingReadback) ||
                (!record.pending && corruptPublishedReadback)
            val bytes = record.bytes.copyOf()
            if (shouldCorrupt && bytes.isNotEmpty()) bytes[0] = (bytes[0].toInt() xor 0xff).toByte()
            return ByteArrayInputStream(bytes)
        }

        override fun publish(row: MediaStoreRow): Boolean {
            operations += "publish"
            publishCount++
            if (!publishSucceeds) return false
            rows.getValue(row.uri).pending = false
            return true
        }

        override fun delete(row: MediaStoreRow) {
            operations += "delete"
            deleteCount++
            rows.remove(row.uri)
        }

        fun addExisting(item: VerifiedRestoreItem, bytes: ByteArray, pending: Boolean) {
            val destination = MediaStoreDestination(
                categoryId = item.categoryId,
                collection = "synthetic://images",
                relativePath = "Pictures/TransferKit/",
                displayName = markerFor(item),
                mimeType = item.metadata["mime_type"] as String,
            )
            rows[destination.displayName] = FakeRow(bytes.copyOf(), pending)
        }

        private fun markerFor(destination: MediaStoreDestination): String = destination.displayName

        private fun markerFor(item: VerifiedRestoreItem): String {
            val marker = MessageDigest.getInstance("SHA-256")
                .digest("${item.transferId}\u0000${item.categoryId}\u0000${item.itemId}".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                .take(24)
            return "tk_${marker}_photo.jpg"
        }
    }

}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
