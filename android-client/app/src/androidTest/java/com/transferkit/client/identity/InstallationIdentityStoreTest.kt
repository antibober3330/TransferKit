package com.transferkit.client.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallationIdentityStoreTest {
    @Test
    fun androidKeystoreIdentityPersistsAcrossStoreInstancesAndSignsProofs() {
        val alias = "transferkit.test.identity.${UUID.randomUUID()}"
        val message = "resume challenge for instrumentation".toByteArray()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val firstStore = AndroidInstallationIdentityStore(alias)
            val first = firstStore.getOrCreateIdentity()
            val second = AndroidInstallationIdentityStore(alias).getOrCreateIdentity()

            assertEquals(first.fingerprint, second.fingerprint)
            assertEquals(first.publicKeyBase64, second.publicKeyBase64)
            val proof = firstStore.sign(message)
            assertTrue(AndroidInstallationIdentityStore.verify(first, message, proof))
            assertFalse(AndroidInstallationIdentityStore.verify(first, "other challenge".toByteArray(), proof))
            assertTrue(first.toString().contains("<redacted>"))
        } finally {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }
}
