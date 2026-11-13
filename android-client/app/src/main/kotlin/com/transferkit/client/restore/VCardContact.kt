package com.transferkit.client.restore

import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Data
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64

/** A provider-neutral row which can be inserted and read back through ContactsContract.Data. */
internal data class ContactDataRow(
    val mimeType: String,
    val values: Map<String, Any>,
)

internal data class ParsedVCardContact(val rows: List<ContactDataRow>)

/**
 * Conservative importer for the portable text properties emitted by Android vCard providers.
 * Unknown or encoded properties fail before a Contacts Provider write, so data is never silently
 * discarded. The full received vCard byte stream remains independently SHA-256 verified.
 */
internal object VCardContactParser {
    const val MAX_VCARD_BYTES = 1024 * 1024
    private const val MAX_CONTACT_DATA_ROWS = 256
    private const val MAX_PROPERTY_CHARS = 64 * 1024
    private const val MAX_PROPERTIES = 2048
    private const val MAX_PHOTO_BYTES = 256 * 1024

    fun parse(bytes: ByteArray): ParsedVCardContact {
        require(bytes.isNotEmpty() && bytes.size <= MAX_VCARD_BYTES) { "VCARD_SIZE_LIMIT" }
        val source = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val lines = unfold(source)
        require(lines.size <= MAX_PROPERTIES) { "VCARD_PROPERTY_LIMIT" }
        require(lines.firstOrNull()?.equals("BEGIN:VCARD", ignoreCase = true) == true) { "INVALID_VCARD" }
        require(lines.lastOrNull()?.equals("END:VCARD", ignoreCase = true) == true) { "INVALID_VCARD" }

        val properties = lines.drop(1).dropLast(1).map(::parseProperty)
        val versions = properties.filter { it.name == "VERSION" }.map { it.value }
        require(versions.size == 1 && versions.single() in setOf("2.1", "3.0", "4.0")) { "UNSUPPORTED_VCARD_VERSION" }
        val unsupported = properties.firstOrNull { it.name !in SUPPORTED_PROPERTIES && it.name !in IGNORABLE_PROPERTIES }
        require(unsupported == null) { "UNSUPPORTED_VCARD_PROPERTY" }
        require(properties.none { property ->
            val encoding = property.parameters["ENCODING"]
            val charset = property.parameters["CHARSET"]
            (encoding != null && !(property.name == "PHOTO" && encoding.equals("b", true)) &&
                !encoding.equals("QUOTED-PRINTABLE", true)) ||
                (charset != null && charset.uppercase() !in TEXT_CHARSETS) ||
                (charset != null && !charset.equals("UTF-8", true) &&
                    !property.parameters["ENCODING"].equals("QUOTED-PRINTABLE", true))
        }) { "UNSUPPORTED_VCARD_ENCODING" }

        val rows = mutableListOf<ContactDataRow>()
        val names = properties.filter { it.name == "N" }
        val formattedNames = properties.filter { it.name == "FN" }
        require(names.size <= 1 && formattedNames.size <= 1) { "INVALID_VCARD" }
        if (names.isNotEmpty() || formattedNames.isNotEmpty()) {
            val components = names.firstOrNull()?.let { splitEscaped(it.value, ';').map(::unescape) }.orEmpty()
            val displayName = formattedNames.firstOrNull()?.let { unescape(it.value) }
                ?: components.filter(String::isNotBlank).joinToString(" ")
            rows += row(
                CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                CommonDataKinds.StructuredName.DISPLAY_NAME to displayName,
                CommonDataKinds.StructuredName.GIVEN_NAME to components.getOrNull(1)?.takeIf(String::isNotEmpty),
                CommonDataKinds.StructuredName.FAMILY_NAME to components.getOrNull(0)?.takeIf(String::isNotEmpty),
                CommonDataKinds.StructuredName.PREFIX to components.getOrNull(3)?.takeIf(String::isNotEmpty),
                CommonDataKinds.StructuredName.MIDDLE_NAME to components.getOrNull(2)?.takeIf(String::isNotEmpty),
                CommonDataKinds.StructuredName.SUFFIX to components.getOrNull(4)?.takeIf(String::isNotEmpty),
            )
        }

        properties.filter { it.name == "TEL" }.forEach { property ->
            rows += row(
                CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                CommonDataKinds.Phone.NUMBER to unescape(property.value),
                CommonDataKinds.Phone.TYPE to phoneType(property.typeTokens()),
                CommonDataKinds.Phone.LABEL to customType(property.typeTokens(), PHONE_TYPES),
            )
        }
        properties.filter { it.name == "EMAIL" }.forEach { property ->
            rows += row(
                CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                CommonDataKinds.Email.ADDRESS to unescape(property.value),
                CommonDataKinds.Email.TYPE to emailType(property.typeTokens()),
                CommonDataKinds.Email.LABEL to customType(property.typeTokens(), EMAIL_TYPES),
            )
        }
        val organizations = properties.filter { it.name == "ORG" }
        val titles = properties.filter { it.name == "TITLE" }
        require(organizations.size <= 1 && titles.size <= 1) { "INVALID_VCARD" }
        if (organizations.isNotEmpty() || titles.isNotEmpty()) {
            val parts = organizations.firstOrNull()?.let { splitEscaped(it.value, ';').map(::unescape) }.orEmpty()
            rows += row(
                CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                CommonDataKinds.Organization.COMPANY to parts.firstOrNull(),
                CommonDataKinds.Organization.DEPARTMENT to parts.drop(1).takeIf { it.isNotEmpty() }?.joinToString(" / "),
                CommonDataKinds.Organization.TITLE to titles.firstOrNull()?.let { unescape(it.value) },
                CommonDataKinds.Organization.TYPE to CommonDataKinds.Organization.TYPE_WORK,
            )
        }
        properties.filter { it.name == "ADR" }.forEach { property ->
            val parts = splitEscaped(property.value, ';').map(::unescape)
            require(parts.size <= 7) { "INVALID_VCARD" }
            val padded = parts + List(7 - parts.size) { "" }
            val type = postalType(property.typeTokens())
            rows += row(
                CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
                CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS to padded.filter(String::isNotBlank).joinToString(", "),
                CommonDataKinds.StructuredPostal.TYPE to type.first,
                CommonDataKinds.StructuredPostal.LABEL to type.second,
                CommonDataKinds.StructuredPostal.POBOX to padded[0].ifBlank { null },
                CommonDataKinds.StructuredPostal.STREET to listOf(padded[1], padded[2]).filter(String::isNotBlank).joinToString(" ").ifBlank { null },
                CommonDataKinds.StructuredPostal.CITY to padded[3].ifBlank { null },
                CommonDataKinds.StructuredPostal.REGION to padded[4].ifBlank { null },
                CommonDataKinds.StructuredPostal.POSTCODE to padded[5].ifBlank { null },
                CommonDataKinds.StructuredPostal.COUNTRY to padded[6].ifBlank { null },
            )
        }
        properties.filter { it.name == "NOTE" }.forEach { property ->
            rows += row(CommonDataKinds.Note.CONTENT_ITEM_TYPE, CommonDataKinds.Note.NOTE to unescape(property.value))
        }
        properties.filter { it.name == "URL" }.forEach { property ->
            rows += row(
                CommonDataKinds.Website.CONTENT_ITEM_TYPE,
                CommonDataKinds.Website.URL to unescape(property.value),
                CommonDataKinds.Website.TYPE to CommonDataKinds.Website.TYPE_HOME,
            )
        }
        properties.filter { it.name == "NICKNAME" }.forEach { property ->
            rows += row(
                CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
                CommonDataKinds.Nickname.NAME to unescape(property.value),
                CommonDataKinds.Nickname.TYPE to CommonDataKinds.Nickname.TYPE_DEFAULT,
            )
        }
        properties.filter { it.name == "BDAY" }.forEach { property ->
            rows += row(
                CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                CommonDataKinds.Event.START_DATE to normalizeDate(property.value),
                CommonDataKinds.Event.TYPE to CommonDataKinds.Event.TYPE_BIRTHDAY,
            )
        }
        properties.filter { it.name == "PHOTO" }.forEach { property ->
            val encoding = property.parameters["ENCODING"]
            require(encoding.equals("b", true)) { "UNSUPPORTED_VCARD_ENCODING" }
            val photo = runCatching { Base64.getMimeDecoder().decode(property.value) }.getOrNull()
                ?: throw IllegalArgumentException("INVALID_VCARD")
            require(photo.isNotEmpty() && photo.size <= MAX_PHOTO_BYTES) { "VCARD_PHOTO_LIMIT" }
            rows += ContactDataRow(CommonDataKinds.Photo.CONTENT_ITEM_TYPE, mapOf(Data.DATA15 to photo))
        }
        require(rows.isNotEmpty()) { "INVALID_VCARD" }
        require(rows.size <= MAX_CONTACT_DATA_ROWS) { "VCARD_PROPERTY_LIMIT" }
        return ParsedVCardContact(rows)
    }

