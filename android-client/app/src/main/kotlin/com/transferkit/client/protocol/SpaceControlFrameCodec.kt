package com.transferkit.client.protocol

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val SPACE_CONTROL_PROTOCOL_VERSION: String = "2"
const val SPACE_CONTROL_FRAME_VERSION: Int = 1
const val MAX_SPACE_CONTROL_PAYLOAD_BYTES: Int = 64 * 1024 * 1024
const val SPACE_CONTROL_HEADER_SIZE: Int = 9

enum class SpaceControlKind(val magic: String) {
    REPORT("TKSP"),
    DECISION("TKSD"),
}

data class SpaceControlFrame(val kind: SpaceControlKind, val payload: ByteArray)

/** Bounded canonical JSON envelope. The frame version and negotiated protocol version are distinct. */
@OptIn(ExperimentalSerializationApi::class)
object SpaceControlFrameCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
        ignoreUnknownKeys = false
    }

    fun encode(report: SpaceReportContract): ByteArray {
        report.validate()
        return encodeJson(SpaceControlKind.REPORT, json.encodeToString(report))
    }

    fun encode(decision: SpaceDecisionContract): ByteArray {
        decision.validate()
        return encodeJson(SpaceControlKind.DECISION, json.encodeToString(decision))
    }

    fun decodeReport(frame: ByteArray): SpaceReportContract {
        val payload = decode(frame, SpaceControlKind.REPORT).payload
        val parsed = decodeContract(payload, SpaceReportContract.serializer())
        parsed.validate()
        return parsed
    }

    fun decodeDecision(frame: ByteArray): SpaceDecisionContract {
        val payload = decode(frame, SpaceControlKind.DECISION).payload
        val parsed = decodeContract(payload, SpaceDecisionContract.serializer())
        parsed.validate()
        return parsed
    }

    fun readReport(input: InputStream): SpaceReportContract =
        decodeContract(read(input, SpaceControlKind.REPORT).payload, SpaceReportContract.serializer())
            .also(SpaceReportContract::validate)

    fun readDecision(input: InputStream): SpaceDecisionContract =
        decodeContract(read(input, SpaceControlKind.DECISION).payload, SpaceDecisionContract.serializer())
            .also(SpaceDecisionContract::validate)

    fun decode(frame: ByteArray, expectedKind: SpaceControlKind? = null): SpaceControlFrame {
        if (frame.size < SPACE_CONTROL_HEADER_SIZE) throw ProtocolException("Space frame is shorter than its header")
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.readAscii(4)
        val version = buffer.get().toInt() and 0xff
        if (version != SPACE_CONTROL_FRAME_VERSION) throw ProtocolException("Unsupported Space frame version: $version")
        val kind = SpaceControlKind.entries.firstOrNull { it.magic == magic }
            ?: throw ProtocolException("Unknown Space frame magic")
        if (expectedKind != null && kind != expectedKind) throw ProtocolException("Unexpected Space frame kind")
        val payloadSize = buffer.int.toLong() and 0xffff_ffffL
        if (payloadSize !in 1..MAX_SPACE_CONTROL_PAYLOAD_BYTES.toLong()) {
            throw ProtocolException("Space payload size is outside the allowed range")
        }
        if (frame.size.toLong() != SPACE_CONTROL_HEADER_SIZE + payloadSize) {
            throw ProtocolException("Space frame length does not match payload_size")
        }
        val payload = ByteArray(payloadSize.toInt()).also(buffer::get)
        validateCanonicalJsonPayload(payload)
        return SpaceControlFrame(kind, payload)
    }

    fun read(input: InputStream, expectedKind: SpaceControlKind? = null): SpaceControlFrame {
        val header = input.readExactly(SPACE_CONTROL_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.readAscii(4)
        val version = buffer.get().toInt() and 0xff
        if (version != SPACE_CONTROL_FRAME_VERSION) throw ProtocolException("Unsupported Space frame version: $version")
        val kind = SpaceControlKind.entries.firstOrNull { it.magic == magic }
            ?: throw ProtocolException("Unknown Space frame magic")
        if (expectedKind != null && kind != expectedKind) throw ProtocolException("Unexpected Space frame kind")
        val payloadSize = buffer.int.toLong() and 0xffff_ffffL
        if (payloadSize !in 1..MAX_SPACE_CONTROL_PAYLOAD_BYTES.toLong()) {
            throw ProtocolException("Space payload size is outside the allowed range")
        }
        val payload = input.readExactly(payloadSize.toInt())
        validateCanonicalJsonPayload(payload)
        return SpaceControlFrame(kind, payload)
    }

    private fun encodeJson(kind: SpaceControlKind, jsonText: String): ByteArray {
        val root = runCatching { Json.parseToJsonElement(jsonText) }.getOrElse {
            throw ProtocolException("Space payload must be valid JSON", it)
        }
        if (root !is JsonObject) throw ProtocolException("Space payload must be a JSON object")
        val payload = root.canonicalJson().toByteArray(Charsets.UTF_8)
        if (payload.isEmpty() || payload.size > MAX_SPACE_CONTROL_PAYLOAD_BYTES) {
            throw ProtocolException("Space payload size is outside the allowed range")
        }
        return ByteBuffer.allocate(SPACE_CONTROL_HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putAscii(kind.magic)
            .put(SPACE_CONTROL_FRAME_VERSION.toByte())
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    private fun <T> decodeContract(payload: ByteArray, serializer: KSerializer<T>): T {
        val text = payload.toString(Charsets.UTF_8)
        return runCatching { json.decodeFromString(serializer, text) }.getOrElse {
            throw ProtocolException("Space payload does not match its contract", it)
        }
    }

    private fun validateCanonicalJsonPayload(payload: ByteArray) {
        if (payload.isEmpty()) throw ProtocolException("Space payload must not be empty")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString()
        } catch (error: Exception) {
            throw ProtocolException("Space payload must be valid UTF-8", error)
        }
        val root = runCatching { Json.parseToJsonElement(text) }.getOrElse {
            throw ProtocolException("Space payload must be valid JSON", it)
        }
        if (root !is JsonObject || root.canonicalJson() != text) {
            throw ProtocolException("Space payload must be a canonical JSON object")
        }
    }

    private fun JsonElement.canonicalJson(depth: Int = 0): String {
        if (depth > 32) throw ProtocolException("Space JSON exceeds the maximum nesting depth")
        return when (this) {
            is JsonObject -> entries.sortedWith { first, second ->
                JSON_CODE_POINT_ORDER.compare(first.key, second.key)
            }.joinToString(",", "{", "}") {
                "${JsonPrimitive(it.key)}:${it.value.canonicalJson(depth + 1)}"
            }
            is JsonArray -> joinToString(",", "[", "]") { it.canonicalJson(depth + 1) }
            is JsonPrimitive -> toString()
        }
    }

    private fun ByteBuffer.putAscii(value: String) = put(value.toByteArray(Charsets.US_ASCII))
    private fun ByteBuffer.readAscii(size: Int) = String(ByteArray(size).also(::get), Charsets.US_ASCII)

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            if (count < 0) throw EOFException("Stream ended before the Space frame was complete")
            if (count == 0) {
                val value = read()
                if (value < 0) throw EOFException("Stream ended before the Space frame was complete")
                bytes[offset++] = value.toByte()
            } else {
                offset += count
            }
        }
        return bytes
    }
}
