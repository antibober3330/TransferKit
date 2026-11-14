package com.transferkit.client.transport

import com.transferkit.client.receiver.DurableTransferProgress
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class DurableProgressReporterTest {
    @Test
    fun checkpointReadFailureDoesNotEscapeAndNextReadCanRecover() {
        val transferId = UUID.randomUUID()
        var reads = 0
        val received = mutableListOf<DurableTransferProgress>()
        val reporter = DurableProgressReporter(
            read = {
                reads++
                if (reads == 1) error("synthetic database read failure")
                DurableTransferProgress(transferId.toString(), 5, 10)
            },
            callback = { received += it },
            elapsedRealtime = { 1_000L },
        )

        reporter.report(transferId)
        reporter.report(transferId)

        assertEquals(2, reads)
        assertEquals(listOf(DurableTransferProgress(transferId.toString(), 5, 10)), received)
    }

    @Test
    fun readAndCallbackFailuresAreIsolatedAndForcedFinalSnapshotBypassesThrottle() {
        val transferId = UUID.randomUUID()
        var now = 1_000L
        var reads = 0
        val reporter = DurableProgressReporter(
            read = {
                reads++
                DurableTransferProgress(transferId.toString(), reads.toLong(), 10)
            },
            callback = { error("synthetic UI callback failure") },
            elapsedRealtime = { now },
        )

        reporter.report(transferId)
        now += 100L
        reporter.report(transferId)
        reporter.report(transferId, force = true)

        assertEquals(2, reads)
    }

    @Test
    fun periodicReadsAreThrottledButForcedBoundaryReadsAreNot() {
        val transferId = UUID.randomUUID()
        var now = 10_000L
        var reads = 0
        val reporter = DurableProgressReporter(
            read = {
                reads++
                DurableTransferProgress(transferId.toString(), 0, 10)
            },
            callback = {},
            elapsedRealtime = { now },
        )

        reporter.report(transferId)
        now += 999L
        reporter.report(transferId)
        now += 1L
        reporter.report(transferId)
        reporter.report(transferId, force = true)

        assertEquals(3, reads)
    }
}
