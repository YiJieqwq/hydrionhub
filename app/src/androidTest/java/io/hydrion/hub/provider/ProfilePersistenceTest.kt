package io.hydrion.hub.provider

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.Mode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfilePersistenceTest {
    @Test fun legacyCatalogGainsDeepSeekOnceAndKeepsUserSettings() {
        val old = demoCatalog().copy(active = mapOf(Mode.Chat to "demo-quick"))
        val legacy = encodeCatalog(old).apply { remove("formatVersion") }
        val migrated = decodeCatalog(legacy.toString())
        assertEquals("demo-quick", migrated.selectedModel(Mode.Chat)?.id)
        assertEquals(deepSeekProvider, migrated.providers.single { it.id == DEEPSEEK_PROVIDER_ID })
        val removed = migrated.copy(providers = migrated.providers.filterNot { it.id == DEEPSEEK_PROVIDER_ID })
        assertTrue(decodeCatalog(encodeCatalog(removed).toString()).providers.none { it.id == DEEPSEEK_PROVIDER_ID })
        val m = deepSeekProvider.models.first()
        val profile = m.profile.copy(contextWindowTokens = 12_345)
        assertEquals(12_345, m.copy(profile = profile.toStoredJson().readProfile()).contextWindowTokens)
        val oldProfile = profile.toStoredJson().apply { remove("contextWindowTokens") }.readProfile()
        assertEquals(1_000_000, m.copy(profile = oldProfile).contextWindowTokens)
    }

    @Test fun loadsPreProfileCatalogAndRoundTripsNewSettingsWithoutHeaderValues() {
        val legacy = encodeCatalog(demoCatalog())
        val legacyModel = legacy.getJSONArray("providers").getJSONObject(0).getJSONArray("models").getJSONObject(0)
        legacyModel.remove("profile")
        assertEquals(ModelProfile(), decodeCatalog(legacy.toString()).selectedModel(Mode.Chat)?.profile)
        val profile = ModelProfile(type = ModelType.Chat, icon = "code", externalId = "local-v2",
            input = setOf("text", "image"), output = setOf("text"), abilities = setOf("tools", "reasoning"),
            dialect = "example", levels = listOf("High", "Max"), defaultLevel = "Max",
            allowDisabling = true, sampling = "default", reasoningReplay = "history", replayField = "summary",
            budget = 4096, imageBudget = 512, headers = mapOf("X-Key" to "secret-for-test"),
            requestBody = """{"nested":{"value":1}}""", imageLinks = true, codeInterpreter = true, builtinImage = true,
            imageModelId = "demo-canvas")
        val original = demoCatalog()
        val p = original.providers.first()
        val model = p.models.first().copy(profile = profile)
        val catalog = original.withProvider(p.copy(models = listOf(model)), mapOf(Mode.Chat to model.id))
        val serialized = encodeCatalog(catalog).toString()
        assertFalse(serialized.contains("secret-for-test"))
        val restored = decodeCatalog(serialized).selectedModel(Mode.Chat)!!
        assertEquals(profile.copy(headers = mapOf("X-Key" to "")), restored.profile)
        val request = catalog.localRequest(model, "hello")
        assertEquals("local-v2", request.body.getString("model"))
        assertEquals("secret-for-test", request.headers["X-Key"])
        assertEquals(1, request.body.getJSONObject("nested").getInt("value"))
        assertFalse(request.parameters.containsValue("secret-for-test"))
    }

    @Test fun budgetsConstrainLocalRequestsAfterJsonOverrides() {
        val catalog = demoCatalog()
        val (base, provider) = catalog.imageTarget(false)!!
        val limited = base.copy(profile = ModelProfile(type = ModelType.Image, budget = 1, imageBudget = 3,
            requestBody = """{"n":99}"""))
        val local = catalog.localRequest(limited, "image", provider.requestBody, parentImageLimit = 2)
        assertEquals(2, local.body.getInt("n"))
        assertEquals("2", local.parameters["n"])
        assertTrue(runCatching { catalog.localRequest(limited, "image", provider.requestBody, requests = 2) }.isFailure)
        assertTrue(runCatching { catalog.localRequest(limited, "image", provider.requestBody, parentImageLimit = 0) }.isFailure)
    }

    @Test fun imageRequestAppliesAdvancedBodyAfterProviderTemplate() {
        val catalog = demoCatalog()
        val (base, provider) = catalog.imageTarget(false)!!
        val model = base.copy(profile = ModelProfile(type = ModelType.Image, externalId = "raster-v2",
            requestBody = """{"size":"1536x1024","n":3,"nested":{"label":"{{literal}}"}}"""))
        val local = catalog.localRequest(model, "云", provider.requestBody)
        assertEquals("raster-v2", local.body.getString("model"))
        assertEquals("1536x1024", local.parameters["size"])
        assertEquals("3", local.parameters["n"])
        assertEquals("{{literal}}", local.body.getJSONObject("nested").getString("label"))
    }
}
