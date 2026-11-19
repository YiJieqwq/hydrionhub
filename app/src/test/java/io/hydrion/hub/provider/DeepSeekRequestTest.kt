package io.hydrion.hub.provider

import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.Role
import org.junit.Assert.*
import org.junit.Test

class DeepSeekRequestTest {
    private val provider = deepSeekProvider
    private val flash = provider.models.first { it.externalModelId == "deepseek-flash" }
    private val catalog = ModelCatalog(listOf(provider))
    private val current = Message(role = Role.User, text = "next")

    @Test fun urlAndHeadersUseTheSelectedHttpsServiceAndSessionKey() {
        for (path in listOf("", "/v1/", "/responses", "/v1/chat/completions")) {
            assertEquals("https://api.deepseek.com/responses", provider.copy(baseUrl = "https://api.deepseek.com$path").deepSeekEndpoint())
        }
        val proxy = "https://proxy.example.test:8443/api/v1/responses"
        assertEquals(proxy, provider.copy(baseUrl = proxy).deepSeekEndpoint())
        for (invalid in listOf("http://api.deepseek.com", "https://key@api.deepseek.com", "https://proxy.test/?secret=a")) {
            assertTrue(runCatching { provider.copy(baseUrl = invalid).deepSeekEndpoint() }.isFailure)
        }
        val model = flash.copy(name = "Flash 别名", profile = flash.profile.copy(headers = mapOf("X-Client" to "android")))
        for ((choice, effort) in listOf("Auto" to null, "Off" to "none", "Minimal" to "low", "Low" to "low",
            "Medium" to "high", "High" to "high", "Extra High" to "high", "Max" to "max")) {
            val request = catalog.responsesRequest(provider, model, listOf(current), "secret", mapOf("effort" to choice))
            assertEquals("deepseek-flash", request.body.getString("model"))
            assertEquals("Flash 别名", request.model)
            assertEquals(effort, request.body.optJSONObject("reasoning")?.getString("effort"))
            assertEquals("Bearer secret", request.headers["Authorization"])
            assertEquals("android", request.headers["X-Client"])
            assertEquals("text/event-stream", request.headers["Accept"])
            assertTrue(request.body.getBoolean("stream"))
            assertFalse(request.body.toString().contains("secret"))
            assertFalse(request.parameters.toString().contains("secret"))
            assertFalse(request.toString().contains("secret"))
        }
        val auth = model.copy(profile = flash.profile.copy(headers = mapOf("authorization" to "Bearer custom")))
        assertEquals("Bearer custom", catalog.responsesRequest(provider, auth, listOf(current), "").headers["authorization"])
        for (bad in listOf("x-test" to "value\nsecret", "Header:Bad" to "secret", "HOST" to "secret")) {
            val invalid = model.copy(profile = model.profile.copy(headers = mapOf(bad)))
            val error = runCatching { catalog.responsesRequest(provider, invalid, listOf(current), "secret") }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains("secret"))
        }
        assertTrue(runCatching { catalog.responsesRequest(provider, model, listOf(current), "") }.isFailure)
    }

    @Test fun historyPreservesStoppedTextAndRealReasoningWhileDroppingFailedExchange() {
        val first = Message(role = Role.User, text = "first")
        val remote = Message(role = Role.Assistant, replyTo = first.id, text = "partial answer", status = Generation.Stopped,
            thought = "reasoned", remoteModel = RemoteModel(provider.id, flash.id))
        val badTurn = Message(role = Role.User, text = "failed question")
        val local = Message(role = Role.User, text = "local question")
        val demo = remote.copy(id = "demo", replyTo = local.id, text = "local answer", thought = "fake thought", remoteModel = null)
        val history = listOf(first, remote, badTurn, Message(role = Role.Assistant, replyTo = badTurn.id,
            text = "error fragment", status = Generation.Error), local, demo, current)
        val input = catalog.responsesRequest(provider, flash, history, "key").body.getJSONArray("input")
        assertEquals(listOf("message", "reasoning", "message", "message", "message", "message"),
            (0 until input.length()).map { input.getJSONObject(it).getString("type") })
        assertEquals("partial answer", input.getJSONObject(2).getString("content"))
        assertEquals("reasoned", input.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(input.toString().contains("fake thought"))
        assertFalse(input.toString().contains("failed question"))
        val disabled = flash.copy(profile = flash.profile.copy(reasoningReplay = "none"))
        assertFalse(catalog.responsesRequest(provider, disabled, history, "key").body.toString().contains("reasoned"))
    }

    @Test fun imageHistoryUsesOnlyUserAttachmentsAndProExplicitlyRejectsThem() {
        val file = Attachment("photo", "photo.png", mimeType = "image/png")
        val photo = current.copy(id = "image-turn", text = "", attachments = listOf(file))
        val answer = Message(role = Role.Assistant, replyTo = photo.id, text = "a cat", attachments = listOf(file))
        val data = "data:image/png;base64,AA=="
        val history = listOf(photo, answer, current)
        var calls = 0
        val input = catalog.responsesRequest(provider, flash, history, "key") { calls++; data }.body.getJSONArray("input")
        assertEquals(1, calls)
        val image = input.getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("input_image", image.getString("type"))
        assertEquals(data, image.getString("image_url"))
        assertEquals("a cat", input.getJSONObject(1).getString("content"))
        val pro = provider.models.first { it.externalModelId == "deepseek-v4-pro" }
        assertTrue(runCatching { catalog.responsesRequest(provider, pro, history, "key") { data } }.isFailure)
        for (invalid in listOf("data:application/pdf;base64,AA==", "data:image/png;base64,+!!!", "content://files/1")) {
            assertTrue(runCatching { catalog.responsesRequest(provider, flash, history, "key") { invalid } }.isFailure)
        }
        val links = flash.copy(profile = flash.profile.copy(imageLinks = true))
        val remoteImage = "https://example.test/foreground.png"
        val linkedBody = catalog.responsesRequest(provider, links, listOf(photo), "key") { remoteImage }.body
        assertEquals(remoteImage, linkedBody.getJSONArray("input").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getString("image_url"))
        assertTrue(runCatching { catalog.responsesRequest(provider, links, listOf(photo), "key") { "https://key@example.test/img.png" } }.isFailure)
        val tooMany = photo.copy(attachments = List(MAX_DEEPSEEK_IMAGES + 1) { file })
        assertTrue(runCatching { catalog.responsesRequest(provider, flash, listOf(tooMany), "key") { data } }.isFailure)
        assertTrue(runCatching { catalog.responsesRequest(provider, flash, listOf(photo, answer.copy(tool = "shell"), current), "key") { data } }.isFailure)
    }
}
