package com.transferkit.client.deviceowner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoBridgeTest {
    @Test
    fun confirmedOwnerStateIsReportedWithoutClaimingPolicyOperations() {
        val api = FakeDeviceOwnerApi(result = true)

        val result = DoBridge("com.transferkit.client", api).probe()

        assertTrue(result.statusAvailable)
        assertTrue(result.activeForThisApp)
        assertEquals("OK", result.reasonCode)
        assertFalse(result.policyOperationsImplemented)
        assertEquals(listOf("com.transferkit.client"), api.queriedPackages)
    }

    @Test
    fun confirmedNonOwnerStateIsNotConfusedWithUnavailableProbe() {
        val api = FakeDeviceOwnerApi(result = false)

        val result = DoBridge("com.transferkit.client", api).probe()

        assertTrue(result.statusAvailable)
        assertFalse(result.activeForThisApp)
        assertEquals("DEVICE_OWNER_REQUIRED", result.reasonCode)
        assertFalse(result.policyOperationsImplemented)
    }

    @Test
    fun policyManagerFailureIsUnknownRatherThanReportedAsNonOwner() {
        val api = FakeDeviceOwnerApi(fail = true)

        val result = DoBridge("com.transferkit.client", api).probe()

        assertFalse(result.statusAvailable)
        assertFalse(result.activeForThisApp)
        assertEquals("DEVICE_OWNER_STATUS_UNAVAILABLE", result.reasonCode)
        assertFalse(result.policyOperationsImplemented)
    }

    private class FakeDeviceOwnerApi(
        private val result: Boolean = false,
        private val fail: Boolean = false,
    ) : DeviceOwnerApi {
        val queriedPackages = mutableListOf<String>()

        override fun isDeviceOwnerApp(packageName: String): Boolean {
            queriedPackages += packageName
            check(!fail) { "synthetic policy-manager failure" }
            return result
        }
    }
}
