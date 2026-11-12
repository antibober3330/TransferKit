package com.transferkit.client

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class DeviceInfoProviderTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun readsIdentityVersionMemoryAndStorageFromPlatformApis() {
        val info = DeviceInfoProvider.read(context)

        assertEquals(Build.MANUFACTURER.trim(), info.manufacturer)
        assertEquals(Build.MODEL.trim(), info.model)
        assertEquals(Build.VERSION.SDK_INT, info.sdk)
        assertEquals(Build.FINGERPRINT, info.fingerprint)
        assertEquals(Build.VERSION.RELEASE, info.androidRelease)
        assertTrue(info.totalMemoryBytes > 0)
        assertTrue(info.availableMemoryBytes in 0..info.totalMemoryBytes)

        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)!!.getMemoryInfo(memory)
        assertEquals(memory.totalMem, info.totalMemoryBytes)

        val storage = StatFs(Environment.getDataDirectory().absolutePath)
        assertEquals(storage.totalBytes, info.dataStorageTotalBytes)
        // Free space is mutable while the test runner and package manager write files.
        assertTrue(abs(storage.availableBytes - info.dataStorageAvailableBytes) <= 64L * 1024 * 1024)
    }
}
