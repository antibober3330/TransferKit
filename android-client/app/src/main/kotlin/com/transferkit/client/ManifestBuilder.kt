package com.transferkit.client

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneOffset
import java.security.MessageDigest
import java.time.temporal.ChronoUnit
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.transferkit.client.protocol.MAX_MANIFEST_BYTES
import com.transferkit.client.protocol.JSON_CODE_POINT_ORDER
import com.transferkit.client.protocol.validateManifestItemCount
import com.transferkit.client.receiver.IncomingManifestValidator

/** A lazy source item supplied by a selected Android category provider. */
class ManifestItemSource internal constructor(
    val id: String,
    /** Null means the provider cannot report a trustworthy size before reading the bytes. */
    val declaredSizeBytes: Long?,
    val metadata: Map<String, Any?>,
    /** Use a verified second pass instead of a disk snapshot for stable, replayable providers. */
    val replayableWithoutSnapshot: Boolean = false,
    private val writePayload: (OutputStream) -> Unit,
) {
    internal fun writeTo(output: OutputStream) = writePayload(output)
}

data class ManifestCategorySource(
    val categoryId: String,
    val provider: String,
    val method: String,
    val items: List<ManifestItemSource>,
)

/** Item plus its preflight integrity values and a verified source for the later transport pass. */
class PreparedManifestItem internal constructor(
    val id: String,
    val sizeBytes: Long,
    val sha256: String,
    val metadata: JsonObject,
    private val writePayload: (OutputStream) -> Unit,
) {
    /** A second pass must reproduce the exact manifest bytes before the item can be considered sent. */
    fun writeAndVerify(output: OutputStream) {
        val digesting = CountingDigestOutputStream(output)
        writePayload(digesting)
        val observed = digesting.finish()
        if (observed.sizeBytes != sizeBytes || observed.sha256 != sha256) {
            throw IOException("Source item changed after manifest preflight")
        }
    }
}

data class PreparedManifestCategory(
    val categoryId: String,
    val provider: String,
    val method: String,
    val items: List<PreparedManifestItem>,
)

data class ManifestBuildFailure(
    val categoryId: String,
    val itemId: String,
    val reason: String,
)

/** Immutable wire document and the exact source readers used for its items. */
class TransferManifestBundle internal constructor(
    val transferId: String,
    val createdAt: String,
    val document: JsonObject,
    val canonicalBytes: ByteArray,
    val categories: List<PreparedManifestCategory>,
    val failures: List<ManifestBuildFailure>,
    private val snapshotDirectory: File,
) : AutoCloseable {
    /** Call only after transfer/cancellation no longer needs any unknown-size item snapshot. */
    override fun close() {
        if (snapshotDirectory.exists() && !snapshotDirectory.deleteRecursively()) {
            throw IOException("Could not clean the manifest snapshot directory")
        }
    }
}

/**
 * Builds schema-shaped v1 manifests. Known-size providers are hashed in a streaming preflight
 * pass and rechecked on send. Unknown-size providers are first frozen to private cache snapshots;
 * their measured size and SHA-256 therefore describe the exact bytes sent later.
 */
class ManifestBuilder internal constructor(private val cacheDirectory: File) {
    constructor(context: Context) : this(context.applicationContext.cacheDir)

