package io.hydrion.hub.provider

import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.Role
import org.junit.Assert.*
import org.junit.Test

class ChatCompletionsRequestTest {
    private val deepSeek = deepSeekProvider.copy(protocol = ChatProtocol.ChatCompletionsHttp.label)
    private val model = deepSeek.models.first()
    private val open = Provider("open", Mode.Chat, "Test", deepSeek.protocol, "https://api.openai.com/v1", listOf(model))
    private val catalog = ModelCatalog(listOf(deepSeek, open))
    private val prompt = Message(role = Role.User, text = "current")

    @Test fun buildsChatHistoryWithNestedImagePartsAndIncludesUsage() {
        val image = Attachment("photo", "photo.png", mimeType = "image/png")
        val first = prompt.copy(id = "first", text = "describe", attachments = listOf(image))
        val reply = Message(role = Role.Assistant, replyTo = first.id, status = Generation.Stopped, text = "partial",
            thought = "old thought", attachments = listOf(image), remoteModel = RemoteModel(deepSeek.id, model.id))
        val history = listOf(first, reply, prompt)
        val source = "data:image/png;base64,AA=="
        for (provider in listOf(open, deepSeek)) {
            val reads = mutableListOf<Attachment>()
            val request = catalog.chatCompletionsRequest(provider, model, history, "fixture") { reads.add(it); source }
            assertEquals(listOf(image), reads)
            val body = request.body
            assertEquals(model.externalModelId, body.getString("model"))
            assertTrue(body.getBoolean("stream"))
            assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
            assertFalse(body.has("input"))
            assertFalse(body.has("tools"))
            assertFalse(body.toString().contains(reply.thought))
            val messages = body.getJSONArray("messages")
            assertEquals(3, messages.length())
            assertFalse(messages.getJSONObject(0).has("type"))
            val parts = messages.getJSONObject(0).getJSONArray("content")
            assertEquals("text", parts.getJSONObject(0).getString("type"))
            assertEquals("image_url", parts.getJSONObject(1).getString("type"))
            assertEquals(source, parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
            assertEquals("partial", messages.getJSONObject(1).getString("content"))
            assertEquals("current", messages.getJSONObject(2).getString("content"))
        }
    }

    @Test fun deepSeekAndOpenAiHaveDistinctThinkingControls() {
        val history = listOf(prompt)
        val high = mapOf("effort" to "Extra High")
        val off = mapOf("effort" to "Off")
        val openBody = catalog.chatCompletionsRequest(open, model, history, "fixture", high).body
        assertEquals("xhigh", openBody.getString("reasoning_effort"))
        assertFalse(openBody.has("thinking"))
        assertFalse(openBody.getBoolean("store"))
        val deep = catalog.chatCompletionsRequest(deepSeek, model, history, "fixture", high).body
        assertEquals("high", deep.getString("reasoning_effort"))
        assertEquals("enabled", deep.getJSONObject("thinking").getString("type"))
        val disabled = catalog.chatCompletionsRequest(deepSeek, model, history, "fixture", off).body
        assertEquals("disabled", disabled.getJSONObject("thinking").getString("type"))
        assertFalse(disabled.has("reasoning_effort"))
        val custom = open.copy(baseUrl = "https://api.deepseek.com")
        val official = catalog.chatCompletionsRequest(custom, model, history, "fixture", off).body
        assertEquals("disabled", official.getJSONObject("thinking").getString("type"))
    }

    @Test fun webSocketKeepsTheResponsesBodyWithoutHttpTransportFields() {
        val ws = open.copy(protocol = ChatProtocol.ResponsesWebSocket.label)
        val request = catalog.responsesRequest(ws, model, listOf(prompt), "fixture")
        assertEquals("wss://api.openai.com/v1/responses", request.url)
        assertFalse(request.body.has("stream"))
        assertFalse(request.body.has("background"))
        assertFalse(request.body.getBoolean("store"))
        assertEquals(prompt.text, request.body.getJSONArray("input").getJSONObject(0).getString("content"))
        assertTrue(runCatching { catalog.chatCompletionsRequest(ws, model, listOf(prompt), "fixture") }.isFailure)
        assertTrue(runCatching { catalog.responsesRequest(open, model, listOf(prompt), "fixture") }.isFailure)
    }
}
