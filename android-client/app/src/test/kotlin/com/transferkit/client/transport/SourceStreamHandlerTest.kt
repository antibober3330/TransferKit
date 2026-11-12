package com.transferkit.client.transport

import com.transferkit.client.DeviceInfo
import com.transferkit.client.ManifestBuilder
import com.transferkit.client.ManifestCategorySource
import com.transferkit.client.ManifestItemSource
import com.transferkit.client.protocol.AckKind
import com.transferkit.client.protocol.Acknowledgement
import com.transferkit.client.protocol.IncomingChunkFrame
import com.transferkit.client.protocol.IncomingManifestFrame
import com.transferkit.client.protocol.ProtocolCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceStreamHandlerTest {
    private val device = DeviceInfo(
        manufacturer = "Fixture",
        model = "SourceStreamTest",
        sdk = 34,
        fingerprint = "fixture/source-stream-test",
        androidRelease = "14",
        totalMemoryBytes = 4L * 1024 * 1024 * 1024,
        availableMemoryBytes = 2L * 1024 * 1024 * 1024,
        dataStorageTotalBytes = 64L * 1024 * 1024 * 1024,
        dataStorageAvailableBytes = 32L * 1024 * 1024 * 1024,
    )

    @Test
    fun sendsCanonicalManifestAndSequentialChunksOnlyAfterMatchingAcks() {
        val cache = Files.createTempDirectory("tk-source-stream-").toFile()
        val payload = "abcdefghij".toByteArray()
        val bundle = ManifestBuilder(cache).build(
            device,
            listOf(
                ManifestCategorySource(
                    "photos", "FixtureProvider", "fixture",
                    listOf(ManifestItemSource("photo-1", payload.size.toLong(), emptyMap()) { it.write(payload) }),
                ),
            ),
        )
        try {
            val acknowledgements = acknowledgementsFor(bundle, payload, 4, ACK_OK)
            val output = ByteArrayOutputStream()
            SourceStreamHandler(4).send(bundle, ByteArrayInputStream(acknowledgements), output)

            val sent = ByteArrayInputStream(output.toByteArray())
            val manifestFrame = ProtocolCodec.readNextReceiverFrame(sent) as IncomingManifestFrame
            assertArrayEquals(bundle.canonicalBytes, manifestFrame.payload)
            val chunks = mutableListOf<ByteArray>()
            var expectedIndex = 0L
            while (sent.available() > 0) {
                val frame = ProtocolCodec.readNextReceiverFrame(sent) as IncomingChunkFrame
                val decoded = ProtocolCodec.decodeChunk(frame.encodedFrame)
                assertEquals(expectedIndex++, decoded.header.chunkIndex)
                chunks += decoded.payload
            }
            assertArrayEquals(payload, chunks.fold(ByteArray(0)) { all, next -> all + next })
            assertEquals(3L, expectedIndex)
        } finally {
            bundle.close()
            cache.deleteRecursively()
        }
    }

    @Test
    fun stopsWhenPeerRejectsAChunk() {
        val cache = Files.createTempDirectory("tk-source-reject-").toFile()
        val payload = "abcdefgh".toByteArray()
        val bundle = ManifestBuilder(cache).build(
            device,
            listOf(
                ManifestCategorySource(
                    "photos", "FixtureProvider", "fixture",
                    listOf(ManifestItemSource("photo-1", payload.size.toLong(), emptyMap()) { it.write(payload) }),
                ),
            ),
        )
        try {
            val acknowledgements = acknowledgementsFor(bundle, payload, 4, ACK_REJECTED)
            val output = ByteArrayOutputStream()
            assertThrows(IOException::class.java) {
                SourceStreamHandler(4).send(bundle, ByteArrayInputStream(acknowledgements), output)
            }
            val sent = ByteArrayInputStream(output.toByteArray())
            ProtocolCodec.readNextReceiverFrame(sent) // manifest
            val firstChunk = ProtocolCodec.readNextReceiverFrame(sent) as IncomingChunkFrame
            assertEquals(0L, ProtocolCodec.decodeChunk(firstChunk.encodedFrame).header.chunkIndex)
            assertEquals(0, sent.available())
        } finally {
            bundle.close()
            cache.deleteRecursively()
        }
    }

    @Test
    fun emitsOneEmptyChunkForZeroByteItem() {
        val cache = Files.createTempDirectory("tk-source-empty-").toFile()
        val bundle = ManifestBuilder(cache).build(
            device,
            listOf(
                ManifestCategorySource(
                    "documents", "FixtureProvider", "fixture",
                    listOf(ManifestItemSource("empty", 0, emptyMap()) { }),
                ),
            ),
        )
        try {
            val empty = byteArrayOf()
            val acknowledgements = acknowledgementsFor(bundle, empty, 4, ACK_OK)
            val output = ByteArrayOutputStream()
            SourceStreamHandler(4).send(bundle, ByteArrayInputStream(acknowledgements), output)
            val sent = ByteArrayInputStream(output.toByteArray())
            ProtocolCodec.readNextReceiverFrame(sent)
            val frame = ProtocolCodec.readNextReceiverFrame(sent) as IncomingChunkFrame
            val decoded = ProtocolCodec.decodeChunk(frame.encodedFrame)
            assertEquals(0, decoded.payload.size)
            assertEquals(1L, decoded.header.totalChunks)
        } finally {
            bundle.close()
            cache.deleteRecursively()
        }
    }

    private fun acknowledgementsFor(bundle: com.transferkit.client.TransferManifestBundle, payload: ByteArray, chunkSize: Int, chunkStatus: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val transferId = UUID.fromString(bundle.transferId)
        output.write(
            ProtocolCodec.encodeAcknowledgement(
                Acknowledgement(AckKind.MANIFEST, transferId, ByteArray(4), ByteArray(4), 0, 0, ACK_OK),
            ),
        )
        val categoryId = bundle.categories.single().categoryId
        val itemId = bundle.categories.single().items.single().id
        val totalChunks = maxOf(1, (payload.size + chunkSize - 1) / chunkSize)
        repeat(totalChunks) { index ->
            val start = index * chunkSize
            val chunkPayload = payload.copyOfRange(start, minOf(payload.size, start + chunkSize))
            val decoded = ProtocolCodec.decodeChunk(
                ProtocolCodec.encodeChunk(transferId, categoryId, itemId, index.toLong(), totalChunks.toLong(), chunkPayload),
            )
            output.write(
                ProtocolCodec.encodeAcknowledgement(
                    Acknowledgement(
                        AckKind.CHUNK,
                        transferId,
                        ProtocolCodec.idHash(categoryId),
                        ProtocolCodec.idHash(itemId),
                        index.toLong(),
                        decoded.header.payloadCrc32,
                        chunkStatus,
                    ),
                ),
            )
        }
        return output.toByteArray()
    }

    private companion object {
        const val ACK_OK = 0
        const val ACK_REJECTED = 1
    }
}
