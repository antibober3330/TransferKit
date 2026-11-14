package com.transferkit.client.deviceowner

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DoBridgeInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun ordinaryInstallIsReadOnlyAndDoesNotProvisionTransferKitAsDeviceOwner() {
        val policyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        assumeFalse("Fixture requires TransferKit not already to be Device Owner", policyManager.isDeviceOwnerApp(context.packageName))

        val result = DoBridge(context).probe()

        assertFalse(result.activeForThisApp)
        assertTrue(result.statusAvailable)
        assertEquals("DEVICE_OWNER_REQUIRED", result.reasonCode)
        assertFalse(result.policyOperationsImplemented)
        assertTrue(result.provisioningWarning.contains("заводських налаштувань"))
        assertFalse(policyManager.isDeviceOwnerApp(context.packageName))
    }
}
