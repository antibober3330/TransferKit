package com.transferkit.client.restore

import java.util.UUID

/** Opaque in-memory approval. Its string representation deliberately contains no token. */
class RestoreApproval internal constructor(internal val tokenId: String) {
    override fun toString(): String = "RestoreApproval(<redacted>)"
}

/**
 * Issues short-lived, single-use approvals only after the caller reports an explicit user choice.
 * Each approval is restricted to exact item IDs, sizes, and SHA-256 values in one manifest.
 */
class RestoreApprovalGate(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    init {
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    private val lock = Any()
    private val sessions = mutableMapOf<String, ApprovalSession>()

    /**
     * Call only from the positive action of a user-visible restore confirmation dialog.
     * Returns null for cancellation or an empty selection. Invalid or mixed manifests are rejected.
     */
    fun approveAfterUserConfirmation(
        transferId: String,
        manifestSha256: String,
        items: Collection<VerifiedRestoreItem>,
        confirmedByUser: Boolean,
    ): RestoreApproval? {
        if (!confirmedByUser || items.isEmpty()) return null
        require(transferId.isNotBlank()) { "transferId must not be blank" }
        require(SHA256_PATTERN.matches(manifestSha256)) { "manifestSha256 must be lowercase SHA-256" }

        val targets = LinkedHashMap<ItemKey, ItemFingerprint>()
        for (item in items) {
            require(item.transferId == transferId) { "all items must belong to the approved transfer" }
            require(item.manifestSha256 == manifestSha256) { "all items must belong to the approved manifest" }
            require(item.sizeBytes >= 0L && SHA256_PATTERN.matches(item.sha256)) {
                "item size and SHA-256 must be valid"
            }
            val key = ItemKey(item.categoryId, item.itemId)
            require(targets.put(key, ItemFingerprint(item.sizeBytes, item.sha256)) == null) {
                "approved item IDs must be unique within a category"
            }
        }

        val now = monotonicMillis()
        val expiresAt = Math.addExact(now, ttlMillis)
        val tokenId = UUID.randomUUID().toString()
        synchronized(lock) {
            purgeExpired(now)
            sessions[tokenId] = ApprovalSession(transferId, manifestSha256, expiresAt, targets)
        }
        return RestoreApproval(tokenId)
    }

    /** Atomically consumes the item's approval; retries require a new explicit confirmation. */
    fun consume(approval: RestoreApproval?, item: VerifiedRestoreItem): Boolean {
        if (approval == null) return false
        val now = monotonicMillis()
        synchronized(lock) {
            val session = sessions[approval.tokenId] ?: return false
            if (now >= session.expiresAtMillis) {
                sessions.remove(approval.tokenId)
                return false
            }
            if (item.transferId != session.transferId || item.manifestSha256 != session.manifestSha256) {
                return false
            }
            val key = ItemKey(item.categoryId, item.itemId)
            val expected = session.remainingItems[key] ?: return false
            if (expected.sizeBytes != item.sizeBytes || expected.sha256 != item.sha256) return false

            session.remainingItems.remove(key)
            if (session.remainingItems.isEmpty()) sessions.remove(approval.tokenId)
            return true
        }
    }

    fun revoke(approval: RestoreApproval?) {
        if (approval == null) return
        synchronized(lock) { sessions.remove(approval.tokenId) }
    }

    private fun purgeExpired(now: Long) {
        sessions.entries.removeAll { now >= it.value.expiresAtMillis }
    }

    private data class ItemKey(val categoryId: String, val itemId: String)
    private data class ItemFingerprint(val sizeBytes: Long, val sha256: String)
    private data class ApprovalSession(
        val transferId: String,
        val manifestSha256: String,
        val expiresAtMillis: Long,
        val remainingItems: MutableMap<ItemKey, ItemFingerprint>,
    )

    private companion object {
        const val DEFAULT_TTL_MILLIS = 15 * 60 * 1000L
        val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
    }
}
