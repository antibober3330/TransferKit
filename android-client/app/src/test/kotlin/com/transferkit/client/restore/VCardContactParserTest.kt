package com.transferkit.client.restore

import android.provider.ContactsContract.CommonDataKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VCardContactParserTest {
    @Test
    fun parsesPortableFieldsEscapesAndFoldedLines() {
        val card = """BEGIN:VCARD
VERSION:3.0
N:Doe;Alex;M;Dr.;Jr.
FN:Dr. Alex M Doe Jr.
TEL;TYPE=CELL,VOICE:+380 67 123 45 67
EMAIL;TYPE=WORK:alex@example.test
ORG:Example\, Inc.;Engineering
TITLE:Architect
ADR;TYPE=HOME:;;123 Main St;Kyiv;Kyivska;01001;Ukraine
NOTE:first\nsecond
URL:https://example.test/a-very-long-url-
 folded-part
NICKNAME:Sashko
BDAY:19850412
END:VCARD""".replace("\n ", "")

        val contact = VCardContactParser.parse(card.toByteArray(Charsets.UTF_8))

        val name = contact.rows.single { it.mimeType == CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }
        assertEquals("Dr. Alex M Doe Jr.", name.values[CommonDataKinds.StructuredName.DISPLAY_NAME])
        assertEquals("Alex", name.values[CommonDataKinds.StructuredName.GIVEN_NAME])
        assertEquals("Doe", name.values[CommonDataKinds.StructuredName.FAMILY_NAME])
        val phone = contact.rows.single { it.mimeType == CommonDataKinds.Phone.CONTENT_ITEM_TYPE }
        assertEquals("+380 67 123 45 67", phone.values[CommonDataKinds.Phone.NUMBER])
        assertEquals(CommonDataKinds.Phone.TYPE_MOBILE, phone.values[CommonDataKinds.Phone.TYPE])
        val organization = contact.rows.single { it.mimeType == CommonDataKinds.Organization.CONTENT_ITEM_TYPE }
        assertEquals("Example, Inc.", organization.values[CommonDataKinds.Organization.COMPANY])
        assertEquals("Engineering", organization.values[CommonDataKinds.Organization.DEPARTMENT])
        assertEquals("Architect", organization.values[CommonDataKinds.Organization.TITLE])
        val address = contact.rows.single { it.mimeType == CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE }
        assertEquals("123 Main St, Kyiv, Kyivska, 01001, Ukraine", address.values[CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS])
        val note = contact.rows.single { it.mimeType == CommonDataKinds.Note.CONTENT_ITEM_TYPE }
        assertEquals("first\nsecond", note.values[CommonDataKinds.Note.NOTE])
        val url = contact.rows.single { it.mimeType == CommonDataKinds.Website.CONTENT_ITEM_TYPE }
        assertEquals("https://example.test/a-very-long-url-folded-part", url.values[CommonDataKinds.Website.URL])
        assertTrue(contact.rows.isNotEmpty())
    }

    @Test
    fun failsClosedOnUnknownPropertiesAndNonUtf8Charset() {
        val unknown = "BEGIN:VCARD\nVERSION:3.0\nFN:Example\nX-SENSITIVE:secret\nEND:VCARD"
        val error = assertThrows(IllegalArgumentException::class.java) {
            VCardContactParser.parse(unknown.toByteArray(Charsets.UTF_8))
        }
        assertEquals("UNSUPPORTED_VCARD_PROPERTY", error.message)

        val charset = "BEGIN:VCARD\nVERSION:3.0\nFN;CHARSET=ISO-8859-1:Example\nEND:VCARD"
        val charsetError = assertThrows(IllegalArgumentException::class.java) {
            VCardContactParser.parse(charset.toByteArray(Charsets.UTF_8))
        }
        assertEquals("UNSUPPORTED_VCARD_ENCODING", charsetError.message)
    }

    @Test
    fun decodesQuotedPrintableUtf8AndSoftLineBreaks() {
        val card = """BEGIN:VCARD
VERSION:2.1
FN:Example
NOTE;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:caf=C3=A9 and soft=
 =20wrapped line
END:VCARD"""

        val contact = VCardContactParser.parse(card.toByteArray(Charsets.UTF_8))

        val note = contact.rows.single { it.mimeType == CommonDataKinds.Note.CONTENT_ITEM_TYPE }
        assertEquals("café and soft wrapped line", note.values[CommonDataKinds.Note.NOTE])
    }

    @Test
    fun preservesTextWhitespaceAndCapsProviderBatchSize() {
        val whitespace = "BEGIN:VCARD\nVERSION:3.0\nFN:Example\nNOTE:  keep edges  \nEND:VCARD"
        val parsed = VCardContactParser.parse(whitespace.toByteArray(Charsets.UTF_8))
        assertEquals(
            "  keep edges  ",
            parsed.rows.single { it.mimeType == CommonDataKinds.Note.CONTENT_ITEM_TYPE }
                .values[CommonDataKinds.Note.NOTE],
        )

        val manyRows = buildString {
            append("BEGIN:VCARD\nVERSION:3.0\nFN:Example\n")
            repeat(257) { append("TEL;TYPE=CELL:+1555000").append(it.toString().padStart(4, '0')).append('\n') }
            append("END:VCARD")
        }
        assertEquals(
            "VCARD_PROPERTY_LIMIT",
            assertThrows(IllegalArgumentException::class.java) {
                VCardContactParser.parse(manyRows.toByteArray(Charsets.UTF_8))
            }.message,
        )
    }

    @Test
    fun rejectsMalformedDatesAndOversizedInput() {
        val date = "BEGIN:VCARD\nVERSION:3.0\nFN:Example\nBDAY:19850231\nEND:VCARD"
        assertEquals(
            "INVALID_VCARD",
            assertThrows(IllegalArgumentException::class.java) {
                VCardContactParser.parse(date.toByteArray(Charsets.UTF_8))
            }.message,
        )
        assertEquals(
            "VCARD_SIZE_LIMIT",
            assertThrows(IllegalArgumentException::class.java) {
                VCardContactParser.parse(ByteArray(VCardContactParser.MAX_VCARD_BYTES + 1))
            }.message,
        )
    }
}
