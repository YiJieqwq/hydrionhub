package io.hydrion.hub.model

import io.hydrion.hub.provider.ChatProtocol
import io.hydrion.hub.provider.DEEPSEEK_PROVIDER_ID
import io.hydrion.hub.provider.LocalModelRequest
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ChatProtocolRoutingTest {
    private val model = ProviderModel("same-local-id", "Display name", profile = ModelProfile(externalId = "wire-model"))
    private val question = Message(role = Role.User, text = "hello")

    private fun provider(protocol: ChatProtocol) = Provider(
        protocol.name, Mode.Chat, protocol.name, protocol.label, "https://api.openai.com/v1", listOf(model)
    )

    private fun reply(provider: Provider, savedProtocol: ChatProtocol?): Message {
        val parameters = buildMap {
            put("model", "wire-model")
            if (savedProtocol != null) put(CHAT_PROTOCOL_PARAMETER, savedProtocol.name)
        }
        return Message(role = Role.Assistant, replyTo = question.id, prompt = question.text, parameters = parameters,
            remoteModel = RemoteModel(provider.id, model.id))
    }

    private class Dispatch(var catalog: ModelCatalog) {
        val keys = mutableListOf<String>()
        val prepared = mutableListOf<Pair<ChatProtocol, LocalModelRequest>>()
        val repository = ChatGenerationRepository({ catalog }, { id ->
            keys += id
            "fixture-for-$id"
        }, { _, _ -> emptyMap() }) { protocol, request, message ->
            prepared += protocol to request
            flowOf(message.copy(status = Generation.Completed, text = "answer"))
        }
    }

    @Test fun eachProtocolDispatchesItsOwnRequestAndRetainsItsRouteAcrossHistoryRestore() = runBlocking {
        val providers = ChatProtocol.entries.map(::provider)
        val dispatch = Dispatch(ModelCatalog(providers))
        for ((i, protocol) in ChatProtocol.entries.withIndex()) {
            val source = providers[i]
            val route = RemoteModel(source.id, model.id)
            dispatch.catalog = dispatch.catalog.copy(active = mapOf(Mode.Chat to route))
            val session = ConversationSession(this, dispatch.repository, {})
            session.setDraft(question.text)
            session.send(model.name, reply(source, protocol).parameters, null, null, remoteModel = route)
            val initial = withTimeout(5_000) { session.state.first { !it.isGenerating } }.current
            val original = initial.messages.last()
            assertEquals(Generation.Completed, original.status)
            assertEquals(protocol.name, original.parameters[CHAT_PROTOCOL_PARAMETER])

            // All providers reuse the same local model ID. Only the recorded provider identifies the right route.
            val another = providers[(i + 1) % providers.size]
            dispatch.catalog = dispatch.catalog.copy(active = mapOf(Mode.Chat to RemoteModel(another.id, model.id)))
            val reloaded = ConversationSession(this, dispatch.repository, {})
            reloaded.restore(session.snapshot())
            reloaded.openConversation(initial.id)
            reloaded.regenerate(original.id)
            val retried = withTimeout(5_000) { reloaded.state.first { !it.isGenerating } }.current.messages.last()
            assertEquals(original.id, retried.id)
            assertEquals(route, retried.remoteModel)
            assertEquals(Generation.Completed, retried.status)
            assertEquals(listOf(source.id, source.id), dispatch.keys.takeLast(2))
            val endpoint = when (protocol) {
                ChatProtocol.ResponsesHttp -> "https://api.openai.com/v1/responses"
                ChatProtocol.ChatCompletionsHttp -> "https://api.openai.com/v1/chat/completions"
                ChatProtocol.ResponsesWebSocket -> "wss://api.openai.com/v1/responses"
            }
            for ((selected, request) in dispatch.prepared.takeLast(2)) {
                assertEquals(protocol, selected)
                assertEquals(endpoint, request.url)
                assertEquals("Bearer fixture-for-${source.id}", request.headers["Authorization"])
                assertEquals("wire-model", request.body.getString("model"))
                assertEquals(protocol == ChatProtocol.ChatCompletionsHttp, request.body.has("messages"))
                assertEquals(protocol != ChatProtocol.ChatCompletionsHttp, request.body.has("input"))
                assertFalse(request.body.has(CHAT_PROTOCOL_PARAMETER))
            }
        }
        assertEquals(2 * providers.size, dispatch.prepared.size)
    }

    @Test fun onlyResponsesHttpCanRetryRemoteHistoryWithoutAStoredProtocol() = runBlocking {
        for (protocol in ChatProtocol.entries) {
            val source = provider(protocol)
            val dispatch = Dispatch(ModelCatalog(listOf(source)))
            val result = runCatching {
                dispatch.repository.generate(Mode.Chat, reply(source, null), true, listOf(question)).single()
            }
            if (protocol == ChatProtocol.ResponsesHttp) {
                assertEquals(Generation.Completed, result.getOrThrow().status)
                assertEquals(protocol, dispatch.prepared.single().first)
            } else {
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("协议"))
                assertTrue(dispatch.prepared.isEmpty())
                assertTrue(dispatch.keys.isEmpty())
            }
        }
    }

    @Test fun protocolAndExternalModelChangesBlockHistoricalCompletionsBeforeDispatch() = runBlocking {
        val source = provider(ChatProtocol.ChatCompletionsHttp)
        val historical = reply(source, ChatProtocol.ChatCompletionsHttp)
        val changedModel = model.copy(profile = model.profile.copy(externalId = "replacement"))
        val cases = listOf(source.copy(protocol = ChatProtocol.ResponsesHttp.label) to "协议",
            source.copy(models = listOf(changedModel)) to "模型 ID")
        for ((changed, detail) in cases) {
            val dispatch = Dispatch(ModelCatalog(listOf(changed)))
            val problem = runCatching {
                dispatch.repository.generate(Mode.Chat, historical, true, listOf(question)).single()
            }.exceptionOrNull()
            assertTrue(problem?.message.orEmpty().contains(detail))
            assertTrue(dispatch.keys.isEmpty())
            assertTrue(dispatch.prepared.isEmpty())
        }
    }

    @Test fun deepSeekIdentityAndNonOfficialWebSocketHostsCannotReachAnyTransport() = runBlocking {
        val official = provider(ChatProtocol.ResponsesWebSocket)
        for (source in listOf(official.copy(id = DEEPSEEK_PROVIDER_ID),
            official.copy(baseUrl = "https://api.deepseek.com"),
            official.copy(baseUrl = "https://api.openai.com.invalid/v1"))) {
            val dispatch = Dispatch(ModelCatalog(listOf(source)))
            val failure = runCatching {
                dispatch.repository.generate(Mode.Chat, reply(source, ChatProtocol.ResponsesWebSocket), false,
                    listOf(question)).single()
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("OpenAI 官方"))
            assertFalse(failure?.message.orEmpty().contains("fixture-for-"))
            assertTrue(dispatch.keys.isEmpty())
            assertTrue(dispatch.prepared.isEmpty())
        }
    }

    @Test fun localReplyUsesNeitherProviderCredentialsNorNetworkTransport() = runBlocking {
        val dispatch = Dispatch(ModelCatalog(emptyList()))
        val local = Message(role = Role.Assistant, prompt = question.text, status = Generation.Sending)
        val first = dispatch.repository.generate(Mode.Chat, local, false, listOf(question)).first()
        assertEquals(Generation.Generating, first.status)
        assertTrue(dispatch.keys.isEmpty())
        assertTrue(dispatch.prepared.isEmpty())
    }
}
