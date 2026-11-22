package io.hydrion.hub.model

import io.hydrion.hub.provider.ChatProtocol
import io.hydrion.hub.provider.LocalModelRequest
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.ModelType
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderCatalogStorage
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.credentialOrigin
import io.hydrion.hub.provider.newProvider
import io.hydrion.hub.ui.sendDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatRouteResolutionTest {
    private class Fixture(private val scope: CoroutineScope) {
        val model = ProviderModel("shared-model-id", "Shared model", profile = ModelProfile(externalId = "shared-wire-id"))
        private fun provider(id: String) = newProvider(Mode.Chat).copy(
            id = id, name = id, baseUrl = "https://$id.invalid/v1", models = listOf(model),
        )
        val a = provider("fixture-a")
        val b = provider("fixture-b")
        fun route(provider: Provider) = RemoteModel(provider.id, model.id)

        private val storage = object : ProviderCatalogStorage {
            private val values = mutableMapOf<String, String>()
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String) { values[key] = value }
            override fun initialCatalog() = ModelCatalog(emptyList())
        }
        var store = ProviderStore(storage)
            private set
        fun save(provider: Provider) = store.save(provider, "synthetic-" + provider.id, null)
        fun default(provider: Provider) = assertTrue(store.setDefault(Mode.Chat, route(provider)))
        fun pick(provider: Provider) = store.configure(Mode.Chat, route(provider), emptyMap())

        private val requests = mutableListOf<LocalModelRequest>()
        private var sends = 0
        private fun repository(current: ProviderStore) = ChatGenerationRepository({ current.catalog.value }, current::sessionKey,
            { attachments, _ -> check(attachments.isEmpty()); emptyMap() },
        ) { protocol, request, reply ->
            check(protocol == ChatProtocol.ChatCompletionsHttp)
            requests += request
            flowOf(reply.copy(text = "Reply from " + request.url, status = Generation.Completed))
        }

        private var saved: String? = null
        private fun newSession() = ConversationSession(scope, repository(store),
            { saved = encodeWorkspace(it).toString() }, newRoutes = store::newThreadRoutes, showRoutes = store::showRoutes,
        ).also { session ->
            store.onModelPicked = session::selectModel
            store.onAssistantModelPicked = { route ->
                val state = session.state.value
                val owner = state.assistants.single { it.id == state.current.assistantId }
                check(session.updateAssistant(owner.id, owner.name, owner.avatarText, owner.systemPrompt, route, owner.contextLimit))
            }
            store.showRoutes(session.state.value.current.modelRoutes, null)
        }

        var chat: ConversationSession
            private set
        init {
            save(a)
            save(b)
            default(a)
            chat = newSession()
        }

        fun reopen(id: String) {
            store = ProviderStore(storage)
            for (provider in store.catalog.value.providers) {
                check(listOf(a, b).any { it.id == provider.id && it.baseUrl == provider.baseUrl })
                assertEquals("", store.sessionKey(provider.id))
                save(provider)
            }
            val history = decodeWorkspace(JSONObject(requireNotNull(saved))).withLegacyModelRoutes(store.legacyRoutes())
            chat = newSession()
            chat.restore(history)
            chat.openConversation(id)
            assertEquals(id, chat.state.value.current.id)
        }

        private suspend fun submit() = sendDraft(store, chat.state.value, store.catalog.value, chat.state,
            { false }, { _, _, _ -> error("Unexpected image request") },
        ) { label, parameters, image, selected ->
            sends++
            chat.send(label, parameters, null, null, image, selected)
        }

        suspend fun delivered(question: String, provider: Provider, expectedEffort: String? = null) {
            chat.setDraft(question)
            val previousRequests = requests.size
            assertNull(submit())
            val thread = withTimeout(5_000) { chat.state.first { !it.isGenerating } }.current
            val reply = thread.messages.last()
            assertEquals(Generation.Completed, reply.status)
            assertEquals(route(provider), reply.remoteModel)
            assertEquals(previousRequests + 1, requests.size)
            val request = requests.last()
            val endpoint = provider.baseUrl + "/chat/completions"
            assertEquals(endpoint, request.url)
            assertEquals("shared-wire-id", request.body.getString("model"))
            if (expectedEffort != null) assertEquals(expectedEffort, request.body.getString("reasoning_effort"))
            val messages = request.body.getJSONArray("messages")
            assertEquals(question, messages.getJSONObject(messages.length() - 1).getString("content"))
            assertEquals("Bearer synthetic-" + provider.id, request.headers["Authorization"])
            assertEquals("Reply from $endpoint", reply.text)
        }

        suspend fun blocked() {
            val before = chat.state.value
            val history = saved
            val previousSends = sends
            val previousRequests = requests.size
            assertTrue(before.draft.text.isNotBlank())
            assertFalse(submit().isNullOrBlank())
            assertEquals(before, chat.state.value)
            assertEquals(history, saved)
            assertEquals(previousSends, sends)
            assertEquals(previousRequests, requests.size)
        }
    }

    @Test fun followingThreadsUseTheirOwnAssistantOrGlobalDefaultAfterSwitchAndReopen() = runBlocking {
        val f = Fixture(this)
        val globalThread = f.chat.state.value.current.id
        f.delivered("Initial global", f.a)
        assertNull(f.chat.state.value.current.modelRoutes?.get(Mode.Chat))
        f.default(f.b)
        f.delivered("Changed global", f.b)

        assertTrue(f.chat.addAssistant("Writer"))
        val writer = f.chat.state.value.assistants.single { it.name == "Writer" }
        assertTrue(f.chat.updateAssistant(writer.id, writer.name, "", "Write clearly", f.route(f.a)))
        f.chat.selectAssistant(writer.id)
        val writerThread = f.chat.state.value.current.id
        f.delivered("Writer default", f.a)
        f.pick(f.b)
        f.delivered("Explicit choice", f.b)

        f.chat.newConversation()
        assertNotEquals(writerThread, f.chat.state.value.current.id)
        f.delivered("Fresh writer chat", f.a)
        f.chat.selectAssistant(LocalAssistant.Default.id)
        assertEquals(globalThread, f.chat.state.value.current.id)
        f.delivered("Return to global", f.b)

        f.reopen(writerThread)
        assertEquals(writer.id, f.chat.state.value.current.assistantId)
        assertEquals(f.route(f.b), f.chat.state.value.current.modelRoutes?.get(Mode.Chat))
        f.delivered("Saved choice", f.b)
        f.store.followChatDefault()
        f.delivered("Follow writer again", f.a)
        f.reopen(writerThread)
        assertNull(f.chat.state.value.current.modelRoutes?.get(Mode.Chat))
        f.delivered("Still following writer", f.a)
        f.chat.openConversation(globalThread)
        f.delivered("Still following global", f.b)
    }

    @Test fun inheritedParameterEditsStayWithTheirProviderAcrossTwoConfiguredAssistants() = runBlocking {
        val f = Fixture(this)
        val model = f.model.copy(profile = f.model.profile.copy(type = ModelType.Chat, abilities = setOf("reasoning"),
            levels = listOf("Low", "High"), defaultLevel = "Low"))
        f.save(f.a.copy(models = listOf(model)))
        f.save(f.b.copy(models = listOf(model)))
        f.default(f.b)
        fun assistant(name: String, provider: Provider): String {
            assertTrue(f.chat.addAssistant(name))
            val id = f.chat.state.value.assistants.single { it.name == name }.id
            assertTrue(f.chat.updateAssistant(id, name, "", "", f.route(provider)))
            return id
        }
        val writer = assistant("Writer", f.a)
        val reviewer = assistant("Reviewer", f.b)
        f.chat.selectAssistant(writer)
        val writerThread = f.chat.state.value.current.id
        val originalOwners = f.chat.state.value.assistants
        val defaults = f.store.backupCatalog().defaults
        f.store.configureChatParameters(f.route(f.a), mapOf("effort" to "High"))
        assertNull(f.chat.state.value.current.modelRoutes?.get(Mode.Chat))
        assertEquals(originalOwners, f.chat.state.value.assistants)
        assertEquals(defaults, f.store.backupCatalog().defaults)
        f.delivered("Writer with new effort", f.a, "high")

        f.chat.selectAssistant(reviewer)
        val catalogBeforeStaleEdit = f.store.backupCatalog()
        val chatBeforeStaleEdit = f.chat.state.value
        f.store.configureChatParameters(f.route(f.a), mapOf("effort" to "Low"))
        assertEquals(catalogBeforeStaleEdit, f.store.backupCatalog())
        assertEquals(chatBeforeStaleEdit, f.chat.state.value)
        f.delivered("Reviewer with own effort", f.b, "low")

        f.reopen(writerThread)
        assertNull(f.chat.state.value.current.modelRoutes?.get(Mode.Chat))
        assertEquals(originalOwners, f.chat.state.value.assistants)
        assertEquals(defaults, f.store.backupCatalog().defaults)
        f.delivered("Return to writer", f.a, "high")
    }

    @Test fun legacyStoredRouteSurvivesDefaultChangeAndTemporarySharedMode() = runBlocking {
        val f = Fixture(this)
        val old = decodeWorkspace(JSONObject("""{"history":[{"id":"existing","title":"Old","mode":"Chat","updatedAt":1}]}"""))
        f.chat.restore(old.withLegacyModelRoutes(f.store.legacyRoutes()))
        f.chat.openConversation("existing")
        assertEquals(f.route(f.a), f.chat.state.value.current.modelRoutes?.get(Mode.Chat))
        f.default(f.b)
        f.delivered("Old migrated chat", f.a)
        f.store.setPerChatModel(false)
        f.delivered("Use shared default", f.b)
        assertEquals(f.route(f.a), f.chat.state.value.current.modelRoutes?.get(Mode.Chat))
        f.store.setPerChatModel(true)
        f.reopen("existing")
        f.delivered("Stored route restored", f.a)
        f.chat.newConversation()
        f.delivered("New chat follows global", f.b)
    }

    @Test fun sharedModePickerUpdatesOnlyTheActiveAssistantAndKeepsTheHiddenThreadChoice() = runBlocking {
        val f = Fixture(this)
        assertTrue(f.chat.addAssistant("Writer"))
        val writer = f.chat.state.value.assistants.single { it.name == "Writer" }
        f.chat.selectAssistant(writer.id)
        assertNull(writer.chatModel)
        f.pick(f.b)
        f.delivered("Thread override", f.b)
        val writerThread = f.chat.state.value.current.id
        f.store.setPerChatModel(false)
        f.delivered("Initially follows global", f.a)
        f.pick(f.b)
        assertEquals(f.route(f.b), f.chat.state.value.assistants.single { it.id == writer.id }.chatModel)
        f.delivered("Saved to this assistant", f.b)
        f.chat.selectAssistant(LocalAssistant.Default.id)
        f.delivered("Untouched global", f.a)
        f.chat.selectAssistant(writer.id)
        assertEquals(writerThread, f.chat.state.value.current.id)
        f.delivered("Assistant retained", f.b)
        f.pick(f.a)
        f.delivered("Replace assistant choice", f.a)
        assertEquals(f.route(f.b), f.chat.state.value.current.modelRoutes?.get(Mode.Chat))
        f.store.setPerChatModel(true)
        f.reopen(writerThread)
        f.delivered("Prior thread choice restored", f.b)
        f.store.followChatDefault()
        f.delivered("Use writer choice", f.a)
    }

    @Test fun disabledOrDeletedSelectionRetainsDraftUntilRestoredOrReplaced() = runBlocking {
        val f = Fixture(this)
        f.default(f.b)
        f.pick(f.a)
        f.delivered("Pin A", f.a)
        val id = f.chat.state.value.current.id

        f.chat.setDraft("Keep this draft")
        f.save(f.a.copy(enabled = false))
        f.blocked()
        f.reopen(id)
        assertEquals("Keep this draft", f.chat.state.value.draft.text)
        assertEquals(f.route(f.a), f.chat.state.value.current.modelRoutes?.get(Mode.Chat))
        f.blocked()
        f.save(f.a.copy(models = listOf(f.model.copy(enabled = false))))
        f.blocked()
        f.save(f.a.copy(models = emptyList()))
        f.blocked()
        f.save(f.a)
        f.delivered("Keep this draft", f.a)

        f.chat.setDraft("After deletion")
        f.store.remove(f.a.id)
        f.blocked()
        f.reopen(id)
        assertFalse(f.store.catalog.value.providers.any { it.id == f.a.id })
        f.blocked()
        f.pick(f.b)
        assertEquals("After deletion", f.chat.state.value.draft.text)
        f.delivered("After deletion", f.b)
    }

    @Test fun deletedGlobalDefaultSurvivesRestartUntilUserChoosesAnotherProvider() = runBlocking {
        val f = Fixture(this)
        f.delivered("Follow global A", f.a)
        val id = f.chat.state.value.current.id
        f.chat.setDraft("Keep for another provider")
        f.store.editModel(f.route(f.a), null, f.a.credentialOrigin())
        f.blocked()

        f.reopen(id)
        assertTrue(f.store.catalog.value.providers.single { it.id == f.a.id }.models.isEmpty())
        assertEquals(f.route(f.a), f.store.backupCatalog().defaults[Mode.Chat])
        assertNull(f.chat.state.value.current.modelRoutes?.get(Mode.Chat))
        assertEquals("Keep for another provider", f.chat.state.value.draft.text)
        f.blocked()
        f.default(f.b)
        f.delivered("Keep for another provider", f.b)
    }

    @Test fun unavailableAssistantDefaultBlocksUntilAnExplicitChoiceIsMade() = runBlocking {
        val f = Fixture(this)
        assertTrue(f.chat.addAssistant("Reviewer"))
        val reviewer = f.chat.state.value.assistants.single { it.name == "Reviewer" }
        assertTrue(f.chat.updateAssistant(reviewer.id, reviewer.name, "", "", f.route(f.b)))
        f.chat.selectAssistant(reviewer.id)
        f.delivered("Reviewer default", f.b)
        f.store.remove(f.b.id)
        f.chat.newConversation()
        f.chat.setDraft("Unsent review")
        f.blocked()
        f.pick(f.a)
        f.delivered("Unsent review", f.a)
        f.store.followChatDefault()
        f.chat.setDraft("Reviewer restored")
        f.blocked()
        f.save(f.b)
        f.delivered("Reviewer restored", f.b)
    }
}
