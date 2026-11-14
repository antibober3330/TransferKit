package com.transferkit.client.shizuku

import android.content.pm.PackageManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPermissionMessageTest {
    @Test
    fun everyRequestOutcomeHasClearLocalizedMessageAndDoesNotClaimAnOperationRan() {
        ShizukuPermissionRequestResult.entries.forEach { result ->
            val message = shizukuPermissionRequestMessage(result)
            assertTrue(message.isNotBlank())
            assertTrue("Messages should be localized", message.any { it in 'А'..'я' || it == 'і' || it == 'ї' })
            assertFalse("Permission request must never imply a settings operation ran", message.contains("виконано"))
        }
    }

    @Test
    fun unsupportedApiDoesNotContactShizuku() {
        val api = FakeShizukuApi()

        val probe = ShizukuBridge(api, Build.VERSION_CODES.Q).probe()
        val request = ShizukuBridge(api, Build.VERSION_CODES.Q).requestPermission(7)

        assertEquals(ShizukuState.UNSUPPORTED_API, probe.state)
        assertEquals("API_UNAVAILABLE", probe.reasonCode)
        assertEquals(ShizukuPermissionRequestResult.UNSUPPORTED_API, request)
        assertEquals(0, api.pingCalls)
        assertEquals(0, api.permissionChecks)
        assertEquals(0, api.permissionRequests)
    }

    @Test
    fun missingServiceFailsClosedWithoutPermissionCheckOrRequest() {
        val api = FakeShizukuApi(serviceRunning = false)

        val probe = ShizukuBridge(api, Build.VERSION_CODES.R).probe()
        val request = ShizukuBridge(api, Build.VERSION_CODES.R).requestPermission(8)

        assertEquals(ShizukuState.SERVICE_NOT_RUNNING, probe.state)
        assertEquals("SHIZUKU_REQUIRED", probe.reasonCode)
        assertEquals(ShizukuPermissionRequestResult.SERVICE_NOT_RUNNING, request)
        assertEquals(0, api.permissionChecks)
        assertEquals(0, api.permissionRequests)
    }

    @Test
    fun alreadyGrantedPermissionIsReportedWithoutRepeatedPrompt() {
        val api = FakeShizukuApi(permissionResult = PackageManager.PERMISSION_GRANTED)

        val probe = ShizukuBridge(api, Build.VERSION_CODES.R).probe()
        val request = ShizukuBridge(api, Build.VERSION_CODES.R).requestPermission(9)

        assertEquals(ShizukuState.AVAILABLE, probe.state)
        assertEquals("OK", probe.reasonCode)
        assertEquals(ShizukuPermissionRequestResult.PERMISSION_ALREADY_GRANTED, request)
        assertEquals(0, api.permissionRequests)
    }

    @Test
    fun modernServerGetsOneExplicitPermissionRequest() {
        val api = FakeShizukuApi()

        val request = ShizukuBridge(api, Build.VERSION_CODES.R).requestPermission(10)

        assertEquals(ShizukuPermissionRequestResult.REQUEST_SENT, request)
        assertEquals(1, api.permissionRequests)
        assertEquals(10, api.lastRequestCode)
    }

    @Test
    fun legacyServerIsRejectedWithoutSendingRequest() {
        val api = FakeShizukuApi(preV11 = true)

        val request = ShizukuBridge(api, Build.VERSION_CODES.R).requestPermission(11)

        assertEquals(ShizukuPermissionRequestResult.LEGACY_SERVER_UNSUPPORTED, request)
        assertEquals(0, api.permissionRequests)
    }

    @Test
    fun requestExceptionIsReportedAndNotTreatedAsSuccess() {
        val api = FakeShizukuApi(failRequest = true)

        val request = ShizukuBridge(api, Build.VERSION_CODES.R).requestPermission(12)

        assertEquals(ShizukuPermissionRequestResult.REQUEST_FAILED, request)
        assertEquals(1, api.permissionRequests)
    }

    private class FakeShizukuApi(
        private val serviceRunning: Boolean = true,
        private val permissionResult: Int = PackageManager.PERMISSION_DENIED,
        private val preV11: Boolean = false,
        private val failRequest: Boolean = false,
    ) : ShizukuApi {
        var pingCalls = 0
            private set
        var permissionChecks = 0
            private set
        var permissionRequests = 0
            private set
        var lastRequestCode: Int? = null
            private set

        override fun pingBinder(): Boolean {
            pingCalls++
            return serviceRunning
        }

        override fun checkSelfPermission(): Int {
            permissionChecks++
            return permissionResult
        }

        override fun isPreV11(): Boolean = preV11

        override fun requestPermission(requestCode: Int) {
            permissionRequests++
            lastRequestCode = requestCode
            check(!failRequest) { "synthetic request failure" }
        }
    }
}