    fun build(
        sourceDevice: DeviceInfo,
        selectedCategories: List<ManifestCategorySource>,
    ): TransferManifestBundle {
        var selectedItemCount = 0L
        selectedCategories.forEach { category ->
            selectedItemCount += category.items.size.toLong()
            validateManifestItemCount(selectedItemCount)
        }
        val frozenSelection = selectedCategories.map { category ->
            category.copy(items = category.items.toList())
        }
        validateCategorySelection(frozenSelection)
        val transferId = UUID.randomUUID().toString()
        val createdAt = formatCanonicalUtcTimestamp(Instant.now())
        val snapshotDirectory = File(
            File(cacheDirectory, SNAPSHOT_DIRECTORY),
            transferId,
        )
        val preparedCategories = ArrayList<PreparedManifestCategory>(selectedCategories.size)
        val failures = ArrayList<ManifestBuildFailure>()

        try {
            frozenSelection.forEach { category ->
                val seenIds = HashSet<String>()
                val preparedItems = category.items.mapNotNull { source ->
                    if (!seenIds.add(source.id)) {
                        throw IllegalArgumentException("Duplicate item ID in ${category.categoryId}")
                    }
                    try {
                        prepareItem(source, snapshotDirectory)
                    } catch (_: SecurityException) {
                        failures += ManifestBuildFailure(category.categoryId, source.id, "PERMISSION_DENIED")
                        null
                    } catch (_: IOException) {
                        failures += ManifestBuildFailure(category.categoryId, source.id, "SOURCE_READ_FAILED")
                        null
                    }
                }
                preparedCategories += PreparedManifestCategory(
                    categoryId = category.categoryId,
                    provider = category.provider,
                    method = category.method,
                    items = preparedItems,
                )
            }

            val document = buildManifestDocument(transferId, createdAt, sourceDevice, preparedCategories)
            val canonicalBytes = CANONICAL_JSON.encodeToString(JsonObject.serializer(), document)
                .toByteArray(Charsets.UTF_8)
            require(canonicalBytes.size <= MAX_MANIFEST_BYTES) { "Manifest exceeds the 64 MiB limit" }
            IncomingManifestValidator.parse(canonicalBytes)
            return TransferManifestBundle(
                transferId,
                createdAt,
                document,
                canonicalBytes,
                preparedCategories,
                failures.toList(),
                snapshotDirectory,
            )
        } catch (failure: Throwable) {
            if (snapshotDirectory.exists()) snapshotDirectory.deleteRecursively()
            throw failure
        }
    }

    private fun prepareItem(source: ManifestItemSource, snapshotDirectory: File): PreparedManifestItem {
        require(source.id.isNotBlank() && source.id.codePointCount(0, source.id.length) <= MAX_ITEM_ID_LENGTH) {
            "Invalid manifest item ID"
        }
        source.declaredSizeBytes?.let { require(it >= 0) { "Negative provider size is invalid" } }

        val metadata = metadataAsJson(source.metadata)
        val metadataBytes = canonicalElementBytes(metadata)
        require(metadataBytes.size <= MAX_METADATA_BYTES) { "Item metadata exceeds 64 KiB" }

        val expectedSize = source.declaredSizeBytes
        if (expectedSize == null) {
            if (source.replayableWithoutSnapshot) {
                val digesting = CountingDigestOutputStream(DiscardOutputStream)
                source.writeTo(digesting)
                val observed = digesting.finish()
                validateItemSize(observed.sizeBytes)
                return PreparedManifestItem(
                    source.id,
                    observed.sizeBytes,
                    observed.sha256,
                    metadata,
                    source::writeTo,
                )
            }
            snapshotDirectory.mkdirs()
            val snapshot = File(snapshotDirectory, "${sha256Hex(source.id.toByteArray(Charsets.UTF_8))}.snapshot")
            val observed = FileOutputStream(snapshot).use { fileOutput ->
                val digesting = CountingDigestOutputStream(fileOutput)
                source.writeTo(digesting)
                digesting.finish()
            }
            validateItemSize(observed.sizeBytes)
            return PreparedManifestItem(
                source.id,
                observed.sizeBytes,
                observed.sha256,
                metadata,
            ) { output ->
                snapshot.inputStream().use { input -> input.copyTo(output) }
            }
        }

        val digesting = CountingDigestOutputStream(DiscardOutputStream)
        source.writeTo(digesting)
        val observed = digesting.finish()
        validateItemSize(observed.sizeBytes)
        if (observed.sizeBytes != expectedSize) {
            throw IOException("Provider size changed during manifest preflight for ${source.id}")
        }
        return PreparedManifestItem(
            source.id,
            observed.sizeBytes,
            observed.sha256,
            metadata,
            source::writeTo,
        )
    }

    private fun validateCategorySelection(categories: List<ManifestCategorySource>) {
        require(categories.isNotEmpty()) { "At least one category must be selected" }
        val seen = HashSet<String>()
        categories.forEach { category ->
            require(category.categoryId in ALLOWED_CATEGORIES) { "Unknown category ${category.categoryId}" }
            require(seen.add(category.categoryId)) { "Duplicate category ${category.categoryId}" }
            require(category.provider.isNotBlank() && category.provider.length <= 128) {
                "Invalid provider name for ${category.categoryId}"
            }
            require(category.method.isNotBlank() && category.method.length <= 128) {
                "Invalid provider method for ${category.categoryId}"
            }
        }
    }

