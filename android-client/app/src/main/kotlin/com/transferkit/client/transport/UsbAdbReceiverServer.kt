package com.transferkit.client.transport

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import com.transferkit.client.receiver.AndroidTransferReceiver
import com.transferkit.client.receiver.DurableTransferProgress
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Local abstract-socket receiver exposed only through an explicitly forwarded ADB session. */
class UsbAdbReceiverServer(
    private val receiver: AndroidTransferReceiver,
    private val onDurableProgress: ((DurableTransferProgress) -> Unit)? = null,
) : Closeable {
    private val sessions = PendingSessionStore()
    private val workers: ExecutorService = Executors.newFixedThreadPool(2)
    @Volatile private var closed = false
    private val controlServer: LocalServerSocket
    private val dataServer: LocalServerSocket

    init {
        controlServer = LocalServerSocket(CONTROL_SOCKET)
        dataServer = try {
            LocalServerSocket(DATA_SOCKET)
        } catch (failure: IOException) {
            controlServer.close()
            throw failure
        }
    }

    fun start() {
        startAcceptLoop(controlServer, ::handleControl)
        startAcceptLoop(dataServer, ::handleData)
    }

    private fun startAcceptLoop(server: LocalServerSocket, handler: (LocalSocket) -> Unit) {
        Thread({
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    if (!closed) continue else return@Thread
                }
                if (!isAuthorizedAdbPeer(socket)) {
                    socket.close()
                    continue
                }
                try {
                    workers.execute {
                        socket.use { accepted ->
                            try {
                                accepted.soTimeout = SOCKET_TIMEOUT_MS
                                handler(accepted)
                            } catch (_: Exception) {
                                // Never log protocol payloads, credentials, tokens, or user data.
                            }
                        }
                    }
                } catch (_: RuntimeException) {
                    socket.close()
                }
            }
        }, "transferkit-adb-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun isAuthorizedAdbPeer(socket: LocalSocket): Boolean = try {
        socket.peerCredentials.uid == ROOT_UID || socket.peerCredentials.uid == SHELL_UID
    } catch (_: Exception) {
        false
    }

    private fun handleControl(socket: LocalSocket) {
        val offer = UsbAdbAuthProtocol.decodeOffer(readExactly(socket, UsbAdbAuthProtocol.OFFER_SIZE))
        val accepted = sessions.offer(offer.transferId, offer.token)
        offer.token.fill(0)
        socket.outputStream.write(UsbAdbAuthProtocol.encodeOfferAck(offer.transferId, if (accepted) 0 else 1))
        socket.outputStream.flush()
    }

    private fun handleData(socket: LocalSocket) {
        val pending = sessions.awaitPending(SESSION_TTL_MS) ?: return
        val nonce = ByteArray(UsbAdbAuthProtocol.NONCE_SIZE).also(SecureRandom()::nextBytes)
        socket.outputStream.write(UsbAdbAuthProtocol.encodeChallenge(pending.transferId, nonce))
        socket.outputStream.flush()
        val proof = UsbAdbAuthProtocol.decodeProof(readExactly(socket, UsbAdbAuthProtocol.PROOF_SIZE))
        val expected = UsbAdbAuthProtocol.proof(pending.transferId, pending.token, nonce)
        val authenticated = UsbAdbAuthProtocol.constantTimeEquals(expected, proof) &&
            sessions.consumeAfterAuthentication(pending, SystemClock.elapsedRealtime())
        expected.fill(0)
        proof.fill(0)
        nonce.fill(0)
        socket.outputStream.write(UsbAdbAuthProtocol.encodeAuthResult(pending.transferId, if (authenticated) 0 else 1))
        socket.outputStream.flush()
        if (authenticated) {
            ReceiverStreamHandler(receiver, onDurableProgress)
                .serve(socket.inputStream, socket.outputStream, pending.transferId)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        sessions.clear()
        // Closing a LocalServerSocket from another thread does not reliably unblock accept().
        // A short local connection wakes each accept loop; its app UID is rejected by the ADB peer check.
        wakeAccept(CONTROL_SOCKET)
        wakeAccept(DATA_SOCKET)
        runCatching { controlServer.close() }
        runCatching { dataServer.close() }
        workers.shutdownNow()
    }

    private fun wakeAccept(socketName: String) {
        LocalSocket().use { wake ->
            runCatching { wake.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)) }
        }
    }

    private data class PendingSession(val transferId: UUID, val token: ByteArray, val expiresAt: Long)

    private class PendingSessionStore {
        private val lock = ReentrantLock()
        private val available = lock.newCondition()
        private var pending: PendingSession? = null

        fun offer(transferId: UUID, token: ByteArray): Boolean = lock.withLock {
            val now = SystemClock.elapsedRealtime()
            pending?.takeIf { it.expiresAt <= now }?.token?.fill(0)
            pending = pending?.takeIf { it.expiresAt > now }
            pending?.let { current ->
                if (current.transferId != transferId) return@withLock false
                if (current.token.contentEquals(token)) return@withLock true
                current.token.fill(0)
                pending = PendingSession(transferId, token.copyOf(), now + SESSION_TTL_MS)
                available.signalAll()
                return@withLock true
            }
            pending = PendingSession(transferId, token.copyOf(), now + SESSION_TTL_MS)
            available.signalAll()
            true
        }

        fun awaitPending(timeoutMs: Long): PendingSession? = lock.withLock {
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!Thread.currentThread().isInterrupted) {
                pending?.let { session ->
                    if (session.expiresAt > SystemClock.elapsedRealtime()) return@withLock session
                    session.token.fill(0)
                    pending = null
                }
                if (remainingNanos <= 0) return@withLock null
                remainingNanos = available.awaitNanos(remainingNanos)
            }
            null
        }

        fun consumeAfterAuthentication(session: PendingSession, now: Long): Boolean = lock.withLock {
            if (pending !== session || session.expiresAt <= now) return@withLock false
            pending = null
            session.token.fill(0)
            true
        }

        fun clear() = lock.withLock {
            pending?.token?.fill(0)
            pending = null
            available.signalAll()
        }
    }

    private fun readExactly(socket: LocalSocket, size: Int): ByteArray {
        val output = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = socket.inputStream.read(output, offset, size - offset)
            if (count < 0) throw EOFException("ADB socket ended during a bounded frame")
            if (count > 0) offset += count
        }
        return output
    }

    companion object {
        const val CONTROL_SOCKET = "transferkit-control-v1"
        const val DATA_SOCKET = "transferkit-v1"
        const val SESSION_TTL_MS = 5 * 60 * 1000L
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val ROOT_UID = 0
        private const val SHELL_UID = 2000
    }
}
