package com.transferkit.client.protocol

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SpaceStrategy(val wireValue: String) {
    @SerialName("batch") BATCH("batch"),
    @SerialName("streaming") STREAMING("streaming"),
    @SerialName("drop-largest") DROP_LARGEST("drop-largest"),
    @SerialName("insufficient") INSUFFICIENT("insufficient"),
}

@Serializable
data class SpaceReportContract(
    val version: String,
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("transfer_id") val transferId: String,
    @SerialName("available_bytes") val availableBytes: Long?,
    @SerialName("allocatable_bytes") val allocatableBytes: Long?,
    @SerialName("safety_margin_bytes") val safetyMarginBytes: Long?,
    @SerialName("safe_available_bytes") val safeAvailableBytes: Long?,
    @SerialName("staging_leftover_bytes") val stagingLeftoverBytes: Long,
    @SerialName("total_manifest_bytes") val totalManifestBytes: Long,
    @SerialName("largest_item_bytes") val largestItemBytes: Long,
    @SerialName("batch_peak_bytes") val batchPeakBytes: Long?,
    @SerialName("streaming_peak_bytes") val streamingPeakBytes: Long?,
    @SerialName("estimates_complete") val estimatesComplete: Boolean,
    @SerialName("unknown_item_ids") val unknownItemIds: List<String>,
    val strategy: SpaceStrategy,
    @SerialName("decision_required") val decisionRequired: Boolean,
    @SerialName("kept_item_ids") val keptItemIds: List<String>,
    @SerialName("skipped_item_ids") val skippedItemIds: List<String>,
) {
    fun validate() {
        require(version == SPACE_REPORT_VERSION && protocolVersion == SPACE_PROTOCOL_VERSION) {
            "Unsupported SpaceReport version"
        }
        validateTransferId(transferId)
        listOfNotNull(availableBytes, allocatableBytes, safetyMarginBytes, safeAvailableBytes,
            batchPeakBytes, streamingPeakBytes).forEach { require(it in 0..MAX_SAFE_JSON_INTEGER) }
        listOf(stagingLeftoverBytes, totalManifestBytes, largestItemBytes)
            .forEach { require(it in 0..MAX_SAFE_JSON_INTEGER) }
        require(largestItemBytes <= totalManifestBytes) { "Largest item exceeds manifest total" }
        validateItemIds("unknown_item_ids", unknownItemIds)
        validateItemIds("kept_item_ids", keptItemIds)
        validateItemIds("skipped_item_ids", skippedItemIds)
        require(keptItemIds.toSet().intersect(skippedItemIds.toSet()).isEmpty()) {
            "Kept and skipped IDs must be disjoint"
        }

        if (estimatesComplete) {
            require(unknownItemIds.isEmpty() && allocatableBytes != null &&
                safetyMarginBytes != null && safeAvailableBytes != null &&
                batchPeakBytes != null && streamingPeakBytes != null
            ) { "Complete estimates require space and both strategy peaks" }
        } else {
            require((unknownItemIds.isNotEmpty() || allocatableBytes == null) &&
                strategy == SpaceStrategy.INSUFFICIENT && decisionRequired
            ) { "Incomplete estimates must block transfer and identify what is unknown" }
        }

        if (allocatableBytes == null) {
            require(!estimatesComplete && safetyMarginBytes == null && safeAvailableBytes == null &&
                strategy == SpaceStrategy.INSUFFICIENT && decisionRequired
            ) { "Unknown capacity must block transfer" }
        } else {
            require(safetyMarginBytes != null && safeAvailableBytes ==
                maxOf(0L, allocatableBytes - safetyMarginBytes)
            ) { "Safe available bytes do not match the budget" }
        }

        val safe = safeAvailableBytes ?: 0L
        when (strategy) {
            SpaceStrategy.BATCH -> require(!decisionRequired && skippedItemIds.isEmpty() &&
                batchPeakBytes != null && batchPeakBytes <= safe
            ) { "Batch is allowed only when every item fits by default" }
            SpaceStrategy.STREAMING -> require(decisionRequired && keptItemIds.isNotEmpty() &&
                skippedItemIds.isEmpty() && batchPeakBytes != null && streamingPeakBytes != null &&
                batchPeakBytes > safe && streamingPeakBytes <= safe
            ) { "Streaming must fit when batch does not and needs user approval" }
            SpaceStrategy.DROP_LARGEST -> require(decisionRequired && keptItemIds.isNotEmpty() &&
                batchPeakBytes != null && streamingPeakBytes != null &&
                batchPeakBytes > safe && streamingPeakBytes > safe
            ) { "Drop-largest requires a kept subset and a streaming peak above budget" }
            SpaceStrategy.INSUFFICIENT -> require(decisionRequired && keptItemIds.isEmpty() &&
                skippedItemIds.isEmpty() && (!estimatesComplete ||
                (batchPeakBytes != null && streamingPeakBytes != null && minOf(batchPeakBytes, streamingPeakBytes) > safe))
            ) { "Insufficient strategy cannot authorize items when a full strategy fits" }
        }
    }
}

