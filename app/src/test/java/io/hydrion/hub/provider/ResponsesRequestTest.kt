package io.hydrion.hub.provider

import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.Role
import org.junit.Assert.*
import org.junit.Test

class ResponsesRequestTest {
    private val model = ProviderModel("local-model-id", "Display name",
        profile = ModelProfile(type = ModelType.Chat, externalId = "test-chat",
            input = setOf("text", "image"), abilities = setOf("reasoning")))
    private val provider = Provider("custom", Mode.Chat, "My service", "OpenAI Responses",
        "https://api.openai.com", listOf(model))
    private val catalog = ModelCatalog(listOf(provider))
    private val user = Message(role = Role.User, text = "Hi")

    @Test fun onlyExplicitChatProvidersUseAnIntentionalHttpsDestination() {
        val local = demoCatalog().providers.first()
        assertNotNull(provider.copy(protocol = "OpenAI Compatible").chatProtocolIssue())
        assertNull(provider.chatProtocolIssue())
        assertNull(local.copy(baseUrl = provider.baseUrl).chatProtocolIssue())
        assertNull(provider.copy(protocol = "local", baseUrl = "https://api.openai.com.example.test").chatProtocolIssue())
        for (offline in listOf(local, local.copy(protocol = provider.protocol, baseUrl = provider.baseUrl),
            provider.copy(protocol = "OpenAI Compatible"), provider.copy(mode = Mode.Image))) {
            assertNull(offline.chatProtocol())
            assertTrue(runCatching { catalog.responsesRequest(offline, model, listOf(user), "fixture") }.isFailure)
        }
        for (path in listOf("", "/", "/v1/", "/responses/", "/v1/responses", "/v1/chat/completions")) {
            val openAi = provider.copy(baseUrl = "https://api.openai.com$path")
            assertEquals(ChatProtocol.ResponsesHttp, openAi.chatProtocol())
            assertEquals("https://api.openai.com/v1/responses", openAi.chatEndpoint())
        }
        val proxy = "https://proxy.example.test:8443/tenant/v1"
        assertEquals("$proxy/responses", provider.copy(baseUrl = proxy).chatEndpoint())
        assertEquals("https://api.deepseek.com/responses", deepSeekProvider.copy(baseUrl = "").chatEndpoint())
        assertTrue(runCatching {
            catalog.responsesRequest(deepSeekProvider.copy(baseUrl = "https://API.OPENAI.COM./v1"),
                deepSeekProvider.models.first(), listOf(user), "deepseek-fixture")
        }.isFailure)
        for (invalid in listOf("", "http://example.test/v1", "https://user@example.test/v1",
            "https://example.test/v1?param=1", "https://example.test/v1#fragment", "https://example.test/v1/../x")) {
            assertTrue(runCatching { provider.copy(baseUrl = invalid).chatEndpoint() }.isFailure)
        }
    }

    @Test fun openAiPreservesEffortAndOmitsReasoningOptionsForPlainTextModels() {
        for ((selected, expected) in listOf("Auto" to null, "Off" to "none", "Minimal" to "minimal",
            "Low" to "low", "Medium" to "medium", "High" to "high", "Extra High" to "xhigh", "Max" to "max")) {
            val request = catalog.responsesRequest(provider, model, listOf(user), "fixture", mapOf("effort" to selected))
            val reasoning = request.body.getJSONObject("reasoning")
            assertEquals(expected, reasoning.opt("effort"))
            assertEquals(if (expected == "none") null else "auto", reasoning.opt("summary"))
            assertEquals("test-chat", request.body.getString("model"))
            assertEquals("Bearer fixture", request.headers["Authorization"])
            assertEquals("text/event-stream", request.headers["Accept"])
            assertTrue(request.body.getBoolean("stream"))
            assertFalse(request.body.getBoolean("store"))
        }
        val plain = model.copy(profile = model.profile.copy(abilities = emptySet()))
        val body = catalog.responsesRequest(provider, plain, listOf(user), "fixture").body
        assertFalse(body.has("reasoning"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("background"))
    }

    @Test fun openAiReplaysVisibleTurnsAndOnlyUsersImagesAcrossProviderChanges() {
        val photo = Attachment("photo", "photo.png", mimeType = "image/png")
        val first = user.copy(id = "first", text = "describe", attachments = listOf(photo))
        val partial = Message(role = Role.Assistant, replyTo = first.id, status = Generation.Stopped,
            text = "partial", thought = "private DeepSeek reasoning", attachments = first.attachments,
            remoteModel = RemoteModel(DEEPSEEK_PROVIDER_ID, "deepseek-flash"))
        val failed = user.copy(id = "failed", text = "omit this")
        val error = Message(role = Role.Assistant, replyTo = failed.id, status = Generation.Error)
        val history = listOf(first, partial, failed, error, user)
        val fixtureImage = "data:image/png;base64,AA=="
        val opened = mutableListOf<Attachment>()
        val body = catalog.responsesRequest(provider, model, history, "fixture") {
            opened.add(it)
            fixtureImage
        }.body
        val input = body.getJSONArray("input")
        assertEquals(listOf(photo), opened)
        assertEquals(3, input.length())
        val parts = input.getJSONObject(0).getJSONArray("content")
        assertEquals("describe", parts.getJSONObject(0).getString("text"))
        assertEquals("input_image", parts.getJSONObject(1).getString("type"))
        assertEquals(fixtureImage, parts.getJSONObject(1).getString("image_url"))
        assertEquals("partial", input.getJSONObject(1).getString("content"))
        assertEquals(user.text, input.getJSONObject(2).getString("content"))
        assertFalse(input.toString().contains(partial.thought))
        assertFalse(input.toString().contains(failed.text))
        val textOnly = model.copy(profile = model.profile.copy(input = setOf("text")))
        assertTrue(runCatching { catalog.responsesRequest(provider, textOnly, history, "fixture") { fixtureImage } }.isFailure)
    }
}
