package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import org.junit.Assert.*
import org.junit.Test

class OfficialImageGenerationTest {
    private val model = ProviderModel("shared", "Photographs", profile = ModelProfile(type = ModelType.Image,
        externalId = "gpt-image-2.6-flare", headers = mapOf("OpenAI-Project" to "fixture")))
    private val provider = Provider("images", Mode.Image, "Images", "OpenAI Images", "https://api.openai.com/v1", listOf(model))
    private val route = RemoteModel(provider.id, model.id)
    private val params = mapOf("model" to model.externalModelId, "n" to "1", "size" to "1024x1024", "quality" to "high")

    @Test fun savedRouteHonorsEditedPromptAndOriginalParametersAfterSelectionChanges() {
        val other = provider.copy(id = "other", models = listOf(model.copy(profile = model.profile.copy(headers = emptyMap()))))
        val chat = provider.copy(id = "chat", mode = Mode.Chat, models = listOf(model.copy(profile = ModelProfile(type = ModelType.Chat))))
        val catalog = ModelCatalog(listOf(chat, other, provider), active = mapOf(Mode.Image to RemoteModel(other.id, model.id)))
        val lookedUp = mutableListOf<String>()
        val req = catalog.prepareOfficialImageGeneration(route, "revised composition", params) {
            lookedUp.add(it)
            "fixture-session"
        }
        assertEquals(listOf(provider.id), lookedUp)
        assertEquals("https://api.openai.com/v1/images/generations", req.url)
        assertEquals("revised composition", req.body.getString("prompt"))
        assertEquals(model.externalModelId, req.body.getString("model"))
        assertEquals("high", req.body.getString("quality"))
        assertEquals(1, req.body.getInt("n"))
        assertEquals("Bearer fixture-session", req.headers["Authorization"])
        assertEquals("fixture", req.headers["OpenAI-Project"])
        assertEquals(params, req.parameters)
    }

    @Test fun invalidTargetModelOrParametersNeverReadsCredentials() {
        fun rejects(p: Provider = provider, saved: Map<String,String> = params) {
            var read = false
            assertTrue(runCatching {
                ModelCatalog(listOf(p)).prepareOfficialImageGeneration(route, "prompt", saved) { read = true; "fixture" }
            }.isFailure)
            assertFalse(read)
        }
        for (bad in listOf("", "http://api.openai.com/v1", "https://proxy.example/v1", "https://api.openai.com./v1",
            "https://api.openai.com:444/v1", "https://api.openai.com:/v1", "https://user@api.openai.com/v1",
            "https://api.openai.com/v1?x", "https://api.openai.com/v1#x", "https://api.openai.com/v1/images/edits",
            "https://api.openai.com/v1/%69mages/generations", "https://api.openai.com/v1/../v1")) {
            rejects(provider.copy(baseUrl = bad))
        }
        assertTrue(runCatching { ModelCatalog(listOf(provider.copy(baseUrl = ""))).imageInvocation("test") }.isFailure)
        rejects(provider.copy(models = listOf(model.copy(profile = model.profile.copy(headers = mapOf("X-Other" to "other"))))))
        rejects(provider.copy(enabled = false))
        rejects(provider.copy(id = "other"))
        rejects(provider.copy(protocol = "OpenAI Responses"))
        rejects(provider.copy(mode = Mode.Chat))
        rejects(provider.copy(models = listOf(model.copy(enabled = false))))
        rejects(provider.copy(models = listOf(model.copy(id = "other"))))
        rejects(provider.copy(models = listOf(model.copy(profile = model.profile.copy(externalId = "gpt-image-2")))))
        for (unsupported in listOf("gpt-image-1", "dall-e-3", "gpt-image-2.6-custom", "gpt-6.2")) {
            rejects(provider.copy(models = listOf(model.copy(profile = model.profile.copy(externalId = unsupported)))),
                params + ("model" to unsupported))
        }
        rejects(saved = params + ("secret-header" to "fixture"))
        rejects(saved = params + ("n" to "0"))
        rejects(saved = params + ("size" to "99x99"))
        rejects(saved = params + ("quality" to "wrong"))
    }

    @Test fun customSizePixelBoundsAndOlderModelQualityAreCheckedOnReplay() {
        fun request(id: String, size: String, quality: String): LocalModelRequest {
            val configured = model.copy(profile = model.profile.copy(externalId = id))
            val catalog = ModelCatalog(listOf(provider.copy(models = listOf(configured))))
            val saved = mapOf("model" to id, "n" to "1", "size" to size, "quality" to quality)
            return catalog.prepareOfficialImageGeneration(route, "prompt", saved) { "fixture" }
        }
        for (id in listOf("gpt-image-2", "gpt-image-2.5-flare", "gpt-image-2.6-sunburst", "gpt-image-2.6-flare")) {
            assertThrows(IllegalArgumentException::class.java) { request(id, "1024x624", "high") }
            for (size in listOf("1024x640", "3840x2160")) {
                assertEquals(size, request(id, size, "high").body.getString("size"))
            }
            assertThrows(IllegalArgumentException::class.java) { request(id, "3840x2176", "high") }
        }
        for (id in listOf("gpt-image-2", "gpt-image-1.5", "gpt-image-1-mini")) {
            assertEquals("high", request(id, "1024x1024", "high").body.getString("quality"))
            for (quality in listOf("xhigh", "max")) {
                assertThrows(IllegalArgumentException::class.java) { request(id, "1024x1024", quality) }
            }
        }
    }

    @Test fun missingOrDisabledImagePreferenceNeverChoosesTheDemoImplicitly() {
        val local = demoCatalog()
        for (preference in listOf(false, true)) {
            val selected = if (preference) local.copy(defaults = local.defaults + (Mode.Image to route))
                else local.copy(active = mapOf(Mode.Image to route))
            for (providers in listOf(local.providers, local.providers + provider.copy(enabled = false),
                local.providers + provider.copy(models = emptyList()),
                local.providers + provider.copy(models = listOf(model.copy(profile = model.profile.copy(type = ModelType.Embedding)))),
                local.providers + provider.copy(models = listOf(model.copy(enabled = false))))) {
                val unavailable = selected.copy(providers = providers).normalized()
                assertEquals(route, if (preference) unavailable.defaults[Mode.Image] else unavailable.active[Mode.Image])
                assertNull(unavailable.selectedEntry(Mode.Image))
                assertTrue(runCatching { unavailable.imageInvocation("hi") }.isFailure)
                val restored = unavailable.copy(providers = local.providers + provider).normalized()
                assertEquals(route, restored.selectedEntry(Mode.Image)?.route)
            }
        }
        assertEquals("demo-image", local.selectedEntry(Mode.Image)?.second?.id)
    }
}
