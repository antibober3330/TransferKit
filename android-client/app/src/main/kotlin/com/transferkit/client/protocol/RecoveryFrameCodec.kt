package com.transferkit.client.protocol

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val MAX_RECOVERY_PAYLOAD_BYTES: Int = 256 * 1024
private const val RECOVERY_FRAME_VERSION = 1
private const val RECOVERY_FRAME_HEADER_SIZE = 9

enum class RecoveryFrameKind(val magic: String) {
    HELLO("TKRH"),
    CHALLENGE("TKRQ"),
    PROOF("TKRP"),
    ACCEPT("TKRA"),
    BLOCKED("TKRB"),
    CHECKPOINT("TKCP"),
    PLAN("TKPL"),
}

data class RecoveryFrame(val kind: RecoveryFrameKind, val payload: ByteArray)

/** Bounded frame codec; callers still validate message contracts and cryptographic proofs. */
object RecoveryFrameCodec {
    fun encode(kind: RecoveryFrameKind, canonicalJsonPayload: ByteArray): ByteArray {
        validatePayload(canonicalJsonPayload)
        return ByteBuffer.allocate(RECOVERY_FRAME_HEADER_SIZE + canonicalJsonPayload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putAscii(kind.magic)
            .put(RECOVERY_FRAME_VERSION.toByte())
            .putInt(canonicalJsonPayload.size)
            .put(canonicalJsonPayload)
            .array()
    }

    fun decode(frame: ByteArray, expectedKind: RecoveryFrameKind? = null): RecoveryFrame {
        if (frame.size < RECOVERY_FRAME_HEADER_SIZE) throw ProtocolException("Recovery frame is shorter than its header")
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.readAscii(4)
        val version = buffer.get().toInt() and 0xff
        if (version != RECOVERY_FRAME_VERSION) throw ProtocolException("Unsupported Recovery frame version: $version")
        val kind = RecoveryFrameKind.entries.firstOrNull { it.magic == magic }
            ?: throw ProtocolException("Unknown Recovery frame magic")
        if (expectedKind != null && kind != expectedKind) throw ProtocolException("Unexpected Recovery frame kind")
        val payloadSize = buffer.int.toLong() and 0xffff_ffffL
        if (payloadSize !in 1..MAX_RECOVERY_PAYLOAD_BYTES.toLong()) {
            throw ProtocolException("Recovery payload size is outside the allowed range")
        }
        if (frame.size.toLong() != RECOVERY_FRAME_HEADER_SIZE + payloadSize) {
            throw ProtocolException("Recovery frame length does not match payload_size")
        }
        val payload = ByteArray(payloadSize.toInt()).also(buffer::get)
        validatePayload(payload)
        return RecoveryFrame(kind, payload)
    }

    fun read(input: InputStream, expectedKind: RecoveryFrameKind? = null): RecoveryFrame {
        val header = input.readExactly(RECOVERY_FRAME_HEADER_SIZE)
        val payloadSize = ByteBuffer.wrap(header, 5, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
        if (payloadSize !in 1..MAX_RECOVERY_PAYLOAD_BYTES.toLong()) {
            throw ProtocolException("Recovery payload size is outside the allowed range")
        }
        return decode(header + input.readExactly(payloadSize.toInt()), expectedKind)
    }

    private fun validatePayload(payload: ByteArray) {
        if (payload.isEmpty() || payload.size > MAX_RECOVERY_PAYLOAD_BYTES) {
            throw ProtocolException("Recovery payload size is outside the allowed range")
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString()
        } catch (error: Exception) {
            throw ProtocolException("Recovery payload must be valid UTF-8 JSON", error)
        }
        val root = try {
            Json.parseToJsonElement(text)
        } catch (error: Exception) {
            throw ProtocolException("Recovery payload must be valid UTF-8 JSON", error)
        }
        if (root !is JsonObject) throw ProtocolException("Recovery payload must be a JSON object")
        if (root.canonicalJson() != text) throw ProtocolException("Recovery payload must use canonical JSON encoding")
    }

    private fun JsonElement.canonicalJson(): String = when (this) {
        is JsonObject -> entries.sortedBy { it.key }.joinToString(",", "{", "}") {
            "${JsonPrimitive(it.key)}:${it.value.canonicalJson()}"
        }
        is JsonArray -> joinToString(",", "[", "]") { it.canonicalJson() }
        is JsonPrimitive -> toString()
    }

    private fun ByteBuffer.putAscii(value: String) = put(value.toByteArray(Charsets.US_ASCII))
    private fun ByteBuffer.readAscii(size: Int) = String(ByteArray(size).also(::get), Charsets.US_ASCII)

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            if (count < 0) throw EOFException("Stream ended before the Recovery frame was complete")
            if (count == 0) {
                val value = read()
                if (value < 0) throw EOFException("Stream ended before the Recovery frame was complete")
                bytes[offset++] = value.toByte()
            } else {
                offset += count
            }
        }
        return bytes
    }
}