@Serializable
data class SpaceDecisionContract(
    val version: String,
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("transfer_id") val transferId: String,
    val strategy: SpaceStrategy,
    @SerialName("user_confirmed") val userConfirmed: Boolean,
    @SerialName("kept_item_ids") val keptItemIds: List<String>,
    @SerialName("skipped_item_ids") val skippedItemIds: List<String>,
) {
    fun validate() {
        require(version == SPACE_REPORT_VERSION && protocolVersion == SPACE_PROTOCOL_VERSION) {
            "Unsupported SpaceDecision version"
        }
        validateTransferId(transferId)
        validateItemIds("kept_item_ids", keptItemIds)
        validateItemIds("skipped_item_ids", skippedItemIds)
        require(keptItemIds.toSet().intersect(skippedItemIds.toSet()).isEmpty()) {
            "Kept and skipped IDs must be disjoint"
        }
        if (strategy == SpaceStrategy.INSUFFICIENT) {
            require(!userConfirmed && keptItemIds.isEmpty() && skippedItemIds.isEmpty()) {
                "Insufficient-space decision cannot confirm or authorize items"
            }
        } else {
            require(userConfirmed && keptItemIds.isNotEmpty()) {
                "Transfer decision requires user confirmation and kept items"
            }
            if (strategy != SpaceStrategy.DROP_LARGEST) {
                require(skippedItemIds.isEmpty()) { "Batch and streaming cannot skip items" }
            }
        }
    }

    /** Validate against the complete manifest before any data chunk is accepted. */
    fun validateForManifest(manifestItemCategories: Map<String, String>) {
        validate()
        if (strategy == SpaceStrategy.INSUFFICIENT) return

        val authorizedIds = (keptItemIds + skippedItemIds).toSet()
        require(manifestItemCategories.keys == authorizedIds) {
            "Manifest category map must exactly cover kept and skipped IDs"
        }
        if (strategy == SpaceStrategy.STREAMING || strategy == SpaceStrategy.DROP_LARGEST) {
            require(manifestItemCategories.values.all { it in STREAMING_FILE_CATEGORIES }) {
                "Streaming and drop-largest are limited to file categories until API restore is process-death idempotent"
            }
        }
    }
}

private fun validateTransferId(value: String) {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(parsed != null && parsed.toString() == value) { "transfer_id must be a canonical UUID" }
}

private fun validateItemIds(name: String, values: List<String>) {
    require(values.size <= MAX_SPACE_ITEM_IDS && values.size == values.toSet().size &&
        values.all { it.isNotBlank() && it.length <= 512 }
    ) { "$name must contain unique non-empty item IDs" }
}

private const val SPACE_REPORT_VERSION = "1.0"
private const val SPACE_PROTOCOL_VERSION = "2"
private const val MAX_SPACE_ITEM_IDS = 100_000
private const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L
private val STREAMING_FILE_CATEGORIES = setOf("photos", "videos", "audio", "documents", "downloads")
