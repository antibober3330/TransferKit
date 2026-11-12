package com.transferkit.client.restore

import java.util.UUID

/** One-time user approval for exactly one verified APK item. */
class ApkInstallApproval internal constructor(internal val tokenId: String) {
    override fun toString(): String = "ApkInstallApproval(<redacted>)"
}

/** Keeps APK consent process-local, short-lived, single-use, and bound to exact manifest bytes. */
class ApkInstallApprovalGate(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    init {
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    private val lock = Any()
    private val approvals = mutableMapOf<String, Target>()

    /** Invoke only from the positive action of the per-APK confirmation dialog. */
    fun approveAfterUserConfirmation(item: VerifiedRestoreItem, confirmedByUser: Boolean): ApkInstallApproval? {
        if (!confirmedByUser) return null
        require(item.categoryId == "apks") { "Only APK items can receive install approval" }
        require(item.transferId.isNotBlank() && SHA256.matches(item.manifestSha256))
        require(item.itemId.isNotBlank() && item.sizeBytes >= 0L && SHA256.matches(item.sha256))
        val now = monotonicMillis()
        val expiresAt = Math.addExact(now, ttlMillis)
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            approvals.entries.removeAll { now >= it.value.expiresAtMillis }
            approvals[id] = Target(
                transferId = item.transferId,
                manifestSha256 = item.manifestSha256,
                itemId = item.itemId,
                sizeBytes = item.sizeBytes,
                sha256 = item.sha256,
                expiresAtMillis = expiresAt,
            )
        }
        return ApkInstallApproval(id)
    }

    /** Consume consent before creating any PackageInstaller session. */
    fun consume(approval: ApkInstallApproval?, item: VerifiedRestoreItem): Boolean {
        if (approval == null || item.categoryId != "apks") return false
        val now = monotonicMillis()
        synchronized(lock) {
            val target = approvals[approval.tokenId] ?: return false
            if (now >= target.expiresAtMillis) {
                approvals.remove(approval.tokenId)
                return false
            }
            if (target.transferId != item.transferId || target.manifestSha256 != item.manifestSha256 ||
                target.itemId != item.itemId || target.sizeBytes != item.sizeBytes || target.sha256 != item.sha256
            ) return false
            approvals.remove(approval.tokenId)
            return true
        }
    }

    fun revoke(approval: ApkInstallApproval?) {
        if (approval == null) return
        synchronized(lock) { approvals.remove(approval.tokenId) }
    }

    private data class Target(
        val transferId: String,
        val manifestSha256: String,
        val itemId: String,
        val sizeBytes: Long,
        val sha256: String,
        val expiresAtMillis: Long,
    )

    private companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
        val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}
