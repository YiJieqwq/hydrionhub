package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderCredentialOriginTest {
    private val first = Provider(
        "fixture", Mode.Chat, "Fixture", ChatProtocol.ChatCompletionsHttp.label,
        "https://first.invalid/v1", listOf(ProviderModel("m", "Model",
            profile = ModelProfile(headers = mapOf("X-Fixture" to "old-header-value"))))
    )

    private class Storage : ProviderCatalogStorage {
        val records = mutableMapOf<String, String>()
        override fun initialCatalog() = ModelCatalog(emptyList())
        override fun read(key: String) = records[key]
        override fun write(key: String, value: String) { records[key] = value }
    }

    @Test fun keepsCredentialsForEquivalentOrigins() {
        val store = ProviderStore(Storage())
        val edited = first.copy(baseUrl = "HTTPS://FIRST.INVALID:443/another/path")
        store.save(first, "first-key", null)
        store.save(edited, "first-key", null)
        assertEquals("first-key", store.sessionKey(first))
        assertEquals("first-key", store.sessionKey(edited))
        assertEquals(first.models, store.catalog.value.providers.single().models)
    }

    @Test fun hostAndPortChangesDropInheritedValuesAndProtectStaleSnapshots() {
        for (url in listOf("https://second.invalid/v1", "https://first.invalid:8443/v1")) {
            val storage = Storage()
            val store = ProviderStore(storage)
            store.save(first, "first-key", null)
            val model = first.models.single()
            val headers = mapOf("x-fixture" to "old-header-value", "X-New" to "new-header-value")
            val edited = first.copy(baseUrl = url, models = listOf(model.copy(profile = model.profile.copy(headers = headers))))
            store.save(edited, "first-key", null)
            val saved = store.catalog.value.providers.single()
            assertEquals("", store.sessionKey(saved.id))
            assertEquals(mapOf("x-fixture" to "", "X-New" to "new-header-value"), saved.models.single().profile.headers)
            store.save(saved, "second-key", null)
            assertEquals("second-key", store.sessionKey(saved))
            assertEquals("", store.sessionKey(first))
            store.editModel(RemoteModel(first.id, model.id), model, first.credentialOrigin())
            assertEquals(saved.models, store.catalog.value.providers.single().models)
            assertFalse(storage.records.values.any { "second-key" in it || "header-value" in it })
            val restored = ProviderStore(storage)
            assertEquals("", restored.sessionKey(saved))
            assertEquals(mapOf("x-fixture" to "", "X-New" to ""), restored.catalog.value.providers.single().models.single().profile.headers)
        }
    }

    @Test fun explicitNewCredentialsAreAvailableOnlyToTheNewOrigin() {
        val store = ProviderStore(Storage())
        store.save(first, "first-key", null)
        val model = first.models.single()
        val replacement = model.copy(profile = model.profile.copy(headers = mapOf("X-Fixture" to "replacement")))
        val edited = first.copy(baseUrl = "https://second.invalid", models = listOf(replacement))
        store.save(edited, "replacement-key", null)
        assertEquals("", store.sessionKey(first))
        assertEquals("replacement-key", store.sessionKey(edited))
        assertEquals(edited.models, store.catalog.value.providers.single().models)
    }

    @Test fun schemeDefaultsAndInvalidAddressesUseTheSameBoundariesAsRequests() {
        assertEquals(first.copy(baseUrl = "https://api.openai.com").credentialOrigin(),
            first.copy(baseUrl = "wss://API.OPENAI.COM:443/v1/responses").credentialOrigin())
        assertEquals(first.copy(baseUrl = DEEPSEEK_BASE_URL).credentialOrigin(),
            first.copy(id = DEEPSEEK_PROVIDER_ID, baseUrl = "").credentialOrigin())
        assertEquals("http://first.invalid:80", first.copy(baseUrl = "http://first.invalid/v1").credentialOrigin())
        for (url in listOf("", "https://user@first.invalid", "https://first.invalid:65536", "https://first.invalid/#fragment")) {
            assertNull(first.copy(baseUrl = url).credentialOrigin())
        }
    }
}
