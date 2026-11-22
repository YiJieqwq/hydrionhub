package io.hydrion.hub.model

import io.hydrion.hub.provider.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantProfileTest {
    private val global = RemoteModel("global", "chat")
    private val custom = RemoteModel("owner", "chat")

    @Test fun editingOtherOwnerPreservesConversationAndDraftAndSurvivesReload() = runBlocking {
        fun session() = ConversationSession(this, FakeGenerationRepository {}, {}, newRoutes = { emptyMap() })
        val live = session()
        live.setDraft("fixture draft")
        live.addAttachment(Attachment("file", "local"))
        val original = live.state.value
        live.addAssistant("Other")
        val b = live.state.value.assistants.last().id
        assertTrue(live.updateAssistant(b, "B", "🐈", "Only B", custom, 7))
        assertEquals(original.current, live.state.value.current)
        assertEquals(original.draft, live.state.value.draft)
        assertFalse(live.updateAssistant("removed", "x", "", "", null))
        assertFalse(live.updateAssistant(b, "  ", "", "invalid", null))
        live.selectAssistant(b)
        assertNull(live.state.value.current.modelRoutes?.get(Mode.Chat))
        assertEquals(custom, live.state.value.assistants.single { it.id == b }.chatModel)
        live.selectModel(Mode.Chat, global)
        val pinned = live.state.value.current
        assertTrue(live.updateAssistant(b, "B", "🐈", "B updated", null, 8))
        assertEquals(pinned, live.state.value.current)
        val cold = session()
        cold.restore(decodeWorkspace(JSONObject(encodeWorkspace(live.snapshot()).toString())))
        assertEquals(pinned.id, cold.state.value.current.id)
        assertEquals(8, cold.state.value.assistants.single { it.id == b }.contextLimit)
        assertEquals("B updated", cold.state.value.assistants.single { it.id == b }.systemPrompt)
        cold.selectAssistant(LocalAssistant.Default.id)
        assertEquals(original.current, cold.state.value.current)
        assertEquals(original.draft, cold.state.value.draft)
        assertTrue(cold.copyAssistant(b, "Copy"))
        val copied = cold.state.value.assistants.last()
        assertNotEquals(b, copied.id)
        assertEquals("B updated", copied.systemPrompt)
        assertEquals("🐈", copied.avatarText)
    }

    @Test fun oldProfileAndFreshSingleAssistantResolveDefaultsWithoutReassigningHistory() = runBlocking {
        val old = decodeWorkspace(JSONObject("""{"assistants":[{"id":"default","name":"Old"}]}"""))
        assertEquals(LocalAssistant("default","Old"), old.assistants.single())
        val model = ProviderModel("chat", "Fixture model")
        val first = newProvider(Mode.Chat).copy(id = global.providerId, models = listOf(model))
        val catalog = ModelCatalog(listOf(first, first.copy(id = custom.providerId)), defaults = mapOf(Mode.Chat to global))
        var presented: RemoteModel? = null
        val existing = Conversation(id = "kept", modelRoutes = mapOf(Mode.Chat to global))
        val live = ConversationSession(this, FakeGenerationRepository {}, {}, newRoutes = { emptyMap() },
            showRoutes = { routes, assistantRoute ->
                presented = catalog.forConversation(routes, assistantRoute).selectedEntry(Mode.Chat)?.route
            })
        live.restore(old.copy(assistants = listOf(old.assistants.single().copy(chatModel = custom)), history = listOf(existing)))
        assertNull(live.state.value.current.modelRoutes?.get(Mode.Chat))
        assertEquals(custom, presented)
        live.openConversation("kept")
        assertEquals(global, live.state.value.current.modelRoutes?.get(Mode.Chat))
        assertEquals(global, presented)
    }

    @Test fun explicitChatRouteWinsEvenWhenMissingAndImageSelectionStaysIndependent() {
        val catalog = demoCatalog().withDeepSeekPreset()
        val choices = catalog.entries(Mode.Chat).map { it.route }.distinct()
        val first = choices[0]; val owner = choices[1]; val pin = choices[2]
        val defaults = catalog.withDefault(Mode.Chat, first)!!
        val pins = defaults.initialRoutes() + (Mode.Chat to pin)
        fun resolved(perChat: Boolean, saved: Map<Mode, RemoteModel>, pref: RemoteModel?) =
            defaults.copy(perChatModel = perChat).forConversation(saved, pref).selectedEntry(Mode.Chat)?.route
        assertEquals(pin, resolved(true, pins, owner))
        assertEquals(owner, resolved(false, pins, owner))
        assertEquals(owner, resolved(true, pins - Mode.Chat, owner))
        assertNull(resolved(true, pins + (Mode.Chat to custom), owner))
        assertNull(resolved(false, pins, custom))
        val disabledPin = defaults.copy(providers = defaults.providers.map { provider ->
            if (provider.id == pin.providerId) provider.copy(enabled = false) else provider
        }).normalized()
        assertNull(disabledPin.forConversation(pins, owner).selectedEntry(Mode.Chat))
        val restored = disabledPin.copy(providers = defaults.providers).normalized()
        assertEquals(pin, restored.forConversation(pins, owner).selectedEntry(Mode.Chat)?.route)
        assertEquals(first, resolved(false, pins, null))
        assertEquals(pins[Mode.Image], defaults.forConversation(pins, owner).selectedEntry(Mode.Image)?.route)
    }

    @Test fun queuedChatRequestAndRetryAlwaysUseOwningAssistantPrompt() = runBlocking {
        val catalog = demoCatalog().withDeepSeekPreset()
        val model = deepSeekProvider.models.first()
        val route = RemoteModel(deepSeekProvider.id, model.id)
        val requests = mutableListOf<List<String>>()
        val repo = ChatGenerationRepository({ catalog }, { "fixture" }, { _, _ -> emptyMap() }) { _, req, reply ->
            val input = req.body.getJSONArray("input")
            assertEquals("system", input.getJSONObject(0).getString("role"))
            requests += (0 until input.length()).map { input.getJSONObject(it).getString("content") }
            flowOf(reply.copy(status = Generation.Completed, text = "reply"))
        }
        val live = ConversationSession(this, repo, {})
        live.updateAssistant("default", "A", "", "scope A", null)
        live.addAssistant("B")
        val ownerB = live.state.value.assistants.last().id
        live.updateAssistant(ownerB, "B", "", "scope B", null)
        live.setDraft("A question"); live.send(model.name, emptyMap(), null, null, remoteModel = route)
        val a = live.state.value.current.id
        live.selectAssistant(ownerB)
        live.setDraft("B question"); live.send(model.name, emptyMap(), null, null, remoteModel = route)
        val b = live.state.value.current.id
        withTimeout(5000) { live.state.first { state -> state.history.size == 2 && state.history.none { c -> c.messages.any { it.status.isActive } } } }
        assertEquals(setOf(listOf("scope A","A question"), listOf("scope B","B question")), requests.toSet())
        live.openConversation(a)
        live.regenerate(live.state.value.current.messages.last().id)
        live.openConversation(b)
        withTimeout(5000) { live.state.first { state -> state.history.single { it.id == a }.messages.last().status == Generation.Completed } }
        assertEquals(listOf("scope A","A question"), requests.last())
        assertTrue(live.state.value.history.flatMap { it.messages }.none { it.text.startsWith("scope") })
    }

    @Test fun limitOnlyChangesRequestHistoryAndNeverAppliesProfileToImageMode() = runBlocking {
        val inputs = mutableListOf<Pair<String,List<String>>>()
        val model = object : GenerationRepository {
            override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String) =
                flowOf(request.copy(status = Generation.Completed, text = "answer")).also {
                    inputs += systemPrompt to history.map { it.text }
                }
        }
        val live = ConversationSession(this, model, {})
        live.updateAssistant("default", "A", "", "only chat", null, 1)
        suspend fun send(text: String) {
            live.setDraft(text)
            live.send("fixture", emptyMap(), null, null)
            withTimeout(5000) { live.state.first { it.current.messages.isNotEmpty() && !it.isGenerating } }
        }
        send("first"); send("second")
        assertEquals("only chat" to listOf("second"), inputs.last())
        assertEquals(4, live.state.value.current.messages.size)
        live.newConversation()
        live.setMode(Mode.Image)
        send("draw")
        assertEquals("" to listOf("draw"), inputs.last())
    }

    @Test fun personaHasSystemRoleOnEachSupportedWireAndEmptyPromptKeepsOldBody() {
        val catalog = demoCatalog().withDeepSeekPreset()
        val model = deepSeekProvider.models.first()
        val user = listOf(Message(role = Role.User, text = "original"))
        for (protocol in ChatProtocol.entries) {
            val provider = if (protocol == ChatProtocol.ResponsesWebSocket)
                deepSeekProvider.copy(id = "fixture-openai", name = "OpenAI", baseUrl = "https://api.openai.com/v1", protocol = protocol.label)
                else deepSeekProvider.copy(protocol = protocol.label)
            fun input(prompt: String) = if (protocol == ChatProtocol.ChatCompletionsHttp)
                catalog.chatCompletionsRequest(provider, model, user, "fixture", systemPrompt = prompt).body.getJSONArray("messages")
                else catalog.responsesRequest(provider, model, user, "fixture", systemPrompt = prompt).body.getJSONArray("input")
            assertEquals(1, input("").length())
            val body = input("Owner instructions")
            assertEquals(2, body.length())
            assertEquals("system", body.getJSONObject(0).getString("role"))
            assertEquals("Owner instructions", body.getJSONObject(0).getString("content"))
            assertEquals("user", body.getJSONObject(1).getString("role"))
        }
    }
}