    companion object {
        private val ALLOWED_CATEGORIES = setOf(
            "photos", "videos", "audio", "documents", "downloads", "contacts", "calendar",
            "sms", "call_log", "apks", "settings", "app_data", "wifi_passwords",
            "launcher_layout", "accounts", "drm", "banking_apps", "two_factor_tokens",
        )
    }
}

/** Adapter helpers keep Android provider identity and wire metadata in one place. */
object ManifestItemSources {
    fun media(provider: MediaProvider, item: MediaItem) = ManifestItemSource(
        id = item.id,
        declaredSizeBytes = item.sizeBytes.takeUnless { item.providerSizeMayDiffer },
        metadata = item.manifestMetadata(),
        replayableWithoutSnapshot = item.providerSizeMayDiffer,
    ) { output -> provider.openStream(item).use { it.copyTo(output) } }

    fun contact(provider: ContactsProvider, item: ContactVCardItem) = ManifestItemSource(
        id = item.id,
        declaredSizeBytes = null,
        metadata = item.manifestMetadata(),
    ) { output -> provider.exportVCard(item, output) }

    fun calendar(item: CalendarEventItem) = ManifestItemSource(
        id = item.id,
        declaredSizeBytes = item.sizeBytes,
        metadata = item.manifestMetadata(),
    ) { output -> item.writeTo(output) }

    fun sms(item: SmsMessageItem) = ManifestItemSource(
        id = item.id,
        declaredSizeBytes = item.sizeBytes,
        metadata = item.manifestMetadata(),
    ) { output -> item.writeTo(output) }

    fun callLog(item: CallLogItem) = ManifestItemSource(
        id = item.id,
        declaredSizeBytes = item.sizeBytes,
        metadata = item.manifestMetadata(),
    ) { output -> item.writeTo(output) }

    fun apk(item: ApkFileItem) = ManifestItemSource(
        id = item.id,
        declaredSizeBytes = item.sizeBytes,
        metadata = item.manifestMetadata(),
    ) { output -> item.copyAndVerify(output) }

    fun settings(item: SettingsSnapshotItem) = ManifestItemSource(
        id = item.id,
        declaredSizeBytes = item.sizeBytes,
        metadata = item.manifestMetadata(),
    ) { output -> item.writeTo(output) }
}

private fun buildManifestDocument(
    transferId: String,
    createdAt: String,
    device: DeviceInfo,
    categories: List<PreparedManifestCategory>,
): JsonObject {
    val deviceObject = JsonObject(
        sortedMapOf(JSON_CODE_POINT_ORDER,
            "fingerprint" to JsonPrimitive(device.fingerprint),
            "manufacturer" to JsonPrimitive(device.manufacturer),
            "model" to JsonPrimitive(device.model),
            "sdk" to JsonPrimitive(device.sdk),
        ),
    )
    val categoryObjects = categories.associate { category ->
        val items = category.items.map { item ->
            JsonObject(
                sortedMapOf(JSON_CODE_POINT_ORDER,
                    "id" to JsonPrimitive(item.id),
                    "metadata" to item.metadata,
                    "sha256" to JsonPrimitive(item.sha256),
                    "size" to JsonPrimitive(item.sizeBytes),
                ),
            )
        }
        category.categoryId to JsonObject(
            sortedMapOf(JSON_CODE_POINT_ORDER,
                "items" to JsonArray(items),
                "method" to JsonPrimitive(category.method),
                "provider" to JsonPrimitive(category.provider),
            ),
        )
    }
    return JsonObject(
        sortedMapOf(JSON_CODE_POINT_ORDER,
            "categories" to JsonObject(categoryObjects.toSortedMap(JSON_CODE_POINT_ORDER)),
            "created_at" to JsonPrimitive(createdAt),
            "source_device" to deviceObject,
            "transfer_id" to JsonPrimitive(transferId),
            "version" to JsonPrimitive("1.0"),
        ),
    )
}

internal fun formatCanonicalUtcTimestamp(value: Instant): String {
    val normalized = value.truncatedTo(ChronoUnit.MICROS)
    return if (normalized.nano == 0) {
        CANONICAL_UTC_SECONDS.format(normalized)
    } else {
        CANONICAL_UTC_MICROS.format(normalized)
    }
}

