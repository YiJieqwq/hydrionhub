package io.hydrion.hub.model

import io.hydrion.hub.provider.LocalModelRequest
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.deepSeekProvider
import io.hydrion.hub.provider.demoCatalog
import io.hydrion.hub.provider.withDeepSeekPreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeepSeekConversationTest {
    private val flash = deepSeekProvider.models.first()
    private val route = RemoteModel(deepSeekProvider.id, flash.id)

    private fun ConversationSession.ask(text: String) {
        setDraft(text)
        send(flash.name, emptyMap(), null, null, remoteModel = route)
    }

    private suspend fun ConversationSession.finished(): Message = withTimeout(5_000) {
        state.first { !it.isGenerating && it.current.messages.isNotEmpty() }.current.messages.last()
    }

    private fun LocalModelRequest.transcript(): List<String> {
        val input = body.getJSONArray("input")
        return (0 until input.length()).map { input.getJSONObject(it).getString("content") }
    }

    @Test fun followsUpAndRetriesHistoricalPromptWithItsOriginalModel() = runBlocking {
        val requests = mutableListOf<LocalModelRequest>()
        var catalog = demoCatalog().withDeepSeekPreset().copy(active = mapOf(Mode.Chat to route))
        val repository = ChatGenerationRepository({ catalog }, { "unit-test-placeholder" }, { _, _ -> emptyMap() }) { _, request, reply ->
            requests += request
            flowOf(reply.copy(text = "answer ${requests.size}", status = Generation.Completed, totalTokens = 45L))
        }
        val session = ConversationSession(this, repository, {})
        session.ask("first")
        val firstReply = session.finished()
        assertEquals(route, firstReply.remoteModel)
        assertNull(firstReply.tool)
        assertEquals(45L, firstReply.totalTokens)
        session.ask("second")
        val secondReply = session.finished()
        assertEquals(listOf("first", "answer 1", "second"), requests.last().transcript())
        val snapshot = session.snapshot()
        assertFalse(snapshot.toString().contains("unit-test-placeholder"))

        catalog = catalog.copy(active = mapOf(Mode.Chat to RemoteModel("demo-chat", "demo-reason")))
        val reopened = ConversationSession(this, repository, {})
        reopened.restore(snapshot)
        reopened.openConversation(session.state.value.current.id)
        reopened.regenerate(firstReply.id)
        reopened.finished()
        assertEquals("deepseek-flash", requests.last().body.getString("model"))
        assertEquals(listOf("first"), requests.last().transcript())
        val messages = reopened.state.value.current.messages
        assertEquals(firstReply.id, messages[1].id)
        assertEquals("answer 3", messages[1].text)
        assertEquals(secondReply, messages.last())
    }

    @Test fun stoppingKeepsPartialOutputAndCancelsTheOriginalStream() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val repository = ChatGenerationRepository({ demoCatalog().withDeepSeekPreset() },
            { "unit-test-placeholder" }, { _, _ -> emptyMap() }) { _, _, reply ->
            flow {
                try {
                    emit(reply.copy(text = "partial", thought = "thinking", status = Generation.Generating))
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val session = ConversationSession(this, repository, {})
        session.ask("stop")
        withTimeout(5_000) { session.state.first { it.current.messages.last().text == "partial" } }
        session.stop()
        withTimeout(5_000) { cancelled.await() }
        val stopped = session.finished()
        assertEquals(Generation.Stopped, stopped.status)
        assertEquals("partial", stopped.text)
        assertEquals("thinking", stopped.thought)
        assertEquals(stopped, session.snapshot().history.single().messages.last())
    }

    @Test fun configuredResponsesRouteUsesItsOwnKeyAndModelOnRetry() = runBlocking {
        val openModel = ProviderModel("open-model", "OpenAI test model")
        val open = Provider("my-openai", Mode.Chat, "OpenAI", "OpenAI Responses",
            "https://api.openai.com/v1", listOf(openModel))
        val openRoute = RemoteModel(open.id, openModel.id)
        var catalog = demoCatalog().withDeepSeekPreset().withProvider(open, emptyMap())
        val sent = mutableListOf<LocalModelRequest>()
        val keys = mutableListOf<String>()
        val repository = ChatGenerationRepository({ catalog }, { provider ->
            keys += provider.id
            "fixture-for-${provider.id}"
        }, { _, _ -> emptyMap() }) { _, prepared, reply ->
            sent += prepared
            flowOf(reply.copy(text = "received", status = Generation.Completed))
        }
        val session = ConversationSession(this, repository, {})
        session.setDraft("hello")
        session.send(openModel.name, mapOf("model" to openModel.name), null, null, remoteModel = openRoute)
        val first = session.finished()
        assertEquals(openRoute, first.remoteModel)
        assertEquals("https://api.openai.com/v1/responses", sent.single().url)
        assertEquals("Bearer fixture-for-my-openai", sent.single().headers["Authorization"])
        assertFalse(sent.single().body.getBoolean("store"))

        catalog = catalog.copy(active = mapOf(Mode.Chat to route))
        session.regenerate(first.id)
        assertEquals(Generation.Completed, session.finished().status)
        assertEquals(listOf(open.id, open.id), keys)
        assertEquals(listOf("hello"), sent.last().transcript())

        catalog = catalog.withProvider(open.copy(models = listOf(openModel.copy(name = "another-model-id"))), emptyMap())
        session.regenerate(first.id)
        assertTrue(session.finished().error.orEmpty().contains("模型 ID"))
        assertEquals(2, keys.size)
        assertEquals(2, sent.size)

        catalog = catalog.withProvider(open.copy(protocol = "OpenAI Compatible"), emptyMap())
        session.regenerate(first.id)
        assertEquals(Generation.Error, session.finished().status)
        assertEquals(2, keys.size)
        assertEquals(2, sent.size)
    }

    @Test fun reportsFailedStreamAndMissingSessionKeyOnRetry() = runBlocking {
        val errors = mutableListOf<Pair<String, String>>()
        var key = "unit-test-placeholder"
        var calls = 0
        val repository = ChatGenerationRepository({ demoCatalog().withDeepSeekPreset() }, { key }, { _, _ -> emptyMap() }) { _, _, reply ->
            calls++
            flow {
                emit(reply.copy(text = "partial", status = Generation.Generating))
                throw IOException("connection interrupted")
            }
        }
        val session = ConversationSession(this, repository, {}, onGenerationError = { id, detail -> errors += id to detail })
        session.ask("hello")
        val failed = session.finished()
        assertEquals(Generation.Error, failed.status)
        assertEquals("partial", failed.text)
        assertEquals(listOf(session.state.value.current.id to "connection interrupted"), errors)
        key = ""
        session.regenerate(failed.id)
        assertEquals(Generation.Error, session.finished().status)
        assertTrue(errors.last().second.contains("API Key"))
        assertEquals(1, calls)
    }
}
