package com.transferkit.client.restore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafDocumentDestinationTest {
    @Test
    fun destinationNameIsDeterministicAndBoundToTransferAndItem() {
        val source = item(transferId = "00000000-0000-4000-8000-000000000001", itemId = "doc-1")
        val first = SafDocumentDestination.from(source)
        val retry = SafDocumentDestination.from(source.copy(payloadFile = File("same-item")))
        val otherTransfer = SafDocumentDestination.from(
            source.copy(transferId = "00000000-0000-4000-8000-000000000002"),
        )

        assertNotNull(first)
        assertEquals(first, retry)
        assertTrue(first!!.displayName.startsWith("tk_"))
        assertTrue(first.displayName.endsWith("report.pdf"))
        assertFalse(first.displayName.contains('/'))
        assertFalse(first.displayName.contains('\\'))
        assertFalse(first.displayName == otherTransfer?.displayName)
    }

    @Test
    fun destinationNameSanitizesTraversalAndBoundsLongNames() {
        val destination = SafDocumentDestination.from(
            item(path = "../../" + "x".repeat(300) + "?.pdf"),
        )

        assertNotNull(destination)
        assertTrue(destination!!.displayName.length <= 120)
        assertFalse(destination.displayName.contains(".."))
        assertFalse(destination.displayName.contains('/'))
        assertFalse(destination.displayName.contains('?'))
    }

    @Test
    fun unsupportedMimeFallsBackAndNonDocumentCategoriesAreRejected() {
        val destination = SafDocumentDestination.from(item(mimeType = "application/pdf\nimage/png"))

        assertEquals("application/octet-stream", destination?.mimeType)
        assertNull(SafDocumentDestination.from(item(categoryId = "photos")))
    }

    private fun item(
        transferId: String = "00000000-0000-4000-8000-000000000001",
        itemId: String = "doc-1",
        categoryId: String = "documents",
        path: String = "Reports/report.pdf",
        mimeType: String = "application/pdf",
    ) = VerifiedRestoreItem(
        transferId = transferId,
        manifestSha256 = "a".repeat(64),
        categoryId = categoryId,
        itemId = itemId,
        sizeBytes = 3,
        sha256 = "b".repeat(64),
        metadata = mapOf("path" to path, "mime_type" to mimeType),
        payloadFile = File("unused"),
    )
}
