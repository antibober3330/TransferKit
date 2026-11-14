package com.transferkit.client.transport

import android.os.SystemClock
import com.transferkit.client.receiver.DurableTransferProgress
import java.util.UUID

/** Best-effort UI observer: checkpoint read/notification failures must never abort the data stream. */
internal class DurableProgressReporter(
    private val read: (UUID) -> DurableTransferProgress?,
    private val callback: ((DurableTransferProgress) -> Unit)?,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var lastSuccessfulReadAtMillis = Long.MIN_VALUE

    fun report(transferId: UUID, force: Boolean = false) {
        val notify = callback ?: return
        val now = elapsedRealtime()
        if (!force && lastSuccessfulReadAtMillis != Long.MIN_VALUE &&
            now - lastSuccessfulReadAtMillis < PROGRESS_READ_INTERVAL_MS
        ) return

        val progress = runCatching { read(transferId) }.getOrNull() ?: return
        lastSuccessfulReadAtMillis = now
        runCatching { notify(progress) }
    }

    private companion object {
        const val PROGRESS_READ_INTERVAL_MS = 1_000L
    }
}