private val CANONICAL_UTC_SECONDS = DateTimeFormatterBuilder()
    .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
    .appendLiteral('Z')
    .toFormatter(Locale.ROOT)
    .withZone(ZoneOffset.UTC)

private val CANONICAL_UTC_MICROS = DateTimeFormatterBuilder()
    .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
    .appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, true)
    .appendLiteral('Z')
    .toFormatter(Locale.ROOT)
    .withZone(ZoneOffset.UTC)

private fun metadataAsJson(metadata: Map<String, Any?>): JsonObject =
    JsonObject(metadata.toSortedMap(JSON_CODE_POINT_ORDER).mapValues { (_, value) -> value.toJsonElement(1) })

private fun Any?.toJsonElement(depth: Int): JsonElement {
    require(depth <= MAX_METADATA_DEPTH) { "Item metadata exceeds the maximum nesting depth" }
    return when (this) {
    null -> JsonNull
    is JsonElement -> canonicalize(this, depth)
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Byte, is Short, is Int, is Long -> JsonPrimitive((this as Number).toLong())
    is Float, is Double -> {
        val number = (this as Number).toDouble()
        require(number.isFinite()) { "Metadata numbers must be finite" }
        JsonPrimitive(number)
    }
    is Number -> throw IllegalArgumentException("Unsupported manifest metadata number type")
    is Map<*, *> -> {
        val fields = this.entries.associate { (key, value) ->
            require(key is String) { "Metadata object keys must be strings" }
            key to value.toJsonElement(depth + 1)
        }
        JsonObject(fields.toSortedMap(JSON_CODE_POINT_ORDER))
    }
    is Iterable<*> -> JsonArray(map { it.toJsonElement(depth + 1) })
    is Array<*> -> JsonArray(map { it.toJsonElement(depth + 1) })
    else -> throw IllegalArgumentException("Unsupported manifest metadata value: ${this::class.java.name}")
    }
}

private fun canonicalElementBytes(element: JsonElement): ByteArray =
    CANONICAL_JSON.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8)

private fun canonicalize(element: JsonElement, depth: Int = 0): JsonElement {
    require(depth <= MAX_METADATA_DEPTH) { "Item metadata exceeds the maximum nesting depth" }
    return when (element) {
    is JsonObject -> JsonObject(element.toSortedMap(JSON_CODE_POINT_ORDER).mapValues { (_, value) -> canonicalize(value, depth + 1) })
    is JsonArray -> JsonArray(element.map { canonicalize(it, depth + 1) })
    else -> element
    }
}

private data class DigestResult(val sizeBytes: Long, val sha256: String)

private class CountingDigestOutputStream(output: OutputStream) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var size = 0L
    private val target = output

    override fun write(value: Int) {
        target.write(value)
        digest.update(value.toByte())
        size = checkedAdd(size, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        target.write(buffer, offset, length)
        digest.update(buffer, offset, length)
        size = checkedAdd(size, length.toLong())
    }

    fun finish(): DigestResult = DigestResult(size, digest.digest().toHex())
}

private object DiscardOutputStream : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
}

private fun validateItemSize(size: Long) {
    require(size in 0..MAX_SAFE_INTEGER) { "Item size is outside the manifest schema limit" }
}

private fun checkedAdd(total: Long, increment: Long): Long = try {
    Math.addExact(total, increment)
} catch (_: ArithmeticException) {
    throw IOException("Manifest item exceeds supported byte count")
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val CANONICAL_JSON = Json { encodeDefaults = true; prettyPrint = false }
private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
private val VALID_CATEGORY_IDS = setOf(
    "photos", "videos", "audio", "documents", "downloads", "contacts", "calendar", "sms",
    "call_log", "apks", "settings", "app_data", "wifi_passwords", "launcher_layout", "accounts",
    "drm", "banking_apps", "two_factor_tokens",
)
private const val SNAPSHOT_DIRECTORY = "transferkit-manifest-snapshots"
private const val MAX_METADATA_BYTES = 64 * 1024
private const val MAX_METADATA_DEPTH = 32
private const val MAX_ITEM_ID_LENGTH = 512
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
