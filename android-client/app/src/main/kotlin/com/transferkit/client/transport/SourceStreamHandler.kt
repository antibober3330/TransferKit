package com.transferkit.client.transport

import com.transferkit.client.TransferManifestBundle
import com.transferkit.client.protocol.AckKind
import com.transferkit.client.protocol.Acknowledgement
import com.transferkit.client.protocol.MAX_UINT32
import com.transferkit.client.protocol.MAX_CHUNK_BYTES
import com.transferkit.client.protocol.ProtocolCodec
import com.transferkit.client.protocol.RECOMMENDED_CHUNK_BYTES
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Streams a prepared Android source manifest and items over an already-authenticated connection.
 * Each source chunk is sent only after the peer has ACKed the preceding chunk.
 */
class SourceStreamHandler(private val chunkSize: Int = RECOMMENDED_CHUNK_BYTES) {
    init {
        require(chunkSize in 1..MAX_CHUNK_BYTES) { "chunkSize must be between 1 byte and 8 MiB" }
    }

    @Throws(IOException::class)
    fun send(bundle: TransferManifestBundle, input: InputStream, output: OutputStream) {
        val transferId = UUID.fromString(bundle.transferId)
        output.write(ProtocolCodec.encodeManifestFrame(bundle.canonicalBytes))
        output.flush()
        val manifestAck = ProtocolCodec.readAcknowledgement(input, AckKind.MANIFEST)
        if (manifestAck.transferId != transferId || manifestAck.status != ACK_OK) {
            throw IOException("Peer rejected or mismatched the source manifest ACK")
        }

        // Canonical JSON sorts category keys; stream items in the same order the relay reads them.
        bundle.categories.sortedBy { it.categoryId }.forEach { category ->
            category.items.forEach { item ->
                val chunkOutput = AcknowledgedChunkOutputStream(
                    transferId = transferId,
                    categoryId = category.categoryId,
                    itemId = item.id,
                    itemSize = item.sizeBytes,
                    chunkSize = chunkSize,
                    input = input,
                    output = output,
                )
                item.writeAndVerify(chunkOutput)
                chunkOutput.finish()
            }
        }
        output.flush()
    }

    private class AcknowledgedChunkOutputStream(
        private val transferId: UUID,
        private val categoryId: String,
        private val itemId: String,
        private val itemSize: Long,
        private val chunkSize: Int,
        private val input: InputStream,
        private val output: OutputStream,
    ) : OutputStream() {
        private val buffer = ByteArray(chunkSize)
        private val totalChunks: Long = maxOf(
            1L,
            itemSize / chunkSize + if (itemSize % chunkSize == 0L) 0L else 1L,
        )
        private var buffered = 0
        private var bytesWritten = 0L
        private var nextChunkIndex = 0L

        init {
            if (itemSize < 0 || totalChunks > MAX_UINT32) throw IOException("Source item exceeds protocol chunk limits")
        }

        override fun write(value: Int) {
            val single = byteArrayOf(value.toByte())
            write(single, 0, 1)
        }

        override fun write(source: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > source.size - length) throw IndexOutOfBoundsException()
            if (bytesWritten + length > itemSize) throw IOException("Source item exceeded its prepared manifest size")
            var sourceOffset = offset
            var remaining = length
            while (remaining > 0) {
                val copied = minOf(remaining, chunkSize - buffered)
                source.copyInto(buffer, buffered, sourceOffset, sourceOffset + copied)
                buffered += copied
                sourceOffset += copied
                remaining -= copied
                bytesWritten += copied
                if (buffered == chunkSize) sendBufferedChunk()
            }
        }

        fun finish() {
            if (bytesWritten != itemSize) throw IOException("Source item ended before its prepared manifest size")
            if (buffered > 0 || itemSize == 0L) sendBufferedChunk()
            if (nextChunkIndex != totalChunks) throw IOException("Source item did not produce its declared chunk count")
        }

        private fun sendBufferedChunk() {
            if (nextChunkIndex >= totalChunks) throw IOException("Source emitted more chunks than declared")
            val payload = buffer.copyOf(buffered)
            val frame = ProtocolCodec.encodeChunk(
                transferId,
                categoryId,
                itemId,
                nextChunkIndex,
                totalChunks,
                payload,
            )
            output.write(frame)
            output.flush()
            val acknowledgement = ProtocolCodec.readAcknowledgement(input, AckKind.CHUNK)
            val decoded = ProtocolCodec.decodeChunk(frame)
            if (!acknowledgement.matches(decoded)) {
                throw IOException("Peer rejected or mismatched a source chunk ACK")
            }
            nextChunkIndex += 1
            buffered = 0
        }

        private fun Acknowledgement.matches(decoded: com.transferkit.client.protocol.DecodedChunk): Boolean {
            val header = decoded.header
            return status == ACK_OK && transferId == header.transferId &&
                categoryIdHash.contentEquals(ProtocolCodec.idHash(categoryId)) &&
                itemIdHash.contentEquals(ProtocolCodec.idHash(itemId)) &&
                chunkIndex == header.chunkIndex && payloadCrc32 == header.payloadCrc32
        }
    }

    private companion object {
        const val ACK_OK = 0
    }
}
