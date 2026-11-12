package com.transferkit.client.restore

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallApprovalGateTest {
    private var now = 1_000L
    private val gate = ApkInstallApprovalGate(monotonicMillis = { now }, ttlMillis = 100L)
    private val item = VerifiedRestoreItem(
        transferId = "transfer-1",
        manifestSha256 = "a".repeat(64),
        categoryId = "apks",
        itemId = "app-1",
        sizeBytes = 10L,
        sha256 = "b".repeat(64),
        metadata = mapOf("package_name" to "org.example.app", "version_code" to 1L),
        payloadFile = File("unused-fixture"),
    )

    @Test
    fun cancellationCreatesNoApproval() {
        assertNull(gate.approveAfterUserConfirmation(item, confirmedByUser = false))
        assertFalse(gate.consume(null, item))
    }

    @Test
    fun approvalIsBoundToExactApkAndCannotBeReplayed() {
        val approval = gate.approveAfterUserConfirmation(item, confirmedByUser = true)!!
        assertFalse(gate.consume(approval, item.copy(itemId = "other")))
        assertFalse(gate.consume(approval, item.copy(sha256 = "c".repeat(64))))
        assertTrue(gate.consume(approval, item))
        assertFalse(gate.consume(approval, item))
    }

    @Test
    fun approvalExpiresAndCannotBeUsedForAnotherTransfer() {
        val expired = gate.approveAfterUserConfirmation(item, confirmedByUser = true)!!
        now += 100L
        assertFalse(gate.consume(expired, item))
        now += 1L
        val approval = gate.approveAfterUserConfirmation(item, confirmedByUser = true)!!
        assertFalse(gate.consume(approval, item.copy(transferId = "different")))
        assertTrue(gate.consume(approval, item))
    }

    @Test
    fun rejectsNonApkCategory() {
        assertFalse(gate.consume(null, item.copy(categoryId = "photos")))
        try {
            gate.approveAfterUserConfirmation(item.copy(categoryId = "photos"), confirmedByUser = true)
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Non-APK items must not receive install approval")
    }
}
