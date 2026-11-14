package com.transferkit.client.protocol

import java.io.File
import java.io.ByteArrayInputStream
import java.util.UUID
import com.transferkit.client.transport.UsbAdbAuthProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTest {
    private val vectors by lazy {
        val candidates = listOf(
            File("../../shared/protocol-vectors.json"),
            File("../shared/protocol-vectors.json"),
            File("shared/protocol-vectors.json"),
        )
        val fixture = candidates.firstOrNull(File::isFile)
            ?: error("shared/protocol-vectors.json was not found from the unit-test working directory")
        Json.parseToJsonElement(fixture.readText(Charsets.UTF_8)).jsonObject
    }

    @Test
    fun chunkCodecMatchesPythonVector() {
        val vector = vectors.getValue("chunk").jsonObject
        val frame = ProtocolCodec.encodeChunk(
            UUID.fromString(vector.getValue("transfer_id").jsonPrimitive.content),
            vector.getValue("category_id").jsonPrimitive.content,
            vector.getValue("item_id").jsonPrimitive.content,
            vector.getValue("chunk_index").jsonPrimitive.content.toLong(),
            vector.getValue("total_chunks").jsonPrimitive.content.toLong(),
            vector.getValue("payload_hex").jsonPrimitive.content.hexToBytes(),
        )
        assertEquals(vector.getValue("frame_hex").jsonPrimitive.content, frame.toHex())

        val decoded = ProtocolCodec.decodeChunk(frame)
        assertEquals("hello", decoded.payload.toString(Charsets.UTF_8))
        assertEquals(vector.getValue("crc32_hex").jsonPrimitive.content, decoded.header.payloadCrc32.toString(16).padStart(8, '0'))
    }

    @Test
    fun manifestFramingMatchesPythonVector() {
        val vector = vectors.getValue("manifest_frame").jsonObject
        val frame = ProtocolCodec.encodeManifestFrame(vector.getValue("payload_hex").jsonPrimitive.content.hexToBytes())
        assertEquals(vector.getValue("frame_hex").jsonPrimitive.content, frame.toHex())
        assertArrayEquals(
            vector.getValue("payload_hex").jsonPrimitive.content.hexToBytes(),
            ProtocolCodec.decodeManifestFrame(frame).payload,
        )
    }

    @Test
    fun recoveryControlFrameMatchesPythonVectorAndRejectsMalformedPayloads() {
        val vector = vectors.getValue("recovery_hello_frame").jsonObject
        val payload = vector.getValue("payload_utf8").jsonPrimitive.content.toByteArray(Charsets.UTF_8)
        val frame = RecoveryFrameCodec.encode(RecoveryFrameKind.HELLO, payload)
        assertEquals(vector.getValue("frame_hex").jsonPrimitive.content, frame.toHex())

        val decoded = RecoveryFrameCodec.decode(frame, RecoveryFrameKind.HELLO)
        assertEquals(RecoveryFrameKind.HELLO, decoded.kind)
        assertArrayEquals(payload, decoded.payload)
        val streamed = RecoveryFrameCodec.read(ByteArrayInputStream(frame), RecoveryFrameKind.HELLO)
        assertArrayEquals(frame, RecoveryFrameCodec.encode(streamed.kind, streamed.payload))

        assertThrows(ProtocolException::class.java) {
            RecoveryFrameCodec.decode(frame, RecoveryFrameKind.PROOF)
        }
        assertThrows(ProtocolException::class.java) {
            RecoveryFrameCodec.decode("TKRH\u0001\u0000\u0000\u0000\u0003{ }".toByteArray(Charsets.ISO_8859_1))
        }
        assertThrows(ProtocolException::class.java) {
            RecoveryFrameCodec.decode("TKRH\u0001\u0000\u0000\u0000\u0002[]".toByteArray(Charsets.ISO_8859_1))
        }
    }

    @Test
    fun recoveryCanonicalJsonUsesUnicodeCodePointOrdering() {
        val pythonCanonicalPayload = "{\"\uE000\":1,\"😀\":2}".toByteArray(Charsets.UTF_8)
        val frame = RecoveryFrameCodec.encode(RecoveryFrameKind.HELLO, pythonCanonicalPayload)
        assertArrayEquals(pythonCanonicalPayload, RecoveryFrameCodec.decode(frame).payload)

        val utf16CodeUnitOrder = "{\"😀\":2,\"\uE000\":1}".toByteArray(Charsets.UTF_8)
        assertThrows(ProtocolException::class.java) {
            RecoveryFrameCodec.encode(RecoveryFrameKind.HELLO, utf16CodeUnitOrder)
        }
    }

    @Test
    fun rejectsManifestItemCountAboveSharedLimit() {
        validateManifestItemCount(MAX_MANIFEST_ITEMS)
        assertThrows(IllegalArgumentException::class.java) {
            validateManifestItemCount(MAX_MANIFEST_ITEMS + 1)
        }
    }

    @Test
    fun sourceIdentityMatchesPythonVector() {
        val vector = vectors.getValue("source_identity").jsonObject
        val transferId = UUID.fromString(vector.getValue("transfer_id").jsonPrimitive.content)
        val frame = ProtocolCodec.encodeSourceIdentity(transferId)
        assertEquals(vector.getValue("frame_hex").jsonPrimitive.content, frame.toHex())
        assertEquals(transferId, ProtocolCodec.readSourceIdentity(ByteArrayInputStream(frame)).transferId)
    }

    @Test
    fun acknowledgementsMatchPythonVectors() {
        listOf("chunk_ack", "manifest_ack").forEach { key ->
            val vector = vectors.getValue(key).jsonObject
            val kind = if (vector.getValue("kind").jsonPrimitive.content == "0") AckKind.MANIFEST else AckKind.CHUNK
            val ack = Acknowledgement(
                kind = kind,
                transferId = UUID.fromString(vector.getValue("transfer_id").jsonPrimitive.content),
                categoryIdHash = vector.getValue("category_id_hash_hex").jsonPrimitive.content.hexToBytes(),
                itemIdHash = vector.getValue("item_id_hash_hex").jsonPrimitive.content.hexToBytes(),
                chunkIndex = vector.getValue("chunk_index").jsonPrimitive.content.toLong(),
                payloadCrc32 = vector.getValue("payload_crc32_hex").jsonPrimitive.content.toLong(16),
                status = vector.getValue("status").jsonPrimitive.content.toInt(),
            )
            val frame = ProtocolCodec.encodeAcknowledgement(ack)
            assertEquals(vector.getValue("frame_hex").jsonPrimitive.content, frame.toHex())
            assertEquals(kind, ProtocolCodec.decodeAcknowledgement(frame, kind).kind)
        }
    }

    @Test
    fun spaceFramesAndItemResultAckMatchPythonVectors() {
        val reportVector = vectors.getValue("space_report_frame").jsonObject
        val report = SpaceReportContract(
            version = "1.0",
            protocolVersion = "2",
            transferId = "00000000-0000-4000-8000-000000000001",
            availableBytes = null,
            allocatableBytes = null,
            safetyMarginBytes = null,
            safeAvailableBytes = null,
            stagingLeftoverBytes = 0,
            totalManifestBytes = 0,
            largestItemBytes = 0,
            batchPeakBytes = null,
            streamingPeakBytes = null,
            estimatesComplete = false,
            unknownItemIds = emptyList(),
            strategy = SpaceStrategy.INSUFFICIENT,
            decisionRequired = true,
            keptItemIds = emptyList(),
            skippedItemIds = emptyList(),
        )
        val reportFrame = SpaceControlFrameCodec.encode(report)
        assertEquals(reportVector.getValue("frame_hex").jsonPrimitive.content, reportFrame.toHex())
        assertEquals(report, SpaceControlFrameCodec.decodeReport(reportFrame))
        assertEquals(report, SpaceControlFrameCodec.readReport(ByteArrayInputStream(reportFrame)))

        val decisionVector = vectors.getValue("space_decision_frame").jsonObject
        val decision = SpaceDecisionContract(
            version = "1.0",
            protocolVersion = "2",
            transferId = "00000000-0000-4000-8000-000000000001",
            strategy = SpaceStrategy.INSUFFICIENT,
            userConfirmed = false,
            keptItemIds = emptyList(),
            skippedItemIds = emptyList(),
        )
        val decisionFrame = SpaceControlFrameCodec.encode(decision)
        assertEquals(decisionVector.getValue("frame_hex").jsonPrimitive.content, decisionFrame.toHex())
        assertEquals(decision, SpaceControlFrameCodec.decodeDecision(decisionFrame))
        assertEquals(decision, SpaceControlFrameCodec.readDecision(ByteArrayInputStream(decisionFrame)))

        val itemVector = vectors.getValue("item_result_ack").jsonObject
        val itemAck = Acknowledgement(
            kind = AckKind.ITEM_RESULT,
            transferId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
            categoryIdHash = "84a6dae4".hexToBytes(),
            itemIdHash = "4ce1c689".hexToBytes(),
            chunkIndex = ITEM_RESULT_ACK_SENTINEL,
            payloadCrc32 = 0,
            status = ITEM_RESULT_RESTORED_POST_VERIFIED,
        )
        val itemFrame = ProtocolCodec.encodeAcknowledgement(itemAck)
        assertEquals(itemVector.getValue("frame_hex").jsonPrimitive.content, itemFrame.toHex())
        val decodedItemAck = ProtocolCodec.decodeAcknowledgement(itemFrame, AckKind.ITEM_RESULT)
        assertEquals(itemAck.kind, decodedItemAck.kind)
        assertEquals(itemAck.transferId, decodedItemAck.transferId)
        assertArrayEquals(itemAck.categoryIdHash, decodedItemAck.categoryIdHash)
        assertArrayEquals(itemAck.itemIdHash, decodedItemAck.itemIdHash)
        assertEquals(itemAck.chunkIndex, decodedItemAck.chunkIndex)
        assertEquals(itemAck.payloadCrc32, decodedItemAck.payloadCrc32)
        assertEquals(itemAck.status, decodedItemAck.status)
    }

    @Test
    fun spaceFramesRejectWrongVersionMagicAndNonCanonicalJson() {
        val valid = SpaceControlFrameCodec.encode(
            SpaceDecisionContract(
                version = "1.0",
                protocolVersion = "2",
                transferId = "00000000-0000-4000-8000-000000000001",
                strategy = SpaceStrategy.INSUFFICIENT,
                userConfirmed = false,
                keptItemIds = emptyList(),
                skippedItemIds = emptyList(),
            ),
        )
        assertThrows(ProtocolException::class.java) {
            SpaceControlFrameCodec.decode(valid, SpaceControlKind.REPORT)
        }
        val wrongVersion = valid.copyOf().also { it[4] = 2 }
        assertThrows(ProtocolException::class.java) { SpaceControlFrameCodec.decode(wrongVersion) }

        val nonCanonicalPayload = "{ \"version\":\"1.0\" }".toByteArray()
        val nonCanonical = java.nio.ByteBuffer.allocate(SPACE_CONTROL_HEADER_SIZE + nonCanonicalPayload.size)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
            .put("TKSD".toByteArray())
            .put(SPACE_CONTROL_FRAME_VERSION.toByte())
            .putInt(nonCanonicalPayload.size)
            .put(nonCanonicalPayload)
            .array()
        assertThrows(ProtocolException::class.java) { SpaceControlFrameCodec.decode(nonCanonical) }
    }

    @Test
    fun itemResultAckRejectsV1AndBadSentinel() {
        val ack = Acknowledgement(
            kind = AckKind.ITEM_RESULT,
            transferId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
            categoryIdHash = "84a6dae4".hexToBytes(),
            itemIdHash = "4ce1c689".hexToBytes(),
            chunkIndex = ITEM_RESULT_ACK_SENTINEL,
            payloadCrc32 = 0,
            status = ITEM_RESULT_RESTORE_FAILED,
        )
        val valid = ProtocolCodec.encodeAcknowledgement(ack)
        assertThrows(ProtocolException::class.java) {
            ProtocolCodec.decodeAcknowledgement(valid.copyOf().also { it[4] = 1 })
        }
        assertThrows(ProtocolException::class.java) {
            ProtocolCodec.decodeAcknowledgement(valid.copyOf().also { it[30] = 0 })
        }
        assertThrows(ProtocolException::class.java) {
            ProtocolCodec.encodeAcknowledgement(ack.copy(chunkIndex = 0))
        }
    }

    @Test
    fun usbAdbAuthenticationAndFinishFramesMatchSharedVectors() {
        val vector = vectors.getValue("usb_adb_auth").jsonObject
        val transferId = UUID.fromString(vector.getValue("transfer_id").jsonPrimitive.content)
        val token = vector.getValue("token_hex").jsonPrimitive.content.hexToBytes()
        val nonce = vector.getValue("nonce_hex").jsonPrimitive.content.hexToBytes()
        assertEquals(
            vector.getValue("offer_hex").jsonPrimitive.content,
            UsbAdbAuthProtocol.encodeOffer(transferId, token).toHex(),
        )
        assertEquals(
            vector.getValue("offer_ack_hex").jsonPrimitive.content,
            UsbAdbAuthProtocol.encodeOfferAck(transferId, 0).toHex(),
        )
        assertEquals(
            vector.getValue("challenge_hex").jsonPrimitive.content,
            UsbAdbAuthProtocol.encodeChallenge(transferId, nonce).toHex(),
        )
        assertEquals(
            vector.getValue("proof_hex").jsonPrimitive.content,
            UsbAdbAuthProtocol.encodeProof(transferId, token, nonce).toHex(),
        )
        assertEquals(
            vector.getValue("auth_result_hex").jsonPrimitive.content,
            UsbAdbAuthProtocol.encodeAuthResult(transferId, 0).toHex(),
        )

        val finish = ProtocolCodec.encodeFinishRequest(transferId)
        assertEquals(vector.getValue("finish_request_hex").jsonPrimitive.content, finish.toHex())
        assertEquals(transferId, (ProtocolCodec.readNextReceiverFrame(ByteArrayInputStream(finish)) as IncomingFinishFrame).transferId)

        val receiptPayload = "{\"synthetic\":true}".toByteArray()
        assertEquals(
            vector.getValue("receipt_response_hex").jsonPrimitive.content,
            ProtocolCodec.encodeReceiptResponse(transferId, 0, "{}".toByteArray()).toHex(),
        )
        val receiptResponse = ProtocolCodec.encodeReceiptResponse(transferId, 0, receiptPayload)
        val decodedReceipt = ProtocolCodec.readReceiptResponse(ByteArrayInputStream(receiptResponse))
        assertEquals(transferId, decodedReceipt.transferId)
        assertEquals(0, decodedReceipt.status)
        assertArrayEquals(receiptPayload, decodedReceipt.payload)
        assertThrows(ProtocolException::class.java) {
            ProtocolCodec.encodeReceiptResponse(transferId, 1, receiptPayload)
        }
    }

    @Test
    fun corruptedChunkPayloadAndUnexpectedAckKindAreRejected() {
        val transferId = UUID.fromString("00000000-0000-4000-8000-000000000121")
        val frame = ProtocolCodec.encodeChunk(transferId, "photos", "photo-1", 0, 1, "hello".toByteArray())
        frame[frame.lastIndex] = (frame.last().toInt() xor 0x01).toByte()
        assertThrows(ProtocolException::class.java) { ProtocolCodec.decodeChunk(frame) }

        val manifestAck = Acknowledgement(
            kind = AckKind.MANIFEST,
            transferId = transferId,
            categoryIdHash = ByteArray(4),
            itemIdHash = ByteArray(4),
            chunkIndex = 0,
            payloadCrc32 = 0,
            status = 0,
        )
        val encoded = ProtocolCodec.encodeAcknowledgement(manifestAck)
        assertThrows(ProtocolException::class.java) {
            ProtocolCodec.decodeAcknowledgement(encoded, AckKind.CHUNK)
        }
    }
}

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