    private data class Property(val name: String, val parameters: Map<String, String>, val value: String) {
        fun typeTokens(): Set<String> = buildSet {
            parameters.filterKeys { it == "TYPE" }.values.forEach { raw ->
                raw.split(',').map(String::trim).filter(String::isNotEmpty).forEach { add(it.uppercase()) }
            }
        }
    }

    private fun parseProperty(line: String): Property {
        require(line.length <= MAX_PROPERTY_CHARS) { "VCARD_PROPERTY_LIMIT" }
        val delimiter = findUnquotedColon(line)
        require(delimiter > 0) { "INVALID_VCARD" }
        val header = splitHeader(line.substring(0, delimiter))
        val rawName = header.first().substringAfterLast('.').uppercase()
        require(rawName.matches(Regex("^[A-Z0-9-]+$"))) { "INVALID_VCARD" }
        val params = linkedMapOf<String, String>()
        header.drop(1).forEach { raw ->
            val equals = raw.indexOf('=')
            val key = if (equals < 0) "TYPE" else raw.substring(0, equals).uppercase()
            val value = if (equals < 0) raw else raw.substring(equals + 1).trim('"')
            require(key.matches(Regex("^[A-Z0-9-]+$")) && value.length <= 256) { "INVALID_VCARD" }
            params[key] = value
        }
        val rawValue = line.substring(delimiter + 1)
        val value = if (params["ENCODING"].equals("QUOTED-PRINTABLE", true)) {
            decodeQuotedPrintable(rawValue, params["CHARSET"] ?: "UTF-8")
        } else {
            rawValue
        }
        return Property(rawName, params, value)
    }

