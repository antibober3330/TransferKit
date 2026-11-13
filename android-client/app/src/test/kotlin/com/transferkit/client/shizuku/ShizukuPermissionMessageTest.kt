package com.transferkit.client.shizuku

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
}
