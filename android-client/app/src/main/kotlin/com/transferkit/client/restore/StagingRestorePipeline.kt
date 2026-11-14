package com.transferkit.client.restore

/** Result of one sequential staging → adapter → destination-verified cleanup step. */
data class StagingRestoreOutcome(
    val result: RestoreItemResult,
    val stagingCleanupPending: Boolean,
)

/**
 * Runs one receiver-owned item at a time. The adapter cannot run unless the staging file still
 * matches its manifest digest, and the staging payload is released only after adapter success.
 */
object StagingRestorePipeline {
    fun restoreOne(
        item: VerifiedRestoreItem,
        restore: (VerifiedRestoreItem) -> RestoreItemResult,
        releaseAfterPostVerification: (VerifiedRestoreItem) -> Boolean,
    ): StagingRestoreOutcome {
        val sourceFailure = StagingPayloadVerifier.verify(item)
        if (sourceFailure != null) return StagingRestoreOutcome(sourceFailure, stagingCleanupPending = false)

        val result = runCatching { restore(item) }.getOrElse {
            RestoreItemResult(item.itemId, RestoreItemStatus.FAILED, "RESTORE_FAILED")
        }
        val cleanupPending = result.status == RestoreItemStatus.RESTORED &&
            !runCatching { releaseAfterPostVerification(item) }.getOrDefault(false)
        return StagingRestoreOutcome(result, stagingCleanupPending = cleanupPending)
    }
}
