package com.transferkit.client.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class DurableTransferProgressTest {
    @Test
    fun percentIsBoundedAndHandlesEmptyTransfers() {
        assertEquals(0, DurableTransferProgress("transfer", 0, 0).percent)
        assertEquals(0, DurableTransferProgress("transfer", 0, 100).percent)
        assertEquals(33, DurableTransferProgress("transfer", 33, 100).percent)
        assertEquals(100, DurableTransferProgress("transfer", 150, 100).percent)
        assertEquals(0, DurableTransferProgress("transfer", -1, 100).percent)
    }
}
