package com.transferkit.client.protocol

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

const val PROTOCOL_VERSION: Int = 1
const val CHUNK_HEADER_SIZE: Int = 45
const val ACK_FRAME_SIZE: Int = 39
const val MANIFEST_HEADER_SIZE: Int = 9
const val MAX_CHUNK_BYTES: Int = 8 * 1024 * 1024
const val RECOMMENDED_CHUNK_BYTES: Int = 4 * 1024 * 1024
const val MAX_MANIFEST_BYTES: Int = 64 * 1024 * 1024
const val MAX_MANIFEST_ITEMS: Long = 100_000L
const val MAX_RECEIPT_BYTES: Int = 64 * 1024 * 1024
const val FINISH_REQUEST_SIZE: Int = 21
const val RECEIPT_RESPONSE_HEADER_SIZE: Int = 26
const val SOURCE_IDENTITY_HEADER_SIZE: Int = 21
const val ITEM_RESULT_ACK_SENTINEL: Long = 0xFFFF_FFFFL
const val ITEM_RESULT_ACK_PROTOCOL_VERSION: Int = 2
const val ITEM_RESULT_RESTORED_POST_VERIFIED: Int = 0
const val ITEM_RESULT_RESTORE_FAILED: Int = 1
const val ITEM_RESULT_FAILED_INTEGRITY: Int = 2
const val MAX_UINT32: Long = 0xFFFF_FFFFL

private val CHUNK_MAGIC = byteArrayOf(0x54, 0x4b, 0x43, 0x48) // TKCH
private val MANIFEST_MAGIC = byteArrayOf(0x54, 0x4b, 0x4d, 0x46) // TKMF
private val ACK_MAGIC = byteArrayOf(0x54, 0x4b, 0x41, 0x4b) // TKAK
private val FINISH_MAGIC = byteArrayOf(0x54, 0x4b, 0x46, 0x4e) // TKFN
private val RECEIPT_MAGIC = byteArrayOf(0x54, 0x4b, 0x52, 0x43) // TKRC
private val SOURCE_IDENTITY_MAGIC = byteArrayOf(0x54, 0x4b, 0x53, 0x49) // TKSI

private val CATEGORY_IDS = setOf(
    "photos", "videos", "audio", "documents", "downloads", "contacts", "calendar", "sms",
    "call_log", "apks", "settings", "app_data", "wifi_passwords", "launcher_layout", "accounts",
    "drm", "banking_apps", "two_factor_tokens",
)

class ProtocolException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

fun validateManifestItemCount(count: Long) {
    require(count in 0..MAX_MANIFEST_ITEMS) {
        "Manifest cannot contain more than $MAX_MANIFEST_ITEMS items in total"
    }
}

data class ChunkHeader(
    val transferId: UUID,
    val categoryIdHash: ByteArray,
    val itemIdHash: ByteArray,
    val chunkIndex: Long,
    val totalChunks: Long,
    val chunkSize: Int,
    val payloadCrc32: Long,
)

data class DecodedChunk(val header: ChunkHeader, val payload: ByteArray)

data class ManifestFrame(val payload: ByteArray)

sealed interface IncomingReceiverFrame
data class IncomingManifestFrame(val payload: ByteArray) : IncomingReceiverFrame
data class IncomingChunkFrame(val encodedFrame: ByteArray) : IncomingReceiverFrame
data class IncomingFinishFrame(val transferId: UUID) : IncomingReceiverFrame
data class ReceiptResponse(val transferId: UUID, val status: Int, val payload: ByteArray)
data class SourceIdentity(val transferId: UUID)

enum class AckKind(val wireValue: Int) {
    MANIFEST(0),
    CHUNK(1),
    ITEM_RESULT(2),
}

data class Acknowledgement(
    val kind: AckKind,
    val transferId: UUID,
    val categoryIdHash: ByteArray,
    val itemIdHash: ByteArray,
    val chunkIndex: Long,
    val payloadCrc32: Long,
    val status: Int,
)

