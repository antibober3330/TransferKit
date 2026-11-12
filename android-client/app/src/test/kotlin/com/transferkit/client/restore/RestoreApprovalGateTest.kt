package com.transferkit.client.restore

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreApprovalGateTest {
    private var now = 1_000L
    private val gate = RestoreApprovalGate(monotonicMillis = { now }, ttlMillis = 100L)
    private val manifestHash = "a".repeat(64)
    private val item = item()

    @Test
    fun cancellationDoesNotIssueApproval() {
        assertNull(gate.approveAfterUserConfirmation(TRANSFER_ID, manifestHash, listOf(item), false))
        assertFalse(gate.consume(null, item))
    }

    @Test
    fun approvalIsBoundToTransferManifestAndExactItemBytes() {
        val approval = approve(item)
        assertFalse(gate.consume(approval, item.copy(transferId = "another-transfer")))
        assertFalse(gate.consume(approval, item.copy(manifestSha256 = "b".repeat(64))))
        assertFalse(gate.consume(approval, item.copy(sha256 = "c".repeat(64))))
        assertFalse(gate.consume(approval, item.copy(sizeBytes = item.sizeBytes + 1)))
        assertTrue(gate.consume(approval, item))
    }

    @Test
    fun approvalIsScopedToSelectionAndCannotBeReplayed() {
        val approval = approve(item)
        val otherItem = item.copy(itemId = "other-item")
        assertFalse(gate.consume(approval, otherItem))
        assertTrue(gate.consume(approval, item))
        assertFalse(gate.consume(approval, item))
    }

    @Test
    fun approvalExpiresUsingMonotonicClock() {
        val approval = approve(item)
        now += 100L
        assertFalse(gate.consume(approval, item))
    }

    @Test
    fun invalidMixedManifestSelectionIsRejected() {
        assertNull(gate.approveAfterUserConfirmation(TRANSFER_ID, manifestHash, emptyList(), true))
        try {
            gate.approveAfterUserConfirmation(
                TRANSFER_ID,
                manifestHash,
                listOf(item, item.copy(itemId = "other", manifestSha256 = "d".repeat(64))),
                true,
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("mixed-manifest selection must be rejected")
    }

    @Test
    fun revokedApprovalCannotBeConsumed() {
        val approval = approve(item)
        assertNotNull(approval)
        gate.revoke(approval)
        assertFalse(gate.consume(approval, item))
    }

    private fun approve(value: VerifiedRestoreItem): RestoreApproval =
        gate.approveAfterUserConfirmation(
            transferId = TRANSFER_ID,
            manifestSha256 = manifestHash,
            items = listOf(value),
            confirmedByUser = true,
        )!!

    private fun item() = VerifiedRestoreItem(
        transferId = TRANSFER_ID,
        manifestSha256 = manifestHash,
        categoryId = "photos",
        itemId = "photo-1",
        sizeBytes = 3L,
        sha256 = "b".repeat(64),
        metadata = emptyMap(),
        payloadFile = File("unused-fixture"),
    )

    private companion object {
        const val TRANSFER_ID = "transfer-1"
    }
}
