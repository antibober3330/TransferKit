package com.transferkit.client.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.transferkit.client.DeviceInfo
import com.transferkit.client.ManifestBuilder
import com.transferkit.client.ManifestCategorySource
import com.transferkit.client.ManifestItemSource
import com.transferkit.client.receiver.AndroidTransferReceiver
import java.nio.file.Files
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsbAdbServerLifecycleTest {
    @Test
    fun receiverCloseWakesAcceptLoopsAndReleasesAbstractSockets() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = AndroidTransferReceiver(context)
        val server = UsbAdbReceiverServer(receiver)
        try {
            server.start()
            assertTrue(awaitSocketNames(UsbAdbReceiverServer.CONTROL_SOCKET, UsbAdbReceiverServer.DATA_SOCKET))
        } finally {
            server.close()
            receiver.close()
        }
        assertTrue(awaitNoSocketNames(UsbAdbReceiverServer.CONTROL_SOCKET, UsbAdbReceiverServer.DATA_SOCKET))
        assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "transferkit-adb-accept" })
    }

    @Test
    fun sourceCloseWakesAcceptLoopsAndReleasesAbstractSockets() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = Files.createTempDirectory(context.cacheDir.toPath(), "tk-source-lifecycle-").toFile()
        val bundle = ManifestBuilder(cache).build(
            DeviceInfo(
                manufacturer = "Fixture",
                model = "LifecycleTest",
                sdk = 34,
                fingerprint = "fixture/lifecycle-test",
                androidRelease = "14",
                totalMemoryBytes = 1024,
                availableMemoryBytes = 512,
                dataStorageTotalBytes = 4096,
                dataStorageAvailableBytes = 2048,
            ),
            listOf(
                ManifestCategorySource(
                    "documents",
                    "FixtureProvider",
                    "GeneratedFixture",
                    listOf(ManifestItemSource("item-1", 1, emptyMap()) { it.write(1) }),
                ),
            ),
        )
        val server = UsbAdbSourceServer(bundle)
        try {
            server.start()
            assertTrue(awaitSocketNames(UsbAdbSourceServer.CONTROL_SOCKET, UsbAdbSourceServer.DATA_SOCKET))
        } finally {
            server.close()
            bundle.close()
            cache.deleteRecursively()
        }
        assertTrue(awaitNoSocketNames(UsbAdbSourceServer.CONTROL_SOCKET, UsbAdbSourceServer.DATA_SOCKET))
        assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("transferkit-source-") })
    }

    private fun awaitSocketNames(vararg names: String): Boolean = await {
        val table = socketTable()
        names.all { "@$it" in table }
    }

    private fun awaitNoSocketNames(vararg names: String): Boolean = await {
        val table = socketTable()
        names.none { "@$it" in table }
    }

    private fun socketTable(): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /proc/net/unix"),
    ).bufferedReader().use { it.readText() }

    private fun await(timeoutMs: Long = 2_000, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }
}
