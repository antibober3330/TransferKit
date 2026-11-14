package com.transferkit.client.storage

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File
import java.util.UUID

enum class SpaceMeasurementMethod {
    STORAGE_MANAGER,
    STAT_FS_FALLBACK,
    UNAVAILABLE,
}

/** A read-only estimate for one filesystem volume. volumeId is for local matching only. */
data class VolumeSpaceBudget(
    val volumeId: String?,
    val allocatableBytes: Long?,
    val availableBytes: Long?,
    val safetyMarginBytes: Long?,
    val safeAvailableBytes: Long?,
    val method: SpaceMeasurementMethod,
)

/** Staging and destination are kept separate unless the platform proves they share a volume. */
data class TransferStorageSpace(
    val staging: VolumeSpaceBudget,
    val destination: VolumeSpaceBudget,
    val sameVolume: Boolean?,
)

internal interface StorageSpacePlatform {
    fun uuidForPath(path: File): UUID
    fun allocatableBytes(storageUuid: UUID): Long
    fun availableBytes(path: File): Long
}

private class AndroidStorageSpacePlatform(context: Context) : StorageSpacePlatform {
    private val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

    override fun uuidForPath(path: File): UUID =
        requireNotNull(storageManager) { "StorageManager unavailable" }.getUuidForPath(path)

    override fun allocatableBytes(storageUuid: UUID): Long =
        requireNotNull(storageManager) { "StorageManager unavailable" }.getAllocatableBytes(storageUuid)

    override fun availableBytes(path: File): Long = StatFs(path.absolutePath).availableBytes
}

/** Reads StorageManager allocation and StatFs UI estimates without allocating or deleting files. */
class AndroidStorageSpaceProbe internal constructor(
    private val platform: StorageSpacePlatform,
) {
    constructor(context: Context) : this(AndroidStorageSpacePlatform(context.applicationContext))

    fun probe(stagingPath: File, destinationPath: File): TransferStorageSpace {
        val staging = measure(stagingPath)
        val destinationVolumeId = runCatching { platform.uuidForPath(destinationPath).toString() }
            .getOrNull()
        val sameVolume = if (staging.volumeId != null && destinationVolumeId != null) {
            staging.volumeId == destinationVolumeId
        } else {
            null
        }
        val destination = if (sameVolume == true) staging else measure(destinationPath, destinationVolumeId)
        return TransferStorageSpace(staging, destination, sameVolume)
    }

    private fun measure(path: File, knownVolumeId: String? = null): VolumeSpaceBudget {
        val volumeId = knownVolumeId ?: runCatching { platform.uuidForPath(path).toString() }.getOrNull()
        val available = runCatching { platform.availableBytes(path) }
            .getOrNull()
            ?.takeIf { it >= 0L }
            ?: return VolumeSpaceBudget(
                volumeId = volumeId,
                allocatableBytes = null,
                availableBytes = null,
                safetyMarginBytes = null,
                safeAvailableBytes = null,
                method = SpaceMeasurementMethod.UNAVAILABLE,
            )

        val allocatable = volumeId
            ?.let { id -> runCatching { platform.allocatableBytes(UUID.fromString(id)) }.getOrNull() }
            ?.takeIf { it >= 0L }
        if (allocatable != null) {
            val margin = defaultSafetyMargin(allocatable)
            return VolumeSpaceBudget(
                volumeId = volumeId,
                allocatableBytes = allocatable,
                availableBytes = available,
                safetyMarginBytes = margin,
                safeAvailableBytes = (allocatable - margin).coerceAtLeast(0L),
                method = SpaceMeasurementMethod.STORAGE_MANAGER,
            )
        }

        // StatFs is UI/free-space data, not an app allocation promise; keep a larger reserve.
        val margin = maxOf(FALLBACK_MINIMUM_MARGIN_BYTES, available / FALLBACK_MARGIN_DIVISOR)
        return VolumeSpaceBudget(
            volumeId = volumeId,
            allocatableBytes = available,
            availableBytes = available,
            safetyMarginBytes = margin,
            safeAvailableBytes = (available - margin).coerceAtLeast(0L),
            method = SpaceMeasurementMethod.STAT_FS_FALLBACK,
        )
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val DEFAULT_MINIMUM_MARGIN_BYTES = 100L * MIB
        const val DEFAULT_MARGIN_DIVISOR = 20L
        const val FALLBACK_MINIMUM_MARGIN_BYTES = 200L * MIB
        const val FALLBACK_MARGIN_DIVISOR = 10L

        fun defaultSafetyMargin(allocatableBytes: Long): Long =
            maxOf(DEFAULT_MINIMUM_MARGIN_BYTES, allocatableBytes / DEFAULT_MARGIN_DIVISOR)
    }
}
