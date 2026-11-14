package com.transferkit.client.receiver

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StagingLeftoversScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun activeAndRestorePendingTreesAreProtectedAndCountedOnlyAsExistingStorageUse() {
        val root = temporaryFolder.newFolder("receiver")
        val running = UUID.randomUUID().toString()
        val paused = UUID.randomUUID().toString()
        val completed = UUID.randomUUID().toString()
        writeBytes(root, running, "items/a.part", 13)
        writeBytes(root, paused, "items/b.part", 17)
        writeBytes(root, completed, "final/c.bin", 23)

        val rows = StagingLeftoversScanner.scan(
            root,
            mapOf(
                running to ReceiverDatabase.TRANSFER_RUNNING,
                paused to ReceiverDatabase.TRANSFER_PAUSED,
                completed to ReceiverDatabase.TRANSFER_COMPLETED,
            ),
        ).associateBy { it.transferId }

        assertEquals(StagingLeftoverKind.ACTIVE_CHECKPOINT, rows.getValue(running).kind)
        assertEquals(StagingLeftoverKind.ACTIVE_CHECKPOINT, rows.getValue(paused).kind)
        assertEquals(StagingLeftoverKind.RESTORE_PENDING, rows.getValue(completed).kind)
        assertEquals(13L, rows.getValue(running).bytes)
        assertEquals(17L, rows.getValue(paused).bytes)
        assertEquals(23L, rows.getValue(completed).bytes)
        assertTrue(rows.values.all { it.alreadyIncludedInAvailableBytes })
        assertTrue(rows.values.none { it.cleanupEligible })
    }

    @Test
    fun abandonedAndOrphanedTreesAreCandidatesButUnknownDatabaseStatesAreProtected() {
        val root = temporaryFolder.newFolder("receiver")
        val abandoned = UUID.randomUUID().toString()
        val orphan = UUID.randomUUID().toString()
        val unknown = UUID.randomUUID().toString()
        writeBytes(root, abandoned, "payload.bin", 5)
        writeBytes(root, orphan, "payload.bin", 7)
        writeBytes(root, unknown, "payload.bin", 11)

        val rows = StagingLeftoversScanner.scan(
            root,
            mapOf(
                abandoned to ReceiverDatabase.TRANSFER_ABANDONED,
                unknown to ReceiverDatabase.TRANSFER_SUPERSEDED,
            ),
        ).associateBy { it.transferId }

        assertEquals(StagingLeftoverKind.CLEANUP_CANDIDATE, rows.getValue(abandoned).kind)
        assertEquals(StagingLeftoverKind.ORPHANED_CANDIDATE, rows.getValue(orphan).kind)
        assertTrue(rows.getValue(abandoned).cleanupEligible)
        assertTrue(rows.getValue(orphan).cleanupEligible)
        assertEquals(StagingLeftoverKind.PROTECTED_UNKNOWN, rows.getValue(unknown).kind)
        assertFalse(rows.getValue(unknown).cleanupEligible)
    }

    @Test
    fun scannerMeasuresNestedFilesExactlyAndIgnoresNonTransferEntries() {
        val root = temporaryFolder.newFolder("receiver")
        val transferId = UUID.randomUUID().toString()
        writeBytes(root, transferId, "one.bin", 19)
        writeBytes(root, transferId, "nested/two.bin", 29)
        File(root, "not-a-transfer").mkdirs()
        File(root, UUID.randomUUID().toString()).writeText("not a directory")

        val rows = StagingLeftoversScanner.scan(root, emptyMap())

        assertEquals(1, rows.size)
        assertEquals(48L, rows.single().bytes)
        assertEquals(StagingLeftoverKind.ORPHANED_CANDIDATE, rows.single().kind)
    }

    @Test
    fun symbolicLinkTreesAreUnmeasurableAndNeverCleanupCandidates() {
        val root = temporaryFolder.newFolder("receiver")
        val outside = temporaryFolder.newFolder("outside")
        File(outside, "keep.bin").writeBytes(ByteArray(31))
        val transferId = UUID.randomUUID().toString()
        val link = File(root, transferId)
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: Exception) {
            return // Some Windows configurations disable symlink creation for unprivileged test users.
        }

        val row = StagingLeftoversScanner.scan(root, emptyMap()).single()

        assertEquals(StagingLeftoverKind.UNSAFE, row.kind)
        assertEquals(null, row.bytes)
        assertFalse(row.cleanupEligible)
        assertTrue(File(outside, "keep.bin").exists())
    }

    @Test
    fun nonexistentReceiverRootHasNoLeftovers() {
        val root = File(temporaryFolder.root, "not-created")
        assertTrue(StagingLeftoversScanner.scan(root, emptyMap()).isEmpty())
    }

    private fun writeBytes(root: File, transferId: String, relativePath: String, size: Int) {
        val target = File(File(root, transferId), relativePath)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(size))
    }
}