/** Byte-exact codec for the shared TKMF, TKCH and TKAK frames. */
object ProtocolCodec {
    fun idHash(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .copyOfRange(0, 4)

    fun encodeChunk(
        transferId: UUID,
        categoryId: String,
        itemId: String,
        chunkIndex: Long,
        totalChunks: Long,
        payload: ByteArray,
    ): ByteArray {
        if (categoryId !in CATEGORY_IDS) throw ProtocolException("Unknown category ID")
        if (itemId.isBlank()) throw ProtocolException("item_id must not be empty")
        if (payload.size > MAX_CHUNK_BYTES) throw ProtocolException("Chunk payload exceeds 8 MiB")
        if (totalChunks !in 1..MAX_UINT32) throw ProtocolException("total_chunks is out of range")
        if (chunkIndex !in 0 until totalChunks) throw ProtocolException("chunk_index is out of range")

        val categoryHash = idHash(categoryId)
        val itemHash = idHash(itemId)
        val crc = crc32(payload)
        val frame = ByteBuffer.allocate(CHUNK_HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        frame.put(CHUNK_MAGIC)
        frame.put(PROTOCOL_VERSION.toByte())
        frame.putUuid(transferId)
        frame.put(categoryHash)
        frame.put(itemHash)
        frame.putUInt32(chunkIndex)
        frame.putUInt32(totalChunks)
        frame.putUInt32(payload.size.toLong())
        frame.putUInt32(crc)
        frame.put(payload)
        return frame.array()
    }

    fun decodeChunk(frame: ByteArray): DecodedChunk {
        if (frame.size < CHUNK_HEADER_SIZE) throw ProtocolException("Frame is shorter than the 45-byte header")
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if (!buffer.readBytes(4).contentEquals(CHUNK_MAGIC)) throw ProtocolException("Invalid chunk magic")
        val version = buffer.get().toInt() and 0xff
        if (version != PROTOCOL_VERSION) throw ProtocolException("Unsupported chunk version: $version")
        val transferId = buffer.getUuid()
        val categoryHash = buffer.readBytes(4)
        val itemHash = buffer.readBytes(4)
        val chunkIndex = buffer.getUInt32()
        val totalChunks = buffer.getUInt32()
        val chunkSize = buffer.getUInt32()
        val expectedCrc = buffer.getUInt32()

        if (totalChunks == 0L || chunkIndex >= totalChunks) throw ProtocolException("Invalid chunk index/total")
        if (chunkSize > MAX_CHUNK_BYTES) throw ProtocolException("Chunk payload exceeds 8 MiB")
        if (frame.size.toLong() != CHUNK_HEADER_SIZE.toLong() + chunkSize) {
            throw ProtocolException("Frame length does not match chunk_size")
        }

        val payload = buffer.readBytes(chunkSize.toInt())
        val actualCrc = crc32(payload)
        if (actualCrc != expectedCrc) throw ProtocolException("CRC32 mismatch")
        return DecodedChunk(
            ChunkHeader(
                transferId,
                categoryHash,
                itemHash,
                chunkIndex,
                totalChunks,
                chunkSize.toInt(),
                expectedCrc,
            ),
            payload,
        )
    }

    fun encodeManifestFrame(canonicalManifestBytes: ByteArray): ByteArray {
        if (canonicalManifestBytes.size > MAX_MANIFEST_BYTES) {
            throw ProtocolException("Manifest frame payload exceeds 64 MiB")
        }
        return ByteBuffer.allocate(MANIFEST_HEADER_SIZE + canonicalManifestBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MANIFEST_MAGIC)
            .put(PROTOCOL_VERSION.toByte())
            .putUInt32(canonicalManifestBytes.size.toLong())
            .put(canonicalManifestBytes)
            .array()
    }

    fun encodeFinishRequest(transferId: UUID): ByteArray {
        val buffer = ByteBuffer.allocate(FINISH_REQUEST_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(FINISH_MAGIC)
        buffer.put(PROTOCOL_VERSION.toByte())
        buffer.putUuid(transferId)
        return buffer.array()
    }

    /** Public, non-secret transfer identity sent before source-session authentication. */
    fun encodeSourceIdentity(transferId: UUID): ByteArray {
        val frame = ByteBuffer.allocate(SOURCE_IDENTITY_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        frame.put(SOURCE_IDENTITY_MAGIC)
        frame.put(PROTOCOL_VERSION.toByte())
        frame.putUuid(transferId)
        return frame.array()
    }

    fun readSourceIdentity(input: InputStream): SourceIdentity {
        val frame = input.readExactly(SOURCE_IDENTITY_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if (!buffer.readBytes(4).contentEquals(SOURCE_IDENTITY_MAGIC)) {
            throw ProtocolException("Invalid source identity magic")
        }
        val version = buffer.get().toInt() and 0xff
        if (version != PROTOCOL_VERSION) throw ProtocolException("Unsupported source identity version: $version")
        return SourceIdentity(buffer.getUuid())
    }

    fun encodeReceiptResponse(transferId: UUID, status: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        if (status !in 0..3) throw ProtocolException("Unknown receipt response status")
        if (payload.size > MAX_RECEIPT_BYTES) throw ProtocolException("Receipt payload exceeds 64 MiB")
        if (status != 0 && payload.isNotEmpty()) throw ProtocolException("Failed receipt response must not include a payload")
        val buffer = ByteBuffer.allocate(RECEIPT_RESPONSE_HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(RECEIPT_MAGIC)
        buffer.put(PROTOCOL_VERSION.toByte())
        buffer.put(status.toByte())
        buffer.putUuid(transferId)
        buffer.putUInt32(payload.size.toLong())
        buffer.put(payload)
        return buffer.array()
    }

    fun readReceiptResponse(input: InputStream): ReceiptResponse {
        val header = input.readExactly(RECEIPT_RESPONSE_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (!buffer.readBytes(4).contentEquals(RECEIPT_MAGIC)) throw ProtocolException("Invalid receipt response magic")
        val version = buffer.get().toInt() and 0xff
        if (version != PROTOCOL_VERSION) throw ProtocolException("Unsupported receipt response version: $version")
        val status = buffer.get().toInt() and 0xff
        if (status !in 0..3) throw ProtocolException("Unknown receipt response status")
        val transferId = buffer.getUuid()
        val payloadSize = buffer.getUInt32()
        if (payloadSize > MAX_RECEIPT_BYTES || (status != 0 && payloadSize != 0L)) {
            throw ProtocolException("Invalid receipt response payload length")
        }
        return ReceiptResponse(transferId, status, input.readExactly(payloadSize.toInt()))
    }

    fun decodeManifestFrame(frame: ByteArray): ManifestFrame {
        if (frame.size < MANIFEST_HEADER_SIZE) throw ProtocolException("Frame is shorter than the manifest header")
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if (!buffer.readBytes(4).contentEquals(MANIFEST_MAGIC)) throw ProtocolException("Invalid manifest magic")
        val version = buffer.get().toInt() and 0xff
        if (version != PROTOCOL_VERSION) throw ProtocolException("Unsupported manifest version: $version")
        val payloadSize = buffer.getUInt32()
        if (payloadSize > MAX_MANIFEST_BYTES || frame.size.toLong() != MANIFEST_HEADER_SIZE + payloadSize) {
            throw ProtocolException("Manifest frame length does not match payload_size")
        }
        return ManifestFrame(buffer.readBytes(payloadSize.toInt()))
    }

    fun encodeAcknowledgement(ack: Acknowledgement): ByteArray {
        requireHashSize(ack.categoryIdHash)
        requireHashSize(ack.itemIdHash)
        if (ack.chunkIndex !in 0..MAX_UINT32 || ack.payloadCrc32 !in 0..MAX_UINT32) {
            throw ProtocolException("ACK integer field is out of range")
        }
        if (ack.status !in ACK_STATUS_MIN..ACK_STATUS_MAX) throw ProtocolException("Unknown ACK status")
        if (ack.kind == AckKind.MANIFEST &&
            (ack.categoryIdHash.any { it != 0.toByte() } || ack.itemIdHash.any { it != 0.toByte() } ||
                ack.chunkIndex != 0L || ack.payloadCrc32 != 0L)
        ) {
            throw ProtocolException("Manifest ACK reserved fields must be zero")
        }
        if (ack.kind == AckKind.ITEM_RESULT &&
            (ack.chunkIndex != ITEM_RESULT_ACK_SENTINEL || ack.payloadCrc32 != 0L ||
                ack.status !in ITEM_RESULT_RESTORED_POST_VERIFIED..ITEM_RESULT_FAILED_INTEGRITY)
        ) {
            throw ProtocolException("ItemResult ACK sentinel, reserved fields, or status is invalid")
        }

        val frame = ByteBuffer.allocate(ACK_FRAME_SIZE).order(ByteOrder.BIG_ENDIAN)
        frame.put(ACK_MAGIC)
        frame.put((if (ack.kind == AckKind.ITEM_RESULT) ITEM_RESULT_ACK_PROTOCOL_VERSION else PROTOCOL_VERSION).toByte())
        frame.put(ack.kind.wireValue.toByte())
        frame.putUuid(ack.transferId)
        frame.put(ack.categoryIdHash)
        frame.put(ack.itemIdHash)
        frame.putUInt32(ack.chunkIndex)
        frame.putUInt32(ack.payloadCrc32)
        frame.put(ack.status.toByte())
        return frame.array()
    }

    fun decodeAcknowledgement(frame: ByteArray, expectedKind: AckKind? = null): Acknowledgement {
        if (frame.size != ACK_FRAME_SIZE) throw ProtocolException("ACK must be exactly 39 bytes")
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if (!buffer.readBytes(4).contentEquals(ACK_MAGIC)) throw ProtocolException("Invalid ACK magic")
        val version = buffer.get().toInt() and 0xff
        val kindValue = buffer.get().toInt() and 0xff
        val kind = AckKind.entries.firstOrNull { it.wireValue == kindValue }
            ?: throw ProtocolException("Unknown ACK kind: $kindValue")
        val expectedVersion = if (kind == AckKind.ITEM_RESULT) ITEM_RESULT_ACK_PROTOCOL_VERSION else PROTOCOL_VERSION
        if (version != expectedVersion) throw ProtocolException("Unsupported ACK version $version for kind $kindValue")
        if (expectedKind != null && kind != expectedKind) throw ProtocolException("Unexpected ACK kind")
        val ack = Acknowledgement(
            kind = kind,
            transferId = buffer.getUuid(),
            categoryIdHash = buffer.readBytes(4),
            itemIdHash = buffer.readBytes(4),
            chunkIndex = buffer.getUInt32(),
            payloadCrc32 = buffer.getUInt32(),
            status = buffer.get().toInt() and 0xff,
        )
        val statusValid = if (kind == AckKind.ITEM_RESULT) {
            ack.status in ITEM_RESULT_RESTORED_POST_VERIFIED..ITEM_RESULT_FAILED_INTEGRITY
        } else {
            ack.status in ACK_STATUS_MIN..ACK_STATUS_MAX
        }
        if (!statusValid) throw ProtocolException("Unknown ACK status")
        if (kind == AckKind.MANIFEST &&
            (ack.categoryIdHash.any { it != 0.toByte() } || ack.itemIdHash.any { it != 0.toByte() } ||
                ack.chunkIndex != 0L || ack.payloadCrc32 != 0L)
        ) {
            throw ProtocolException("Manifest ACK reserved fields must be zero")
        }
        if (kind == AckKind.ITEM_RESULT &&
            (ack.chunkIndex != ITEM_RESULT_ACK_SENTINEL || ack.payloadCrc32 != 0L)
        ) {
            throw ProtocolException("ItemResult ACK sentinel or reserved fields are invalid")
        }
        return ack
    }

    fun readAcknowledgement(input: InputStream, expectedKind: AckKind? = null): Acknowledgement =
        decodeAcknowledgement(input.readExactly(ACK_FRAME_SIZE), expectedKind)

    fun readManifestFrame(input: InputStream): ManifestFrame {
        val header = input.readExactly(MANIFEST_HEADER_SIZE)
        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (!headerBuffer.readBytes(4).contentEquals(MANIFEST_MAGIC)) throw ProtocolException("Invalid manifest magic")
        val version = headerBuffer.get().toInt() and 0xff
        if (version != PROTOCOL_VERSION) throw ProtocolException("Unsupported manifest version: $version")
        val payloadSize = headerBuffer.getUInt32()
        if (payloadSize > MAX_MANIFEST_BYTES) throw ProtocolException("Manifest payload exceeds 64 MiB")
        return ManifestFrame(input.readExactly(payloadSize.toInt()))
    }

    /** Reads one bounded manifest/chunk request without buffering bytes beyond the frame limit. */
    fun readNextReceiverFrame(input: InputStream): IncomingReceiverFrame {
        val magic = input.readExactly(4)
        if (magic.contentEquals(FINISH_MAGIC)) {
            val rest = input.readExactly(FINISH_REQUEST_SIZE - 4)
            val buffer = ByteBuffer.wrap(rest).order(ByteOrder.BIG_ENDIAN)
            val version = buffer.get().toInt() and 0xff
            if (version != PROTOCOL_VERSION) throw ProtocolException("Unsupported finish version: $version")
            return IncomingFinishFrame(buffer.getUuid())
        }
        if (magic.contentEquals(MANIFEST_MAGIC)) {
            val rest = input.readExactly(MANIFEST_HEADER_SIZE - 4)
            val header = ByteBuffer.wrap(rest).order(ByteOrder.BIG_ENDIAN)
            val version = header.get().toInt() and 0xff
            if (version != PROTOCOL_VERSION) throw ProtocolException("Unsupported manifest version: $version")
            val payloadSize = header.getUInt32()
            if (payloadSize > MAX_MANIFEST_BYTES) throw ProtocolException("Manifest payload exceeds 64 MiB")
            return IncomingManifestFrame(input.readExactly(payloadSize.toInt()))
        }
        if (magic.contentEquals(CHUNK_MAGIC)) {
            val rest = input.readExactly(CHUNK_HEADER_SIZE - 4)
            val header = ByteBuffer.wrap(rest).order(ByteOrder.BIG_ENDIAN)
            val version = header.get().toInt() and 0xff
            if (version != PROTOCOL_VERSION) throw ProtocolException("Unsupported chunk version: $version")
            header.position(33) // chunk_size follows version, UUID, both ID hashes, index and total.
            val payloadSize = header.getUInt32()
            if (payloadSize > MAX_CHUNK_BYTES) throw ProtocolException("Chunk payload exceeds 8 MiB")
            val payload = input.readExactly(payloadSize.toInt())
            return IncomingChunkFrame(magic + rest + payload)
        }
        throw ProtocolException("Unknown receiver frame magic")
    }
}

private const val ACK_STATUS_MIN = 0
private const val ACK_STATUS_MAX = 5

private fun crc32(bytes: ByteArray): Long = CRC32().run {
    update(bytes)
    value
}

private fun ByteBuffer.putUuid(uuid: UUID) {
    putLong(uuid.mostSignificantBits)
    putLong(uuid.leastSignificantBits)
}

private fun ByteBuffer.getUuid(): UUID = UUID(getLong(), getLong())

private fun ByteBuffer.putUInt32(value: Long): ByteBuffer {
    if (value !in 0..MAX_UINT32) throw ProtocolException("Unsigned 32-bit value is out of range")
    putInt(value.toInt())
    return this
}

private fun ByteBuffer.getUInt32(): Long = getInt().toLong() and MAX_UINT32

private fun ByteBuffer.readBytes(size: Int): ByteArray {
    if (size < 0 || remaining() < size) throw ProtocolException("Frame ended before the declared length")
    val bytes = ByteArray(size)
    get(bytes)
    return bytes
}

private fun InputStream.readExactly(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(bytes, offset, size - offset)
        if (read < 0) throw EOFException("Stream ended before the protocol frame was complete")
        if (read == 0) {
            val value = read()
            if (value < 0) throw EOFException("Stream ended before the protocol frame was complete")
            bytes[offset++] = value.toByte()
        } else {
            offset += read
        }
    }
    return bytes
}

private fun requireHashSize(hash: ByteArray) {
    if (hash.size != 4) throw ProtocolException("ACK ID hashes must be 4 bytes")
}
