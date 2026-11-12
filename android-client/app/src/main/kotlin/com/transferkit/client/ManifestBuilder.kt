package com.transferkit.client

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import com.transferkit.client.protocol.MAX_MANIFEST_BYTES

/** A lazy source item supplied by a selected Android category provider. */
class ManifestItemSource internal constructor(
    val id: String,
    /** Null means the provider cannot report a trustworthy size before reading the bytes. */
    val declaredSizeBytes: Long?,
    val metadata: Map<String, Any?>,
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
        val frozenSelection = selectedCategories.map { category ->
            category.copy(items = category.items.toList())
        }
        validateCategorySelection(frozenSelection)
        val transferId = UUID.randomUUID().toString()
        val createdAt = Instant.now().toString()
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
            validateManifestContract(document)
            val canonicalBytes = CANONICAL_JSON.encodeToString(JsonObject.serializer(), document)
                .toByteArray(Charsets.UTF_8)
            require(canonicalBytes.size <= MAX_MANIFEST_BYTES) { "Manifest exceeds the 64 MiB limit" }
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
        require(source.id.isNotBlank() && source.id.length <= MAX_ITEM_ID_LENGTH) { "Invalid manifest item ID" }
        source.declaredSizeBytes?.let { require(it >= 0) { "Negative provider size is invalid" } }

        val metadata = metadataAsJson(source.metadata)
        val metadataBytes = canonicalElementBytes(metadata)
        require(metadataBytes.size <= MAX_METADATA_BYTES) { "Item metadata exceeds 64 KiB" }

        val expectedSize = source.declaredSizeBytes
        if (expectedSize == null) {
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
        declaredSizeBytes = item.sizeBytes,
        metadata = item.manifestMetadata(),
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
        sortedMapOf(CODE_POINT_ORDER,
            "fingerprint" to JsonPrimitive(device.fingerprint),
            "manufacturer" to JsonPrimitive(device.manufacturer),
            "model" to JsonPrimitive(device.model),
            "sdk" to JsonPrimitive(device.sdk),
        ),
    )
    val categoryObjects = categories.associate { category ->
        val items = category.items.map { item ->
            JsonObject(
                sortedMapOf(CODE_POINT_ORDER,
                    "id" to JsonPrimitive(item.id),
                    "metadata" to item.metadata,
                    "sha256" to JsonPrimitive(item.sha256),
                    "size" to JsonPrimitive(item.sizeBytes),
                ),
            )
        }
        category.categoryId to JsonObject(
            sortedMapOf(CODE_POINT_ORDER,
                "items" to JsonArray(items),
                "method" to JsonPrimitive(category.method),
                "provider" to JsonPrimitive(category.provider),
            ),
        )
    }
    return JsonObject(
        sortedMapOf(CODE_POINT_ORDER,
            "categories" to JsonObject(categoryObjects.toSortedMap(CODE_POINT_ORDER)),
            "created_at" to JsonPrimitive(createdAt),
            "source_device" to deviceObject,
            "transfer_id" to JsonPrimitive(transferId),
            "version" to JsonPrimitive("1.0"),
        ),
    )
}

