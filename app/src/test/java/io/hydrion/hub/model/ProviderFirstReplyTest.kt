package io.hydrion.hub.model

import io.hydrion.hub.provider.Capability
import io.hydrion.hub.provider.ChatProtocol
import io.hydrion.hub.provider.LocalModelRequest
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.ProviderCatalogStorage
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.ProviderNavigation
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.newProvider
import io.hydrion.hub.provider.route
import io.hydrion.hub.provider.validateProvider
import io.hydrion.hub.ui.sendDraft
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ProviderFirstReplyTest {
    @Test fun savedProviderSendsWithThePickedModelAndRetriesWithTheOriginalRoute() = runBlocking {
        val records = mutableMapOf<String, String>()
        val storage = object : ProviderCatalogStorage {
            override fun read(key: String) = records[key]
            override fun write(key: String, value: String) { records[key] = value }
            override fun initialCatalog() = ModelCatalog(emptyList())
        }
        val originalStore = ProviderStore(storage)
        val blank = newProvider(Mode.Chat)
        assertEquals(ChatProtocol.ChatCompletionsHttp.label, blank.protocol)
        val base = ProviderModel("base", "Base")
        val chosen = ProviderModel("choice", "Picked model", listOf(Capability("tone", "Tone", listOf("plain", "brief"))),
            profile = ModelProfile(externalId = "fixture-wire-model"))
        val defaultRoute = RemoteModel(blank.id, base.id)
        val chosenRoute = RemoteModel(blank.id, chosen.id)
        val token = "synthetic-session-token"

        val editor = ProviderNavigation().apply { open(blank, originalStore) }
        val draft = requireNotNull(editor.edit)
        draft.provider = draft.provider.copy(name = "Fixture provider", baseUrl = "https://fixture.invalid/v1")
        draft.apiKey = token
        draft.finishModel(base, makeDefault = true)
        draft.finishModel(chosen, makeDefault = false)
        assertTrue(originalStore.catalog.value.providers.isEmpty())
        assertNull(validateProvider(draft.provider, emptyMap()))
        originalStore.saveDefaults(draft.provider, draft.apiKey, draft.defaults)

        val store = ProviderStore(storage)
        val saved = store.catalog.value.providers.single()
        assertEquals(draft.provider, saved)
        assertEquals(defaultRoute, store.catalog.value.selectedEntry(Mode.Chat)?.route)
        assertTrue(store.sessionKey(saved.id).isEmpty())
        assertFalse(records.values.any { token in it })

        val requests = mutableListOf<LocalModelRequest>()
        val wire = requireNotNull(ProviderFirstReplyTest::class.java.getResourceAsStream("/chat-completions/openai-stop.sse")).use { it.readBytes() }
        var failNext = false
        val repository = ChatGenerationRepository({ store.catalog.value }, store::sessionKey,
            { attachments, _ -> check(attachments.isEmpty()); emptyMap() },
        ) { protocol, request, reply ->
            check(protocol == ChatProtocol.ChatCompletionsHttp)
            requests += request
            val status = if (failNext) 503 else 200
            failNext = false
            ResponsesTransport { endpoint -> fixtureConnection(endpoint, status, wire) }.streamChatCompletions(request, reply)
        }
        val session = ConversationSession(this, repository, {}, newRoutes = store::newThreadRoutes, showRoutes = store::showRoutes)
        store.onModelPicked = session::selectModel
        store.showRoutes(session.state.value.current.modelRoutes, null)
        suspend fun submit() = sendDraft(store, session.state.value, store.catalog.value, session.state, { false },
            { _, _, _ -> error("Unexpected image request") },
            { label, parameters, image, route -> session.send(label, parameters, null, null, image, route) })
        suspend fun settled() = withTimeout(5_000) { session.state.first { !it.isGenerating } }.current

        store.configure(Mode.Chat, chosenRoute, mapOf("tone" to "brief"))
        assertEquals(chosenRoute, session.state.value.current.modelRoutes?.get(Mode.Chat))
        val restoredSelection = ProviderStore(storage).catalog.value
        assertEquals(chosenRoute, restoredSelection.selectedEntry(Mode.Chat)?.route)
        assertEquals("brief", restoredSelection.selection(Mode.Chat).parameters["tone"])
        session.setDraft("First question")
        val before = session.state.value
        assertNotNull(submit())
        assertEquals(before, session.state.value)
        assertTrue(requests.isEmpty())

        store.saveDefaults(saved, token, draft.defaults)
        assertNull(submit())
        val first = settled()
        assertEquals(listOf(Role.User, Role.Assistant), first.messages.map { it.role })
        val (question, answer) = first.messages
        assertEquals("First question", question.text)
        assertEquals(question.id, answer.replyTo)
        assertEquals(chosen.name, answer.model)
        assertEquals(chosenRoute, answer.remoteModel)
        assertEquals(Generation.Completed, answer.status)
        assertEquals("Schedule confirmed.", answer.text)
        assertEquals("", session.state.value.draft.text)
        val request = requests.single()
        assertEquals("https://fixture.invalid/v1/chat/completions", request.url)
        assertEquals("fixture-wire-model", request.body.getString("model"))
        assertEquals("First question", request.body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("brief", request.parameters["tone"])
        assertEquals("Bearer $token", request.headers["Authorization"])

        session.setDraft("Second question")
        failNext = true
        assertNull(submit())
        val failed = settled().messages.last()
        assertEquals(Generation.Error, failed.status)
        assertTrue(failed.error.orEmpty().contains("synthetic backend failure"))
        store.configure(Mode.Chat, defaultRoute, emptyMap())
        assertEquals(defaultRoute, store.catalog.value.selectedEntry(Mode.Chat)?.route)
        session.regenerate(failed.id)
        val retried = settled()
        val reply = retried.messages.last()
        assertEquals(3, requests.size)
        assertEquals(request.url, requests[1].url)
        assertEquals(requests[1].url, requests[2].url)
        assertEquals(requests[1].body.getJSONArray("messages").toString(), requests[2].body.getJSONArray("messages").toString())
        assertEquals("fixture-wire-model", requests[2].body.getString("model"))
        assertEquals("brief", requests[2].parameters["tone"])
        assertEquals(listOf("First question", "Second question"), retried.messages.filter { it.role == Role.User }.map { it.text })
        assertEquals(failed.id, reply.id)
        assertEquals(failed.replyTo, reply.replyTo)
        assertEquals(chosenRoute, reply.remoteModel)
        assertEquals(Generation.Completed, reply.status)
        assertEquals("Schedule confirmed.", reply.text)
    }

    private fun fixtureConnection(endpoint: URL, status: Int, wire: ByteArray) = object : HttpURLConnection(endpoint) {
        override fun connect() { error("Fixture streams require no connection") }
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getResponseCode() = status
        override fun getContentType() = "text/event-stream"
        override fun getInputStream() = wire.inputStream()
        override fun getErrorStream() = """{"error":{"message":"synthetic backend failure"}}""".byteInputStream()
    }
}
