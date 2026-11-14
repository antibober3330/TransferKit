package com.transferkit.client.receiver

import java.io.File
import java.nio.file.Files
import java.util.UUID

enum class StagingLeftoverKind {
    ACTIVE_CHECKPOINT,
    RESTORE_PENDING,
    CLEANUP_CANDIDATE,
    ORPHANED_CANDIDATE,
    PROTECTED_UNKNOWN,
    UNSAFE,
}

data class StagingLeftover(
    val transferId: String,
    val transferStatus: String?,
    val kind: StagingLeftoverKind,
    /** Null means the scanner could not safely measure the complete tree. */
    val bytes: Long?,
    val cleanupEligible: Boolean,
    /** These bytes already reduce filesystem available space; never subtract them a second time. */
    val alreadyIncludedInAvailableBytes: Boolean = true,
)

/** Read-only inventory of UUID-named directories below TransferKit's private receiver root. */
internal object StagingLeftoversScanner {
    fun scan(root: File, transferStatuses: Map<String, String>): List<StagingLeftover> {
        if (!root.exists()) return emptyList()
        if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) return emptyList()
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
        val children = root.listFiles()?.sortedBy { it.name } ?: return emptyList()
        return children.mapNotNull { child ->
            val transferId = child.name.takeIf(::isCanonicalUuid) ?: return@mapNotNull null
            if (!child.isDirectory && !Files.isSymbolicLink(child.toPath())) return@mapNotNull null
            val status = transferStatuses[transferId]
            val measured = measureTree(child, canonicalRoot) ?: return@mapNotNull StagingLeftover(
                transferId = transferId,
                transferStatus = status,
                kind = StagingLeftoverKind.UNSAFE,
                bytes = null,
                cleanupEligible = false,
            )
            val kind = when (status) {
                ReceiverDatabase.TRANSFER_RUNNING, ReceiverDatabase.TRANSFER_PAUSED ->
                    StagingLeftoverKind.ACTIVE_CHECKPOINT
                ReceiverDatabase.TRANSFER_COMPLETED -> StagingLeftoverKind.RESTORE_PENDING
                ReceiverDatabase.TRANSFER_ABANDONED -> StagingLeftoverKind.CLEANUP_CANDIDATE
                null -> StagingLeftoverKind.ORPHANED_CANDIDATE
                else -> StagingLeftoverKind.PROTECTED_UNKNOWN
            }
            StagingLeftover(
                transferId = transferId,
                transferStatus = status,
                kind = kind,
                bytes = measured,
                cleanupEligible = kind in setOf(
                    StagingLeftoverKind.CLEANUP_CANDIDATE,
                    StagingLeftoverKind.ORPHANED_CANDIDATE,
                ),
            )
        }
    }

    private fun measureTree(entry: File, canonicalRoot: File): Long? {
        val canonicalEntry = runCatching { entry.canonicalFile }.getOrNull() ?: return null
        if (canonicalEntry.parentFile != canonicalRoot || Files.isSymbolicLink(entry.toPath())) return null
        var total = 0L
        val pending = ArrayDeque<File>()
        pending.add(entry)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (Files.isSymbolicLink(current.toPath())) return null
            val children = current.listFiles()
            if (current.isDirectory) {
                if (children == null) return null
                children.forEach(pending::add)
            } else if (current.isFile) {
                val length = current.length()
                if (length < 0L || length > Long.MAX_VALUE - total) return null
                total += length
            } else {
                return null
            }
        }
        return total
    }

    private fun isCanonicalUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)
}
