package io.hydrion.hub.model

import io.hydrion.hub.provider.Capability
import io.hydrion.hub.provider.ChatProtocol
import io.hydrion.hub.provider.LocalModelRequest
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.ModelDiscovery
import io.hydrion.hub.provider.ModelImportSelection
import io.hydrion.hub.provider.ModelListResult
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.ProviderCatalogStorage
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.ProviderNavigation
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.addDiscoveredModels
import io.hydrion.hub.provider.externalModelId
import io.hydrion.hub.provider.newProvider
import io.hydrion.hub.provider.route
import io.hydrion.hub.provider.validateProvider
import io.hydrion.hub.ui.sendDraft
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ProviderFirstReplyTest {
    @Test fun savedProviderSendsWithThePickedModelAndRetriesWithTheOriginalRoute() = runBlocking {
        val storage = CatalogStorage()
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
        assertFalse(storage.records.values.any { token in it })

        val chat = ChatFixture(this, store)
        val session = chat.session
        val requests = chat.requests

        store.configure(Mode.Chat, chosenRoute, mapOf("tone" to "brief"))
        assertEquals(chosenRoute, session.state.value.current.modelRoutes?.get(Mode.Chat))
        val restoredSelection = ProviderStore(storage).catalog.value
        assertEquals(chosenRoute, restoredSelection.selectedEntry(Mode.Chat)?.route)
        assertEquals("brief", restoredSelection.selection(Mode.Chat).parameters["tone"])
        session.setDraft("First question")
        val before = session.state.value
        assertNotNull(chat.submit())
        assertEquals(before, session.state.value)
        assertTrue(requests.isEmpty())

        store.saveDefaults(saved, token, draft.defaults)
        assertNull(chat.submit())
        val first = chat.settled()
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
        chat.failNext = true
        assertNull(chat.submit())
        val failed = chat.settled().messages.last()
        assertEquals(Generation.Error, failed.status)
        assertTrue(failed.error.orEmpty().contains("synthetic backend failure"))
        store.configure(Mode.Chat, defaultRoute, emptyMap())
        assertEquals(defaultRoute, store.catalog.value.selectedEntry(Mode.Chat)?.route)
        session.regenerate(failed.id)
        val retried = chat.settled()
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

    @Test fun discoveredModelSurvivesReimportAndSendsOnlyToItsOwnProvider() = runBlocking {
        val storage = CatalogStorage()
        val original = ProviderStore(storage)
        val provider = newProvider(Mode.Chat).copy(name = "Discovery fixture", baseUrl = "https://catalog.invalid/v1")
        val draft = requireNotNull(ProviderNavigation().apply { open(provider, original) }.edit)
        val token = "catalog-fixture-token"
        draft.apiKey = token
        val existing = ProviderModel("saved-local-id", "My existing model", profile = ModelProfile(externalId = "vendor/old"))
        draft.finishModel(existing, makeDefault = true)

        val wireId = "vendor/chat-42"
        val payload = """{"data":[{"id":"vendor/old"},{"id":"$wireId"},{"id":"vendor/skip"}]}"""
        val discoveryRequests = mutableListOf<Request>()
        val discovery = ModelDiscovery(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            discoveryRequests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        })
        val picker = ModelImportSelection()
        withTimeout(5_000) {
            picker.load { discovery.fetch(draft.provider, draft.keyForCurrentAddress(draft.provider)) }
        }
        assertEquals(ModelListResult.Loaded(listOf("vendor/old", wireId, "vendor/skip")), picker.result)
        val get = discoveryRequests.single()
        assertEquals("GET", get.method)
        assertEquals("https://catalog.invalid/v1/models", get.url.toString())
        assertEquals("Bearer $token", get.header("Authorization"))

        val configured = draft.provider.models.map { it.externalModelId }.toSet()
        picker.select("vendor/old", checked = true, configured = configured)
        picker.select(wireId, checked = true, configured = configured)
        assertEquals(setOf(wireId), picker.selected)
        picker.confirm(configured) { ids -> draft.replaceModels(addDiscoveredModels(draft.provider, ids)) }
        assertEquals(setOf("vendor/old", wireId), draft.provider.models.map { it.externalModelId }.toSet())
        assertEquals(existing, draft.provider.models.single { it.externalModelId == "vendor/old" })
        val imported = draft.provider.models.single { it.externalModelId == wireId }
        val customized = imported.copy(name = "Display alias", profile = imported.profile.copy(contextWindowTokens = 4096))
        draft.finishModel(customized, makeDefault = false)
        val route = RemoteModel(provider.id, imported.id)
        assertTrue(original.catalog.value.providers.isEmpty())
        original.saveDefaults(draft.provider, draft.apiKey, draft.defaults)

        val otherModel = ProviderModel("unrelated-local-id", customized.name, profile = ModelProfile(externalId = wireId))
        val other = newProvider(Mode.Chat).copy(name = "Other fixture", baseUrl = "https://other.invalid/v1", models = listOf(otherModel))
        original.saveDefaults(other, "", mapOf(Mode.Chat to otherModel.id))

        val store = ProviderStore(storage)
        assertEquals(RemoteModel(other.id, otherModel.id), store.catalog.value.selectedEntry(Mode.Chat)?.route)
        val saved = store.catalog.value.providers.single { it.id == provider.id }
        val reopened = requireNotNull(ProviderNavigation().apply { open(saved, store) }.edit)
        reopened.apiKey = token
        reopened.replaceModels(addDiscoveredModels(reopened.provider, listOf("vendor/old", wireId)))
        assertEquals(listOf(existing, customized), reopened.provider.models)
        val providerBefore = reopened.provider
        val defaultsBefore = reopened.defaults.toMap()
        val recordsBefore = storage.records.toMap()
        val catalogBefore = store.catalog.value
        val selectedBefore = RemoteModel(other.id, otherModel.id)
        val savedIds = providerBefore.models.map { it.externalModelId }.toSet()
        var additions = 0
        val onAdd: (Collection<String>) -> Unit = { ids ->
            additions++
            reopened.replaceModels(addDiscoveredModels(reopened.provider, ids))
        }
        fun assertUnchanged() {
            assertEquals(0, additions)
            assertEquals(providerBefore, reopened.provider)
            assertEquals(defaultsBefore, reopened.defaults)
            assertEquals(token, reopened.apiKey)
            assertEquals(catalogBefore, store.catalog.value)
            assertEquals(recordsBefore, storage.records)
            assertEquals(selectedBefore, store.catalog.value.selectedEntry(Mode.Chat)?.route)
        }

        val pending = ModelImportSelection()
        withTimeout(5_000) {
            pending.load { discovery.fetch(reopened.provider, reopened.keyForCurrentAddress(reopened.provider)) }
        }
        pending.select("vendor/skip", checked = true, configured = savedIds)
        assertEquals(setOf("vendor/skip"), pending.selected)
        pending.discard()
        pending.confirm(savedIds, onAdd)
        assertUnchanged()

        val failed = ModelImportSelection()
        failed.load { throw java.io.IOException("Synthetic model list failure") }
        assertEquals(ModelListResult.Failed, failed.result)
        failed.select("vendor/skip", checked = true, configured = savedIds)
        failed.confirm(savedIds, onAdd)
        assertUnchanged()

        store.saveDefaults(reopened.provider, reopened.apiKey, reopened.defaults)
        assertEquals(other, store.catalog.value.providers.single { it.id == other.id })

        val chat = ChatFixture(this, store)
        store.configure(Mode.Chat, route, emptyMap())
        assertEquals(route, store.catalog.value.selectedEntry(Mode.Chat)?.route)
        chat.session.setDraft("First discovered-model question")
        assertNull(chat.submit())
        val messages = chat.settled().messages
        assertEquals(listOf(Role.User, Role.Assistant), messages.map { it.role })
        assertEquals("First discovered-model question", messages.first().text)
        assertEquals(messages.first().id, messages.last().replyTo)
        assertEquals(route, messages.last().remoteModel)
        assertEquals(Generation.Completed, messages.last().status)
        assertEquals("Schedule confirmed.", messages.last().text)
        val sent = chat.requests.single()
        assertEquals("https://catalog.invalid/v1/chat/completions", sent.url)
        assertEquals(wireId, sent.body.getString("model"))
        assertEquals("Bearer $token", sent.headers["Authorization"])
    }

    private class CatalogStorage : ProviderCatalogStorage {
        val records = mutableMapOf<String, String>()
        override fun read(key: String) = records[key]
        override fun write(key: String, value: String) { records[key] = value }
        override fun initialCatalog() = ModelCatalog(emptyList())
    }

    private inner class ChatFixture(scope: CoroutineScope, private val store: ProviderStore) {
        val requests = mutableListOf<LocalModelRequest>()
        var failNext = false
        private val wire = ("""
            data: {"choices":[{"index":0,"delta":{"content":"Schedule "}}]}

            data: {"choices":[{"index":0,"delta":{"content":"confirmed."},"finish_reason":"stop"}]}

            data: [DONE]
        """.trimIndent() + "\n\n").toByteArray()

        private val repository = ChatGenerationRepository({ store.catalog.value }, store::sessionKey,
            { attachments, _ -> check(attachments.isEmpty()); emptyMap() },
        ) { protocol, request, reply ->
            check(protocol == ChatProtocol.ChatCompletionsHttp)
            requests += request
            val status = if (failNext) 503 else 200
            failNext = false
            ResponsesTransport { endpoint -> fixtureConnection(endpoint, status, wire) }.streamChatCompletions(request, reply)
        }
        val session = ConversationSession(scope, repository, {}, newRoutes = store::newThreadRoutes, showRoutes = store::showRoutes)

        init {
            store.onModelPicked = session::selectModel
            store.showRoutes(session.state.value.current.modelRoutes, null)
        }

        suspend fun submit() = sendDraft(store, session.state.value, store.catalog.value, session.state, { false },
            { _, _, _ -> error("Unexpected image request") },
            { label, parameters, image, route -> session.send(label, parameters, null, null, image, route) })

        suspend fun settled() = withTimeout(5_000) { session.state.first { !it.isGenerating } }.current
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
