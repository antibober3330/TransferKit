package com.transferkit.client

import com.transferkit.client.receiver.DurableTransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProgressSnapshotTest {
    @Test
    fun noCheckpointDoesNotInventProgress() {
        val snapshot = TransferProgressSnapshot("LISTENING", "STOPPED", null)

        assertNull(snapshot.progressFraction)
        assertNull(snapshot.receivedBytesText())
        assertTrue(snapshot.statusText().contains("очікує"))
    }

    @Test
    fun checkpointProgressUsesOnlyBoundedDurableByteCounts() {
        val snapshot = TransferProgressSnapshot(
            receiverStatus = "LISTENING",
            sourceStatus = "STOPPED",
            durableProgress = DurableTransferProgress("opaque-id", 150, 100),
        )

        assertEquals(1f, snapshot.progressFraction!!, 0f)
        assertTrue(snapshot.receivedBytesText()!!.startsWith("Отримано"))
    }

    @Test
    fun zeroByteManifestDoesNotShowAProgressBarOrMisleadingByteRatio() {
        val snapshot = TransferProgressSnapshot(
            receiverStatus = "LISTENING",
            sourceStatus = "STOPPED",
            durableProgress = DurableTransferProgress("opaque-id", 0, 0),
        )

        assertNull(snapshot.progressFraction)
        assertNull(snapshot.receivedBytesText())
        assertTrue(snapshot.statusText().contains("нульового розміру"))
    }

    @Test
    fun fullReceiveIsExplicitlyNotDescribedAsSuccessfulRestore() {
        val snapshot = TransferProgressSnapshot(
            receiverStatus = "LISTENING",
            sourceStatus = "STOPPED",
            durableProgress = DurableTransferProgress("opaque-id", 100, 100),
        )

        assertEquals(
            "Прийом завершено. Це ще не означає, що дані відновлено на пристрої.",
            snapshot.statusText(),
        )
    }
}
