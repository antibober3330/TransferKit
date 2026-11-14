package com.transferkit.client.shizuku

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

@RunWith(AndroidJUnit4::class)
class ShizukuBridgeInstrumentedTest {
    @Test
    fun stoppedOrMissingServiceIsReportedWithoutStartingItOrRunningOperations() {
        // This is a read-only physical acceptance for the normal stock-phone state.
        // Keep it skippable if a developer intentionally starts Shizuku on the device.
        assumeFalse("This case requires Shizuku service to be stopped", Shizuku.pingBinder())

        val bridge = ShizukuBridge()
        val probe = bridge.probe()

        assertEquals(ShizukuState.SERVICE_NOT_RUNNING, probe.state)
        assertEquals("SHIZUKU_REQUIRED", probe.reasonCode)
        assertFalse(probe.serviceRunning)
        assertFalse(probe.permissionGranted)
        assertFalse(probe.elevatedOperationsImplemented)
        assertEquals(
            ShizukuPermissionRequestResult.SERVICE_NOT_RUNNING,
            bridge.requestPermission(0x544b),
        )
        assertFalse("Probe must not start Shizuku", Shizuku.pingBinder())
    }
}
