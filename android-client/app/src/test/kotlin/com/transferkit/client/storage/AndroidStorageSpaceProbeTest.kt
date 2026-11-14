package com.transferkit.client.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class AndroidStorageSpaceProbeTest {
    @Test
    fun sameVolumeUsesOneStorageBudgetForStagingAndDestination() {
        val uuid = UUID.randomUUID()
        val platform = FakeStorageSpacePlatform(
            uuidByPath = mapOf("stage" to uuid, "destination" to uuid),
            allocatableByUuid = mapOf(uuid to 2_000L * MIB),
            availableByPath = mapOf("stage" to 1_800L * MIB),
        )

        val result = AndroidStorageSpaceProbe(platform).probe(File("stage"), File("destination"))

        assertTrue(result.sameVolume == true)
        assertSame(result.staging, result.destination)
        assertEquals(2_000L * MIB, result.staging.allocatableBytes)
        assertEquals(100L * MIB, result.staging.safetyMarginBytes)
        assertEquals(1_900L * MIB, result.staging.safeAvailableBytes)
        assertEquals(1, platform.availableCalls)
    }

    @Test
    fun distinctVolumesKeepIndependentAllocatableAndStatFsValues() {
        val stagingUuid = UUID.randomUUID()
        val destinationUuid = UUID.randomUUID()
        val platform = FakeStorageSpacePlatform(
            uuidByPath = mapOf("stage" to stagingUuid, "destination" to destinationUuid),
            allocatableByUuid = mapOf(stagingUuid to 1_000L * MIB, destinationUuid to 4_000L * MIB),
            availableByPath = mapOf("stage" to 900L * MIB, "destination" to 3_500L * MIB),
        )

        val result = AndroidStorageSpaceProbe(platform).probe(File("stage"), File("destination"))

        assertFalse(result.sameVolume == true)
        assertEquals(1_000L * MIB, result.staging.allocatableBytes)
        assertEquals(900L * MIB, result.staging.availableBytes)
        assertEquals(4_000L * MIB, result.destination.allocatableBytes)
        assertEquals(3_500L * MIB, result.destination.availableBytes)
        assertEquals(2, platform.availableCalls)
    }

    @Test
    fun failedAllocatableQueryUsesStatFsWithLargerSafetyMargin() {
        val uuid = UUID.randomUUID()
        val platform = FakeStorageSpacePlatform(
            uuidByPath = mapOf("stage" to uuid, "destination" to uuid),
            allocatableByUuid = emptyMap(),
            availableByPath = mapOf("stage" to 2_000L * MIB),
        )

        val result = AndroidStorageSpaceProbe(platform).probe(File("stage"), File("destination"))

        assertEquals(SpaceMeasurementMethod.STAT_FS_FALLBACK, result.staging.method)
        assertEquals(2_000L * MIB, result.staging.allocatableBytes)
        assertEquals(200L * MIB, result.staging.safetyMarginBytes)
        assertEquals(1_800L * MIB, result.staging.safeAvailableBytes)
    }

    @Test
    fun unavailableVolumeDoesNotInventCapacity() {
        val platform = FakeStorageSpacePlatform(
            uuidByPath = emptyMap(),
            allocatableByUuid = emptyMap(),
            availableByPath = emptyMap(),
        )

        val result = AndroidStorageSpaceProbe(platform).probe(File("missing-stage"), File("missing-destination"))

        assertNull(result.sameVolume)
        assertEquals(SpaceMeasurementMethod.UNAVAILABLE, result.staging.method)
        assertNull(result.staging.safeAvailableBytes)
        assertNull(result.destination.safeAvailableBytes)
    }

    private class FakeStorageSpacePlatform(
        private val uuidByPath: Map<String, UUID>,
        private val allocatableByUuid: Map<UUID, Long>,
        private val availableByPath: Map<String, Long>,
    ) : StorageSpacePlatform {
        var availableCalls: Int = 0
            private set

        override fun uuidForPath(path: File): UUID =
            uuidByPath[path.path] ?: error("no UUID for ${path.path}")

        override fun allocatableBytes(storageUuid: UUID): Long =
            allocatableByUuid[storageUuid] ?: error("no allocatable estimate")

        override fun availableBytes(path: File): Long {
            availableCalls += 1
            return availableByPath[path.path] ?: error("no StatFs estimate")
        }
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