/** Contract assertions mirror manifest.schema.json; the receiving Station still runs JSON Schema. */
private fun validateManifestContract(document: JsonObject) {
    require(document.keys == setOf("version", "transfer_id", "created_at", "source_device", "categories"))
    require(document["version"] == JsonPrimitive("1.0"))
    val transferId = (document["transfer_id"] as? JsonPrimitive)?.content
        ?: throw IllegalArgumentException("Manifest transfer_id is required")
    require(runCatching { UUID.fromString(transferId).toString() == transferId }.getOrDefault(false))
    val createdAt = (document["created_at"] as? JsonPrimitive)?.content
        ?: throw IllegalArgumentException("Manifest created_at is required")
    require(createdAt.endsWith("Z")) { "Manifest timestamps must use UTC Z suffix" }

    val device = document["source_device"] as? JsonObject
        ?: throw IllegalArgumentException("Manifest source_device is required")
    require(device.keys == setOf("manufacturer", "model", "sdk", "fingerprint"))
    require((device["manufacturer"] as? JsonPrimitive)?.content?.let { it.isNotBlank() && it.length <= 128 } == true)
    require((device["model"] as? JsonPrimitive)?.content?.let { it.isNotBlank() && it.length <= 128 } == true)
    require((device["fingerprint"] as? JsonPrimitive)?.content?.let { it.isNotBlank() && it.length <= 512 } == true)
    val sdkPrimitive = device["sdk"] as? JsonPrimitive
        ?: throw IllegalArgumentException("Manifest device SDK is required")
    require(!sdkPrimitive.isString)
    val sdk = sdkPrimitive.intOrNull
        ?: throw IllegalArgumentException("Manifest device SDK is required")
    require(sdk >= 26)

    val categories = document["categories"] as? JsonObject
        ?: throw IllegalArgumentException("Manifest categories are required")
    require(categories.isNotEmpty())
    categories.forEach { (categoryId, rawCategory) ->
        require(categoryId in VALID_CATEGORY_IDS)
        val category = rawCategory as? JsonObject ?: throw IllegalArgumentException("Invalid category entry")
        require(category.keys == setOf("provider", "method", "items"))
        require((category["provider"] as? JsonPrimitive)?.content?.isNotBlank() == true)
        require((category["method"] as? JsonPrimitive)?.content?.isNotBlank() == true)
        val items = category["items"] as? JsonArray ?: throw IllegalArgumentException("Category items are required")
        val seenItemIds = HashSet<String>()
        items.forEach { rawItem ->
            val item = rawItem as? JsonObject ?: throw IllegalArgumentException("Invalid manifest item")
            require(item.keys == setOf("id", "size", "sha256", "metadata"))
            val id = (item["id"] as? JsonPrimitive)?.content
                ?: throw IllegalArgumentException("Manifest item id is required")
            require(id.isNotBlank() && id.length <= MAX_ITEM_ID_LENGTH && seenItemIds.add(id))
            val sizePrimitive = item["size"] as? JsonPrimitive
                ?: throw IllegalArgumentException("Manifest item size is required")
            require(!sizePrimitive.isString)
            val size = sizePrimitive.longOrNull
                ?: throw IllegalArgumentException("Manifest item size is required")
            require(size in 0..MAX_SAFE_INTEGER)
            val sha256 = (item["sha256"] as? JsonPrimitive)?.content
                ?: throw IllegalArgumentException("Manifest item sha256 is required")
            require(SHA256_PATTERN.matches(sha256))
            val metadata = item["metadata"] as? JsonObject
                ?: throw IllegalArgumentException("Manifest item metadata must be an object")
            require(canonicalElementBytes(metadata).size <= MAX_METADATA_BYTES)
        }
    }
}

private fun metadataAsJson(metadata: Map<String, Any?>): JsonObject =
    JsonObject(metadata.toSortedMap(CODE_POINT_ORDER).mapValues { (_, value) -> value.toJsonElement() })

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> canonicalize(this)
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
            key to value.toJsonElement()
        }
        JsonObject(fields.toSortedMap(CODE_POINT_ORDER))
    }
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> throw IllegalArgumentException("Unsupported manifest metadata value: ${this::class.java.name}")
}

private fun canonicalElementBytes(element: JsonElement): ByteArray =
    CANONICAL_JSON.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8)

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.toSortedMap(CODE_POINT_ORDER).mapValues { (_, value) -> canonicalize(value) })
    is JsonArray -> JsonArray(element.map(::canonicalize))
    else -> element
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
private val CODE_POINT_ORDER = Comparator<String> { first, second ->
    var firstIndex = 0
    var secondIndex = 0
    while (firstIndex < first.length && secondIndex < second.length) {
        val firstPoint = first.codePointAt(firstIndex)
        val secondPoint = second.codePointAt(secondIndex)
        if (firstPoint != secondPoint) return@Comparator firstPoint.compareTo(secondPoint)
        firstIndex += Character.charCount(firstPoint)
        secondIndex += Character.charCount(secondPoint)
    }
    (first.length - firstIndex).compareTo(second.length - secondIndex)
}
private val VALID_CATEGORY_IDS = setOf(
    "photos", "videos", "audio", "documents", "downloads", "contacts", "calendar", "sms",
    "call_log", "apks", "settings", "app_data", "wifi_passwords", "launcher_layout", "accounts",
    "drm", "banking_apps", "two_factor_tokens",
)
private const val SNAPSHOT_DIRECTORY = "transferkit-manifest-snapshots"
private const val MAX_METADATA_BYTES = 64 * 1024
private const val MAX_ITEM_ID_LENGTH = 512
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
