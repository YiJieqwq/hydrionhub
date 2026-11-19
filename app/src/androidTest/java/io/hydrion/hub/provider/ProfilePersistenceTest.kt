package io.hydrion.hub.provider

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfilePersistenceTest {
    @Test fun legacyCatalogGainsDeepSeekOnceAndKeepsUserSettings() {
        val old = demoCatalog().copy(active = mapOf(Mode.Chat to RemoteModel("demo-chat", "demo-quick")))
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

    @Test fun readsLegacyIdCollisionsOnceAndRoundTripsDisabledScopedOverrides() {
        val first = deepSeekProvider.copy(id = "one", enabled = false)
        val second = first.copy(id = "two", enabled = true)
        val model = first.models.first()
        val legacy = encodeCatalog(ModelCatalog(listOf(first, second))).apply {
            put("formatVersion", 4)
            put("active", JSONObject().put("Chat", model.id))
            put("defaults", JSONObject().put("Chat", model.id))
            put("overrides", JSONObject().put(model.id, JSONObject().put("effort", "High")))
        }
        val migrated = decodeCatalog(legacy.toString())
        val routeTwo = RemoteModel(second.id, model.id)
        val routeOne = RemoteModel(first.id, model.id)
        assertEquals(routeTwo, migrated.active[Mode.Chat])
        assertEquals(routeTwo, migrated.defaults[Mode.Chat])
        assertEquals("High", migrated.overrides[routeTwo]?.get("effort"))
        assertNull(migrated.overrides[routeOne])
        val off = migrated.copy(providers = listOf(first, second.copy(models = second.models.map { it.copy(enabled = false) })),
            overrides = migrated.overrides + (routeOne to mapOf("effort" to "Max"))).normalized()
        val saved = decodeCatalog(encodeCatalog(off).toString())
        assertEquals(off, saved)
        assertNull(saved.selectedEntry(Mode.Chat))
        val on = saved.copy(providers = listOf(first.copy(enabled = true), second)).normalized()
        assertEquals(routeTwo, on.selectedEntry(Mode.Chat)?.route)
        assertEquals("High", on.selection(Mode.Chat).parameters["effort"])
        assertEquals("Max", on.parameters(first.models.first(), first)["effort"])
    }

    @Test fun preparesImagesForTheSelectedProviderWithValidatedWireFields() {
        val image = demoCatalog().selectedModel(Mode.Image)!!.copy(profile = ModelProfile(
            type = ModelType.Image, externalId = "gpt-image-1.5", imageBudget = 2,
            headers = mapOf("X-Image-Request" to "fixture"), requestBody = """{"n":3}"""))
        val p = Provider("images-a", Mode.Image, "A", "OpenAI Images", "https://api.openai.com", listOf(image))
        val b = p.copy(id = "images-b", baseUrl = "https://api.openai.com:443/v1/images/generations",
            models = listOf(image.copy(profile = image.profile.copy(headers = mapOf("X-B" to "fixture")))))
        val catalog = ModelCatalog(listOf(p, b), active = mapOf(Mode.Image to RemoteModel(b.id, image.id)))
        val result = catalog.prepareImageGeneration("a \"quote\"")
        assertEquals("https://api.openai.com/v1/images/generations", result.url)
        assertEquals("a \"quote\"", result.body.getString("prompt"))
        assertEquals("gpt-image-1.5", result.body.getString("model"))
        assertEquals(2, result.body.getInt("n"))
        assertEquals("auto", result.body.getString("quality"))
        assertFalse(result.body.has("input_modalities"))
        assertFalse(result.body.has("abilities"))
        assertEquals(mapOf("X-B" to "fixture"), result.headers)
        assertFalse(result.parameters.containsValue("fixture"))
        assertEquals("https://api.openai.com/v1/images/generations", p.imageEndpoint())
        for (bad in listOf("http://example.com", "https://user@example.com", "https://example.com/path?query=x",
            "https://images.example/prefix/images/generations")) {
            assertTrue(runCatching { p.copy(baseUrl = bad).imageEndpoint() }.isFailure)
        }
        assertTrue(runCatching { catalog.prepareImageGeneration("  ") }.isFailure)
        assertEquals("", catalog.imageRequest("  ").url)
    }

    @Test fun invocationStoresOnlyWireOptionsAndRejectsReplacedPromptsAndModels() {
        val (localImage, localProvider) = demoCatalog().selectedEntry(Mode.Image)!!
        val onlineImage = localImage.copy(profile = localImage.profile.copy(type = ModelType.Image,
            externalId = "gpt-image-1.5", headers = mapOf("X-Private" to "fixture-secret")))
        val provider = localProvider.copy(id = "online", models = listOf(onlineImage), baseUrl = "https://api.openai.com/v1")
        val catalog = ModelCatalog(listOf(provider))
        val invocation = catalog.imageInvocation("my prompt")
        assertEquals(RemoteModel(provider.id, onlineImage.id), invocation.remoteModel)
        assertEquals(setOf("model", "size", "quality", "n"), invocation.parameters.keys)
        assertFalse(invocation.parameters.values.contains("my prompt"))
        assertFalse(invocation.parameters.values.contains("fixture-secret"))
        assertNull(demoCatalog().imageInvocation("demo").remoteModel)
        val wrong = listOf("""{"model":"gpt-image-2","prompt":"{{prompt}}"}""", """{"prompt":"override"}""",
            """{"prompt":"{{prompt}}","image":"ref"}""")
        for (template in wrong) {
            val changed = ModelCatalog(listOf(provider.copy(requestBody = template)))
            assertTrue(runCatching { changed.imageInvocation("my prompt") }.isFailure)
        }
    }

    @Test fun wirePreparationConvertsDimensionsRejectsUnsupportedFieldsAndKeepsTheMockTemplate() {
        val (model, owner) = demoCatalog().selectedEntry(Mode.Image)!!
        fun catalog(extra: String, id: String = "gpt-image-1.5", width: Int = 1536): ModelCatalog {
            val profile = ModelProfile(type = ModelType.Image, externalId = id, requestBody = extra)
            val template = """{"prompt":"{{prompt}}","width":$width,"height":1024,"aspect":"3:2","quality":"High"}"""
            return ModelCatalog(listOf(owner.copy(id = "custom-images", baseUrl = "https://api.openai.com/v1",
                requestBody = template, models = listOf(model.copy(profile = profile)))))
        }
        val valid = catalog("{}")
        val wire = valid.prepareImageGeneration("a lake")
        assertEquals("1536x1024", wire.body.getString("size"))
        assertEquals("high", wire.body.getString("quality"))
        assertEquals(setOf("model", "prompt", "n", "size", "quality"), wire.body.keys().asSequence().toSet())
        assertEquals("1600x1024", catalog("{}", "gpt-image-2", 1600).prepareImageGeneration("lake").body.getString("size"))
        assertTrue(runCatching { catalog("{}", "gpt-image-2.6-flare", 1600).prepareImageGeneration("lake") }.isFailure)
        assertTrue(runCatching { catalog("{}", "gpt-image-2-future", 1600).prepareImageGeneration("lake") }.isFailure)
        for (extra in listOf("""{"size":"1024x1024"}""", """{"quality":"max"}""", """{"tool":"local"}""")) {
            val configured = catalog(extra)
            assertTrue(runCatching { configured.prepareImageGeneration("a lake") }.isFailure)
            assertEquals("", configured.imageRequest("a lake").url)
        }
    }

    @Test fun unsupportedFutureFileIsPreservedAndCredentialsStayWithTheirProviderForThisRun() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "provider-fixture-" + UUID.randomUUID()
        val fixture = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = app.getSharedPreferences(file, mode)
        }
        val prefs = fixture.getSharedPreferences("catalog", Context.MODE_PRIVATE)
        try {
            val (model, base) = demoCatalog().selectedEntry(Mode.Image)!!
            val models = listOf(model.copy(profile = model.profile.copy(type = ModelType.Image, externalId = "gpt-image-1.5")))
            val one = base.copy(id = "p1", models = models, baseUrl = "https://api.openai.com/v1")
            val two = base.copy(id = "p2", models = models, baseUrl = "https://api.openai.com")
            val origin = encodeCatalog(ModelCatalog(listOf(one, two))).put("formatVersion", 99)
                .put("futureSetting", "preserve-me").toString()
            prefs.edit().putString("catalog", origin).commit()
            val store = ProviderStore(fixture)
            store.save(one, "local-test-one", null)
            store.save(two, "local-test-two", null)
            store.configure(Mode.Image, RemoteModel(two.id, model.id), mapOf("quality" to "High"))
            val prepared = store.prepareImageGeneration("scene")
            assertEquals("https://api.openai.com/v1/images/generations", prepared.url)
            assertEquals("Bearer local-test-two", prepared.headers["Authorization"])
            assertEquals("application/json", prepared.headers["Accept"])
            assertEquals(origin, prefs.getString("catalog", null))
            assertTrue(prefs.all.values.none { value -> value.toString().contains("local-test-") })
            val restored = ProviderStore(fixture)
            assertEquals(RemoteModel(two.id, model.id), restored.catalog.value.selectedEntry(Mode.Image)?.route)
            assertEquals("", restored.sessionKey(two.id))
            assertTrue(runCatching { restored.prepareImageGeneration("scene") }.isFailure)
        } finally {
            app.deleteSharedPreferences(file)
        }
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
            requestBody = """{"nested":{"value":1}}""", imageLinks = true, codeInterpreter = true)
        val original = demoCatalog()
        val p = original.providers.first()
        val model = p.models.first().copy(profile = profile)
        val catalog = original.withProvider(p.copy(models = listOf(model)), mapOf(Mode.Chat to model.id))
        val serialized = encodeCatalog(catalog).toString()
        assertFalse(serialized.contains("secret-for-test"))
        val oldCatalog = JSONObject(serialized)
        val oldProvider = oldCatalog.getJSONArray("providers").getJSONObject(0)
        oldProvider.put("builtinImage", true)
        oldProvider.getJSONArray("models").getJSONObject(0).getJSONObject("profile")
            .put("builtinImage", true).put("imageModelId", "retired-target")
        val migrated = decodeCatalog(oldCatalog.toString())
        val rewritten = encodeCatalog(migrated).getJSONArray("providers").getJSONObject(0)
        assertFalse(rewritten.has("builtinImage"))
        val storedProfile = rewritten.getJSONArray("models").getJSONObject(0).getJSONObject("profile")
        assertFalse(storedProfile.has("builtinImage"))
        assertFalse(storedProfile.has("imageModelId"))
        assertEquals(catalog.selectedEntry(Mode.Image), migrated.selectedEntry(Mode.Image))
        val restored = migrated.selectedModel(Mode.Chat)!!
        assertEquals(profile.copy(headers = mapOf("X-Key" to "")), restored.profile)
        val request = catalog.localRequest(model, "hello")
        assertEquals("local-v2", request.body.getString("model"))
        assertEquals("secret-for-test", request.headers["X-Key"])
        assertEquals(1, request.body.getJSONObject("nested").getInt("value"))
        assertFalse(request.parameters.containsValue("secret-for-test"))
    }

    @Test fun budgetsConstrainLocalRequestsAfterJsonOverrides() {
        val catalog = demoCatalog()
        val (base, provider) = catalog.selectedEntry(Mode.Image)!!
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
        val (base, provider) = catalog.selectedEntry(Mode.Image)!!
        val model = base.copy(profile = ModelProfile(type = ModelType.Image, externalId = "raster-v2",
            requestBody = """{"size":"1536x1024","n":3,"nested":{"label":"{{literal}}"}}"""))
        val selectedProvider = provider.copy(id = "custom-image", models = listOf(model.copy(id = "custom-render")),
            requestBody = """{"model":"{{model}}","prompt":"{{prompt}}","source":"selected-provider"}""")
        val configured = catalog.withProvider(selectedProvider, mapOf(Mode.Image to "custom-render"))
        val local = configured.imageRequest("云")
        assertEquals("selected-provider", local.body.getString("source"))
        assertEquals("云", local.body.getString("prompt"))
        assertEquals("raster-v2", local.body.getString("model"))
        assertEquals("1536x1024", local.parameters["size"])
        assertEquals("3", local.parameters["n"])
        assertEquals("{{literal}}", local.body.getJSONObject("nested").getString("label"))
    }
}
