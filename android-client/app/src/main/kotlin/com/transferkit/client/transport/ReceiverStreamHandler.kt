package com.transferkit.client.transport

import com.transferkit.client.protocol.Acknowledgement
import com.transferkit.client.protocol.AckKind
import com.transferkit.client.protocol.IncomingChunkFrame
import com.transferkit.client.protocol.IncomingFinishFrame
import com.transferkit.client.protocol.IncomingManifestFrame
import com.transferkit.client.protocol.ProtocolCodec
import com.transferkit.client.receiver.AndroidTransferReceiver
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Transport-neutral adapter from bounded TKMF/TKCH frames to the durable receiver. It owns neither
 * a socket nor pairing/authentication; callers must provide an authenticated transport session.
 */
class ReceiverStreamHandler(private val receiver: AndroidTransferReceiver) {
    /** Reads and responds to frames until EOF or the receiver rejects a frame. */
    fun serve(input: InputStream, output: OutputStream, authenticatedTransferId: UUID? = null) {
        var manifestAccepted = false
        while (true) {
            val frame = try {
                ProtocolCodec.readNextReceiverFrame(input)
            } catch (_: EOFException) {
                return
            }
            when (frame) {
                is IncomingManifestFrame -> {
                    val acknowledgement = receiver.acceptManifest(frame.payload, authenticatedTransferId)
                    manifestAccepted = acknowledgement.status == ACK_OK
                    output.write(ProtocolCodec.encodeAcknowledgement(acknowledgement))
                    output.flush()
                    if (acknowledgement.status != ACK_OK) return
                }
                is IncomingChunkFrame -> {
                    val decoded = runCatching { ProtocolCodec.decodeChunk(frame.encodedFrame) }.getOrNull()
                    val acknowledgement: Acknowledgement = when {
                        !manifestAccepted -> rejectedChunk(decoded, ACK_TRANSFER_MISMATCH)
                        authenticatedTransferId != null && decoded?.header?.transferId != authenticatedTransferId ->
                            rejectedChunk(decoded, ACK_TRANSFER_MISMATCH)
                        else -> receiver.acceptChunk(frame.encodedFrame)
                    }
                    output.write(ProtocolCodec.encodeAcknowledgement(acknowledgement))
                    output.flush()
                    if (acknowledgement.status != ACK_OK) return
                }
                is IncomingFinishFrame -> {
                    val response = when {
                        authenticatedTransferId == null || frame.transferId != authenticatedTransferId || !manifestAccepted ->
                            ProtocolCodec.encodeReceiptResponse(frame.transferId, RECEIPT_TRANSFER_MISMATCH)
                        else -> {
                            val receipt = runCatching { receiver.buildFinalReceipt(frame.transferId) }
                            when {
                                receipt.isSuccess -> ProtocolCodec.encodeReceiptResponse(
                                    frame.transferId,
                                    RECEIPT_OK,
                                    receipt.getOrThrow(),
                                )
                                receipt.exceptionOrNull() is IllegalArgumentException ->
                                    ProtocolCodec.encodeReceiptResponse(frame.transferId, RECEIPT_INCOMPLETE)
                                else -> ProtocolCodec.encodeReceiptResponse(frame.transferId, RECEIPT_FAILED)
                            }
                        }
                    }
                    output.write(response)
                    output.flush()
                    return
                }
            }
        }
    }

    private fun rejectedChunk(decoded: com.transferkit.client.protocol.DecodedChunk?, status: Int): Acknowledgement =
        if (decoded == null) {
            Acknowledgement(AckKind.CHUNK, UUID(0L, 0L), ByteArray(4), ByteArray(4), 0L, 0L, status)
        } else {
            Acknowledgement(
                AckKind.CHUNK,
                decoded.header.transferId,
                decoded.header.categoryIdHash,
                decoded.header.itemIdHash,
                decoded.header.chunkIndex,
                decoded.header.payloadCrc32,
                status,
            )
        }

    private companion object {
        const val ACK_OK = 0
        const val ACK_TRANSFER_MISMATCH = 5
        const val RECEIPT_OK = 0
        const val RECEIPT_INCOMPLETE = 1
        const val RECEIPT_TRANSFER_MISMATCH = 2
        const val RECEIPT_FAILED = 3
    }
}
