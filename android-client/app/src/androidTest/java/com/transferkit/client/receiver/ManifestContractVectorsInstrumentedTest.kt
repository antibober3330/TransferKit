package com.transferkit.client.receiver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestContractVectorsInstrumentedTest {
    @Test
    fun sharedManifestVectorsMatchAndroidValidation() {
        val fixtureText = InstrumentationRegistry.getInstrumentation().context.assets
            .open("manifest-contract-vectors.json")
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
        val fixture = Json.parseToJsonElement(fixtureText).jsonObject

        fixture.getValue("valid").jsonArray.forEach { vector ->
            val document = vector.jsonObject.getValue("document")
            val payload = Json.encodeToString<JsonElement>(document).toByteArray(StandardCharsets.UTF_8)
            assertTrue(
                vector.jsonObject.getValue("name").toString(),
                runCatching { IncomingManifestValidator.parse(payload) }.isSuccess,
            )
        }
        fixture.getValue("invalid").jsonArray.forEach { vector ->
            val document = vector.jsonObject.getValue("document")
            val payload = Json.encodeToString<JsonElement>(document).toByteArray(StandardCharsets.UTF_8)
            assertFalse(
                vector.jsonObject.getValue("name").toString(),
                runCatching { IncomingManifestValidator.parse(payload) }.isSuccess,
            )
        }
    }
}
