package com.transferkit.client

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class ManifestBuilderTest {
    private val device = DeviceInfo(
        manufacturer = "Fixture",
        model = "ManifestTest",
        sdk = 34,
        fingerprint = "fixture/manifest-test",
        androidRelease = "14",
        totalMemoryBytes = 4L * 1024 * 1024 * 1024,
        availableMemoryBytes = 2L * 1024 * 1024 * 1024,
        dataStorageTotalBytes = 64L * 1024 * 1024 * 1024,
        dataStorageAvailableBytes = 32L * 1024 * 1024 * 1024,
    )

    @Test
    fun canonicalUtcTimestampMatchesPythonPrecision() {
        assertEquals("1970-01-01T00:00:00Z", formatCanonicalUtcTimestamp(Instant.EPOCH))
        assertEquals(
            "1970-01-01T00:00:00.123456Z",
            formatCanonicalUtcTimestamp(Instant.parse("1970-01-01T00:00:00.123456789Z")),
        )
    }

    @Test
    fun knownSizeItemHasExactHashAndSecondPassBytes() {
        val cache = Files.createTempDirectory("tk-manifest-known-").toFile()
        val bytes = "known-size provider item".toByteArray()
        val builder = ManifestBuilder(cache)
        val bundle = builder.build(
            device,
            listOf(
                ManifestCategorySource(
                    categoryId = "photos",
                    provider = "FixtureProvider",
                    method = "known-size stream",
                    items = listOf(
                        ManifestItemSource("photo-1", bytes.size.toLong(), mapOf("path" to "photo.jpg")) {
                            it.write(bytes)
                        },
                    ),
                ),
            ),
        )
        try {
            val item = bundle.categories.single().items.single()
            assertEquals(bytes.size.toLong(), item.sizeBytes)
            assertEquals(sha256(bytes), item.sha256)
            val sent = ByteArrayOutputStream()
            item.writeAndVerify(sent)
            assertArrayEquals(bytes, sent.toByteArray())
            assertTrue(String(bundle.canonicalBytes, Charsets.UTF_8).startsWith("{\"categories\":"))
            assertTrue(bundle.createdAt.endsWith("Z"))
        } finally {
            bundle.close()
            cache.deleteRecursively()
        }
    }

    @Test
    fun changedKnownSizeSourceIsRejectedOnTransportPass() {
        val cache = Files.createTempDirectory("tk-manifest-changed-").toFile()
        var currentBytes = "before".toByteArray()
        val bundle = ManifestBuilder(cache).build(
            device,
            listOf(
                ManifestCategorySource(
                    "photos",
                    "FixtureProvider",
                    "mutable stream",
                    listOf(ManifestItemSource("photo-1", 6, emptyMap()) { it.write(currentBytes) }),
                ),
            ),
        )
        try {
            currentBytes = "after!".toByteArray()
            val output = ByteArrayOutputStream()
            assertThrows(IOException::class.java) {
                bundle.categories.single().items.single().writeAndVerify(output)
            }
        } finally {
            bundle.close()
            cache.deleteRecursively()
        }
    }

    @Test
    fun unknownSizeSourceUsesFrozenSnapshotAndCloseRemovesIt() {
        val cache = Files.createTempDirectory("tk-manifest-snapshot-").toFile()
        val original = "unknown size frozen bytes".toByteArray()
        var currentBytes = original
        val bundle = ManifestBuilder(cache).build(
            device,
            listOf(
                ManifestCategorySource(
                    "contacts",
                    "FixtureProvider",
                    "unknown-size vCard",
                    listOf(ManifestItemSource("contact-1", null, mapOf("format" to "vCard")) { it.write(currentBytes) }),
                ),
            ),
        )
        try {
            currentBytes = "changed after preflight".toByteArray()
            val output = ByteArrayOutputStream()
            val item = bundle.categories.single().items.single()
            item.writeAndVerify(output)
            assertEquals(original.size.toLong(), item.sizeBytes)
            assertEquals(sha256(original), item.sha256)
            assertArrayEquals(original, output.toByteArray())
            assertTrue(File(cache, "transferkit-manifest-snapshots/${bundle.transferId}").isDirectory)
        } finally {
            bundle.close()
            assertFalse(File(cache, "transferkit-manifest-snapshots/${bundle.transferId}").exists())
            cache.deleteRecursively()
        }
    }

    @Test
    fun replayableUnknownSizeSourceUsesMeasuredBytesWithoutDiskSnapshot() {
        val cache = Files.createTempDirectory("tk-manifest-replayable-").toFile()
        val bytes = "media provider may redact metadata".toByteArray()
        val bundle = ManifestBuilder(cache).build(
            device,
            listOf(
                ManifestCategorySource(
                    "photos",
                    "FixtureMediaStore",
                    "redacted stream",
                    listOf(
                        ManifestItemSource(
                            id = "photo-redacted",
                            declaredSizeBytes = null,
                            metadata = emptyMap(),
                            replayableWithoutSnapshot = true,
                        ) { it.write(bytes) },
                    ),
                ),
            ),
        )
        try {
            val item = bundle.categories.single().items.single()
            assertEquals(bytes.size.toLong(), item.sizeBytes)
            assertEquals(sha256(bytes), item.sha256)
            assertFalse(File(cache, "transferkit-manifest-snapshots/${bundle.transferId}").exists())
            val sent = ByteArrayOutputStream()
            item.writeAndVerify(sent)
            assertArrayEquals(bytes, sent.toByteArray())
        } finally {
            bundle.close()
            cache.deleteRecursively()
        }
    }

    @Test
    fun replayableUnknownSizeSourceRejectsChangedSecondPass() {
        val cache = Files.createTempDirectory("tk-manifest-replayable-change-").toFile()
        var bytes = "initial media stream".toByteArray()
        val bundle = ManifestBuilder(cache).build(
            device,
            listOf(
                ManifestCategorySource(
                    "photos",
                    "FixtureMediaStore",
                    "redacted stream",
                    listOf(
                        ManifestItemSource(
                            id = "photo-redacted",
                            declaredSizeBytes = null,
                            metadata = emptyMap(),
                            replayableWithoutSnapshot = true,
                        ) { it.write(bytes) },
                    ),
                ),
            ),
        )
        try {
            bytes = "changed media stream".toByteArray()
            assertThrows(IOException::class.java) {
                bundle.categories.single().items.single().writeAndVerify(ByteArrayOutputStream())
            }
        } finally {
            bundle.close()
            cache.deleteRecursively()
        }
    }

    @Test
    fun duplicateItemIdsAndDuplicateCategoriesAreRejected() {
        val cache = Files.createTempDirectory("tk-manifest-invalid-").toFile()
        val builder = ManifestBuilder(cache)
        val duplicateItems = ManifestCategorySource(
            "photos",
            "FixtureProvider",
            "fixture",
            listOf(
                ManifestItemSource("same-id", 1, emptyMap()) { it.write(1) },
                ManifestItemSource("same-id", 1, emptyMap()) { it.write(2) },
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(device, listOf(duplicateItems))
        }
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(device, listOf(duplicateItems.copy(items = emptyList()), duplicateItems.copy(items = emptyList())))
        }
        cache.deleteRecursively()
    }

    @Test
    fun rejectsMetadataNestedBeyondTheContractLimit() {
        val cache = Files.createTempDirectory("tk-manifest-depth-").toFile()
        var nested: Any? = "leaf"
        repeat(40) { nested = mapOf("next" to nested) }

        val failure = assertThrows(IllegalArgumentException::class.java) {
            ManifestBuilder(cache).build(
                device,
                listOf(
                    ManifestCategorySource(
                        "photos",
                        "FixtureProvider",
                        "deep metadata",
                        listOf(ManifestItemSource("photo-deep", 0, mapOf("nested" to nested)) {}),
                    ),
                ),
            )
        }
        assertTrue(failure.message!!.contains("nesting depth"))
        assertFalse(File(cache, "transferkit-manifest-snapshots").exists())
        cache.deleteRecursively()
    }

    @Test
    fun enforcesSharedManifestItemLimit() {
        com.transferkit.client.protocol.validateManifestItemCount(100_000L)
        assertThrows(IllegalArgumentException::class.java) {
            com.transferkit.client.protocol.validateManifestItemCount(100_001L)
        }
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
