package com.transferkit.client.restore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RestorerRegistryTest {
    @Test
    fun mediaFileCategoriesAreEligibleForApiSpecificAdaptersAndApiRecordsAreNot() {
        assertEquals(true, isFileMediaRestoreCategory("photos"))
        assertEquals(true, isFileMediaRestoreCategory("videos"))
        assertEquals(true, isFileMediaRestoreCategory("audio"))
        assertEquals(true, isFileMediaRestoreCategory("downloads"))
        assertEquals(false, isFileMediaRestoreCategory("contacts"))
        assertEquals(false, isFileMediaRestoreCategory("calendar"))
    }

    @Test
    fun dispatchesOnlyToOwningAdapterAndPreservesApproval() {
        val adapter = FakeRestorer(setOf("photos"), RestoreItemStatus.RESTORED)
        val registry = RestorerRegistry(listOf(adapter))
        val item = item("photos")
        val approvalGate = RestoreApprovalGate()
        val approval = approvalGate.approveAfterUserConfirmation(
            item.transferId,
            item.manifestSha256,
            listOf(item),
            confirmedByUser = true,
        )

        val result = registry.restore(item, approval)

        assertEquals(RestoreItemStatus.RESTORED, result.status)
        assertSame(item, adapter.receivedItem)
        assertSame(approval, adapter.receivedApproval)
    }

    @Test
    fun unsupportedCategoryIsExplicitlyUnavailable() {
        val registry = RestorerRegistry(listOf(FakeRestorer(setOf("photos"), RestoreItemStatus.RESTORED)))

        val result = registry.restore(item("contacts"))

        assertEquals(RestoreItemStatus.UNAVAILABLE, result.status)
        assertEquals("UNSUPPORTED", result.errorReason)
    }

    @Test
    fun overlappingAdaptersCannotClaimSameCategory() {
        assertThrows(IllegalArgumentException::class.java) {
            RestorerRegistry(
                listOf(
                    FakeRestorer(setOf("photos"), RestoreItemStatus.RESTORED),
                    FakeRestorer(setOf("photos"), RestoreItemStatus.FAILED),
                ),
            )
        }
    }

    private fun item(category: String) = VerifiedRestoreItem(
        transferId = "00000000-0000-4000-8000-000000000121",
        manifestSha256 = "a".repeat(64),
        categoryId = category,
        itemId = "item-1",
        sizeBytes = 0,
        sha256 = "b".repeat(64),
        metadata = emptyMap(),
        payloadFile = File("unused-fixture"),
    )

    private class FakeRestorer(
        override val categoryIds: Set<String>,
        private val resultStatus: RestoreItemStatus,
    ) : CategoryRestorer {
        var receivedItem: VerifiedRestoreItem? = null
        var receivedApproval: RestoreApproval? = null

        override fun restore(item: VerifiedRestoreItem, approval: RestoreApproval?): RestoreItemResult {
            receivedItem = item
            receivedApproval = approval
            return RestoreItemResult(item.itemId, resultStatus)
        }
    }
}
