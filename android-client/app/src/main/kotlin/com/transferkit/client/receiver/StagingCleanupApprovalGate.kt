package com.transferkit.client.receiver

import java.util.UUID

/** Opaque, short-lived authorization for deleting one transfer's displayed staging byte count. */
class StagingCleanupApproval internal constructor(internal val tokenId: String) {
    override fun toString(): String = "StagingCleanupApproval(<redacted>)"
}

class StagingCleanupApprovalGate(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    init {
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    private val lock = Any()
    private val pending = mutableMapOf<String, Target>()

    /** Call only from a positive user confirmation after showing this exact byte count. */
    fun approveAfterUserConfirmation(
        transferId: String,
        displayedBytes: Long,
        confirmedByUser: Boolean,
    ): StagingCleanupApproval? {
        if (!confirmedByUser) return null
        require(transferId.isNotBlank() && displayedBytes >= 0L)
        val now = monotonicMillis()
        val tokenId = UUID.randomUUID().toString()
        synchronized(lock) {
            pending.entries.removeAll { now >= it.value.expiresAtMillis }
            pending[tokenId] = Target(transferId, displayedBytes, Math.addExact(now, ttlMillis))
        }
        return StagingCleanupApproval(tokenId)
    }

    /** Always consumes the token, including when the directory changed after the user saw it. */
    fun consume(approval: StagingCleanupApproval?, transferId: String, currentBytes: Long): Boolean {
        if (approval == null) return false
        val now = monotonicMillis()
        synchronized(lock) {
            val target = pending.remove(approval.tokenId) ?: return false
            return now < target.expiresAtMillis && target.transferId == transferId &&
                target.displayedBytes == currentBytes
        }
    }

    fun revoke(approval: StagingCleanupApproval?) {
        if (approval == null) return
        synchronized(lock) { pending.remove(approval.tokenId) }
    }

    private data class Target(val transferId: String, val displayedBytes: Long, val expiresAtMillis: Long)

    private companion object {
        const val DEFAULT_TTL_MILLIS = 2 * 60 * 1000L
    }
}