    private fun unfold(source: String): List<String> {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val unfolded = mutableListOf<String>()
        normalized.split('\n').forEach { line ->
            if (line.startsWith(' ') || line.startsWith('\t')) {
                require(unfolded.isNotEmpty()) { "INVALID_VCARD" }
                val previous = unfolded.last()
                if (previous.endsWith('=') && isQuotedPrintableLine(previous)) {
                    unfolded[unfolded.lastIndex] = previous.dropLast(1) + line.drop(1)
                } else {
                    unfolded[unfolded.lastIndex] += line.drop(1)
                }
            } else if (line.isNotEmpty()) {
                val previous = unfolded.lastOrNull()
                if (previous != null && previous.endsWith('=') && isQuotedPrintableLine(previous)) {
                    unfolded[unfolded.lastIndex] = previous.dropLast(1) + line
                } else {
                    unfolded += line
                }
            }
        }
        return unfolded
    }

    private fun isQuotedPrintableLine(line: String): Boolean {
        val header = line.substringBefore(':', "")
        return header.split(';').drop(1).any { parameter ->
            parameter.substringBefore('=').equals("ENCODING", true) &&
                parameter.substringAfter('=', "").trim('"').equals("QUOTED-PRINTABLE", true)
        }
    }

    private fun decodeQuotedPrintable(value: String, charsetName: String): String {
        val charset = Charset.forName(charsetName)
        val output = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '=' && index + 2 < value.length && value[index + 1].digitToIntOrNull(16) != null &&
                value[index + 2].digitToIntOrNull(16) != null
            ) {
                output.write(value.substring(index + 1, index + 3).toInt(16))
                index += 3
            } else if (char == '=' && index + 1 < value.length && value[index + 1] == '\n') {
                index += 2
            } else {
                val codePoint = value.codePointAt(index)
                val text = String(Character.toChars(codePoint))
                output.write(text.toByteArray(charset))
                index += Character.charCount(codePoint)
            }
        }
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray()))
            .toString()
    }

    private fun findUnquotedColon(value: String): Int {
        var quoted = false
        value.forEachIndexed { index, char ->
            if (char == '"') quoted = !quoted
            if (char == ':' && !quoted) return index
        }
        return -1
    }

    private fun splitHeader(value: String): List<String> {
        val result = mutableListOf<String>()
        var quoted = false
        var start = 0
        value.forEachIndexed { index, char ->
            if (char == '"') quoted = !quoted
            if (char == ';' && !quoted) {
                result += value.substring(start, index)
                start = index + 1
            }
        }
        result += value.substring(start)
        return result
    }

    private fun splitEscaped(value: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val part = StringBuilder()
        var escaped = false
        value.forEach { char ->
            if (escaped) {
                part.append('\\').append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == delimiter) {
                result += part.toString()
                part.clear()
            } else {
                part.append(char)
            }
        }
        if (escaped) part.append('\\')
        result += part.toString()
        return result
    }

    private fun unescape(value: String): String {
        val output = StringBuilder(value.length)
        var escaped = false
        value.forEach { char ->
            if (escaped) {
                output.append(when (char) { 'n', 'N' -> '\n'; else -> char })
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else {
                output.append(char)
            }
        }
        if (escaped) output.append('\\')
        return output.toString()
    }

    private fun normalizeDate(value: String): String = try {
        when {
            value.matches(Regex("^\\d{8}$")) -> LocalDate.parse("${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}").toString()
            else -> LocalDate.parse(value).toString()
        }
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("INVALID_VCARD")
    }

    private fun row(mimeType: String, vararg fields: Pair<String, Any?>): ContactDataRow {
        val values = fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        require(values.isNotEmpty()) { "INVALID_VCARD" }
        return ContactDataRow(mimeType, values)
    }

    private fun phoneType(types: Set<String>): Int = when {
        "CELL" in types || "MOBILE" in types -> CommonDataKinds.Phone.TYPE_MOBILE
        "HOME" in types -> CommonDataKinds.Phone.TYPE_HOME
        "WORK" in types -> CommonDataKinds.Phone.TYPE_WORK
        "FAX" in types -> CommonDataKinds.Phone.TYPE_OTHER_FAX
        "PAGER" in types -> CommonDataKinds.Phone.TYPE_PAGER
        "MAIN" in types -> CommonDataKinds.Phone.TYPE_MAIN
        else -> if (types.any { it !in PHONE_TYPES }) CommonDataKinds.Phone.TYPE_CUSTOM else CommonDataKinds.Phone.TYPE_OTHER
    }

    private fun emailType(types: Set<String>): Int = when {
        "HOME" in types -> CommonDataKinds.Email.TYPE_HOME
        "WORK" in types -> CommonDataKinds.Email.TYPE_WORK
        "CELL" in types || "MOBILE" in types -> CommonDataKinds.Email.TYPE_MOBILE
        else -> if (types.any { it !in EMAIL_TYPES }) CommonDataKinds.Email.TYPE_CUSTOM else CommonDataKinds.Email.TYPE_OTHER
    }

    private fun postalType(types: Set<String>): Pair<Int, String?> = when {
        "HOME" in types -> CommonDataKinds.StructuredPostal.TYPE_HOME to null
        "WORK" in types -> CommonDataKinds.StructuredPostal.TYPE_WORK to null
        types.any { it !in POSTAL_TYPES } -> CommonDataKinds.StructuredPostal.TYPE_CUSTOM to types.firstOrNull { it !in POSTAL_TYPES }
        else -> CommonDataKinds.StructuredPostal.TYPE_OTHER to null
    }

    private fun customType(types: Set<String>, known: Set<String>): String? =
        types.firstOrNull { it !in known }

    private val PHONE_TYPES = setOf("HOME", "WORK", "CELL", "MOBILE", "MAIN", "FAX", "PAGER", "VOICE", "TEXT", "OTHER")
    private val EMAIL_TYPES = setOf("HOME", "WORK", "CELL", "MOBILE", "INTERNET", "PREF", "OTHER")
    private val POSTAL_TYPES = setOf("HOME", "WORK", "POSTAL", "PARCEL", "DOM", "INTL", "OTHER")
    private val TEXT_CHARSETS = setOf("UTF-8", "ISO-8859-1", "US-ASCII")
    private val SUPPORTED_PROPERTIES = setOf("VERSION", "FN", "N", "TEL", "EMAIL", "ORG", "TITLE", "ADR", "NOTE", "URL", "BDAY", "NICKNAME", "PHOTO")
    private val IGNORABLE_PROPERTIES = setOf("PRODID", "REV", "UID", "CLASS")
}
