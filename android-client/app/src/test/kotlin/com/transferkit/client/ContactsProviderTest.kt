package com.transferkit.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsProviderTest {
    @Test
    fun copiesVCardBytesAndHashesExactlyWhatWasWritten() {
        val vcard = "BEGIN:VCARD\r\nVERSION:2.1\r\nFN:Тест\r\nEND:VCARD\r\n".toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()

        val result = copyAndDigest(ByteArrayInputStream(vcard), output)

        assertArrayEquals(vcard, output.toByteArray())
        assertEquals(vcard.size.toLong(), result.sizeBytes)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(vcard)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        assertEquals(expected, result.sha256)
        assertEquals(64, result.sha256.length)
    }

    @Test
    fun opaqueItemIdIsStableWithoutExposingProviderLookupKey() {
        val lookupKey = "provider-private-lookup-key"

        val first = contactItemId(lookupKey)
        val second = contactItemId(lookupKey)

        assertEquals(first, second)
        assertEquals(72, first.length) // "contact_" plus a SHA-256 hex digest.
        assertEquals(false, first.contains(lookupKey))
        assertEquals(false, first.contains("provider-private"))
    }

    @Test
    fun emptyExportHasEmptySha256AndZeroSize() {
        val output = ByteArrayOutputStream()

        val result = copyAndDigest(ByteArrayInputStream(ByteArray(0)), output)

        assertEquals(0L, result.sizeBytes)
        assertEquals(64, result.sha256.length)
        assertEquals(0, output.size())
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            result.sha256,
        )
    }
}
