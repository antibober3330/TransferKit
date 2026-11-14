package com.transferkit.client.protocol

import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpacePreflightContractsTest {
    @Test
    fun validStreamingSpaceReportRoundTripsWithExactWireNames() {
        val report = streamingReport()
        report.validate()

        val encoded = Json.encodeToString(report)
        val decoded = Json.decodeFromString<SpaceReportContract>(encoded)
        decoded.validate()

        assertEquals(report, decoded)
        assert(encoded.contains("\"protocol_version\":\"2\""))
        assert(encoded.contains("\"strategy\":\"streaming\""))
    }

    @Test
    fun unknownCapacityOrProviderEstimateFailsClosed() {
        val unknownCapacity = streamingReport().copy(
            availableBytes = 2_000,
            allocatableBytes = null,
            safetyMarginBytes = null,
            safeAvailableBytes = null,
            batchPeakBytes = null,
            streamingPeakBytes = null,
            estimatesComplete = false,
            unknownItemIds = emptyList(),
            strategy = SpaceStrategy.INSUFFICIENT,
            decisionRequired = true,
            keptItemIds = emptyList(),
        )
        unknownCapacity.validate()

        val unknownItem = unknownCapacity.copy(
            allocatableBytes = 2_000,
            safetyMarginBytes = 100,
            safeAvailableBytes = 1_900,
            unknownItemIds = listOf("photo-1"),
        )
        unknownItem.validate()
    }

    @Test
    fun reportRejectsBudgetMismatchAndUnsafeStrategy() {
        assertThrows(IllegalArgumentException::class.java) {
            streamingReport().copy(safeAvailableBytes = 1_899).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            streamingReport().copy(streamingPeakBytes = 1_901).validate()
        }
    }

    @Test
    fun spaceDecisionRequiresExplicitApprovalForStreaming() {
        val decision = SpaceDecisionContract(
            version = "1.0",
            protocolVersion = "2",
            transferId = TEST_TRANSFER_ID,
            strategy = SpaceStrategy.STREAMING,
            userConfirmed = true,
            keptItemIds = listOf("photo-1"),
            skippedItemIds = emptyList(),
        )
        decision.validate()

        assertThrows(IllegalArgumentException::class.java) {
            decision.copy(userConfirmed = false).validate()
        }
    }

    @Test
    fun insufficientDecisionCannotAuthorizeAnyItems() {
        val decision = SpaceDecisionContract(
            version = "1.0",
            protocolVersion = "2",
            transferId = TEST_TRANSFER_ID,
            strategy = SpaceStrategy.INSUFFICIENT,
            userConfirmed = false,
            keptItemIds = emptyList(),
            skippedItemIds = emptyList(),
        )
        decision.validate()
        assertThrows(IllegalArgumentException::class.java) {
            decision.copy(keptItemIds = listOf("photo-1")).validate()
        }
    }

    private fun streamingReport() = SpaceReportContract(
        version = "1.0",
        protocolVersion = "2",
        transferId = TEST_TRANSFER_ID,
        availableBytes = 2_000,
        allocatableBytes = 2_000,
        safetyMarginBytes = 100,
        safeAvailableBytes = 1_900,
        stagingLeftoverBytes = 0,
        totalManifestBytes = 1_800,
        largestItemBytes = 200,
        batchPeakBytes = 3_600,
        streamingPeakBytes = 1_800,
        estimatesComplete = true,
        unknownItemIds = emptyList(),
        strategy = SpaceStrategy.STREAMING,
        decisionRequired = true,
        keptItemIds = listOf("photo-1"),
        skippedItemIds = emptyList(),
    )

    private companion object {
        val TEST_TRANSFER_ID: String = UUID.fromString("00000000-0000-4000-8000-000000000001").toString()
    }
}
