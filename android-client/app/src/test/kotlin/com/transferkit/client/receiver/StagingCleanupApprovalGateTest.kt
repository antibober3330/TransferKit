package com.transferkit.client.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingCleanupApprovalGateTest {
    private var now = 100L
    private val gate = StagingCleanupApprovalGate(monotonicMillis = { now }, ttlMillis = 50L)

    @Test
    fun cancellationDoesNotAuthorizeDeletion() {
        assertNull(gate.approveAfterUserConfirmation("transfer-1", 42L, confirmedByUser = false))
    }

    @Test
    fun approvalIsSingleUseAndBoundToTransferAndExactCurrentBytes() {
        val approval = gate.approveAfterUserConfirmation("transfer-1", 42L, confirmedByUser = true)!!
        assertFalse(gate.consume(approval, "other-transfer", 42L))
        val changed = gate.approveAfterUserConfirmation("transfer-1", 42L, confirmedByUser = true)!!
        assertFalse(gate.consume(changed, "transfer-1", 41L))
        val retry = gate.approveAfterUserConfirmation("transfer-1", 42L, confirmedByUser = true)!!
        assertTrue(gate.consume(retry, "transfer-1", 42L))
        assertFalse(gate.consume(retry, "transfer-1", 42L))
    }

    @Test
    fun approvalExpires() {
        val approval = gate.approveAfterUserConfirmation("transfer-1", 0L, confirmedByUser = true)!!
        now += 50L
        assertFalse(gate.consume(approval, "transfer-1", 0L))
    }
}
