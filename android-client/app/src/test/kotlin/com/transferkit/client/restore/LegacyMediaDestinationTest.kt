package com.transferkit.client.restore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMediaDestinationTest {
    @Test
    fun mapsEachLegacyMediaCategoryToItsTransferKitPublicFolder() {
        assertEquals("Pictures/TransferKit", destination("photos", "image/jpeg")?.relativeDirectory)
        assertEquals("Movies/TransferKit", destination("videos", "video/mp4")?.relativeDirectory)
        assertEquals("Music/TransferKit", destination("audio", "audio/mpeg")?.relativeDirectory)
        assertEquals("Download/TransferKit", destination("downloads", "application/pdf")?.relativeDirectory)
    }

    @Test
    fun generatedNameIsDeterministicAndDoesNotContainUntrustedPathComponents() {
        val first = destination("photos", "image/jpeg", path = "../../DCIM/unsafe name.jpg")
        val retry = destination("photos", "image/jpeg", path = "../../DCIM/unsafe name.jpg")

        assertNotNull(first)
        assertEquals(first, retry)
        assertTrue(first!!.displayName.startsWith("tk_"))
        assertTrue(first.displayName.endsWith("_unsafe name.jpg"))
        assertTrue(!first.displayName.contains('/') && !first.displayName.contains('\\'))
    }

    @Test
    fun distinctTransferItemsCannotClaimSameManagedFilename() {
        val first = destination("photos", "image/jpeg", itemId = "first")
        val second = destination("photos", "image/jpeg", itemId = "second")

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first!!.displayName != second!!.displayName)
    }

    @Test
    fun rejectsUnsupportedCategoryAndCategoryMimeMismatch() {
        assertNull(destination("contacts", "text/vcard"))
        assertNull(destination("photos", "video/mp4"))
        assertNull(destination("audio", "image/jpeg"))
    }

    private fun destination(
        category: String,
        mimeType: String,
        itemId: String = "fixture",
        path: String = "folder/fixture.bin",
    ): LegacyMediaDestination? = LegacyMediaDestination.from(
        VerifiedRestoreItem(
            transferId = "00000000-0000-4000-8000-000000000001",
            manifestSha256 = "a".repeat(64),
            categoryId = category,
            itemId = itemId,
            sizeBytes = 0L,
            sha256 = "b".repeat(64),
            metadata = mapOf("path" to path, "mime_type" to mimeType),
            payloadFile = File("unused"),
        ),
    )
}
