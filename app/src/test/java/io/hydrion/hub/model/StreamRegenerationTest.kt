package io.hydrion.hub.model

import io.hydrion.hub.provider.ChatProtocol
import io.hydrion.hub.provider.LocalModelRequest
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.ProviderCatalogStorage
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.newProvider
import io.hydrion.hub.ui.sendDraft
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamRegenerationTest {
    @get:Rule val files = TemporaryFolder()

    private fun chunk(delta: String, finish: String = "null") =
        """data: {"choices":[{"index":0,"delta":$delta,"finish_reason":$finish}]}""" + "\n\n"

    private fun transcript(request: LocalModelRequest): List<Pair<String, String>> {
        val messages = request.body.getJSONArray("messages")
        return (0 until messages.length()).map { i ->
            val message = messages.getJSONObject(i)
            message.getString("role") to message.getString("content")
        }
    }

    @Test fun stopPreservesStreamedContentAndRegenerationReplacesOnlyTheVisibleReply() = runBlocking {
        val model = ProviderModel("fixture-model", "Fixture", profile = ModelProfile(externalId = "wire-model"))
        val provider = newProvider(Mode.Chat).copy(name = "Synthetic", baseUrl = "https://fixture.invalid/v1", models = listOf(model))
        val providers = ProviderStore(object : ProviderCatalogStorage {
            private val records = mutableMapOf<String, String>()
            override fun initialCatalog() = ModelCatalog(emptyList())
            override fun read(key: String) = records[key]
            override fun write(key: String, value: String) { records[key] = value }
        })
        providers.save(provider, "synthetic-token", model.id)

        val cancelled = CompletableDeferred<Unit>()
        val requests = mutableListOf<LocalModelRequest>()
        val repository = ChatGenerationRepository({ providers.catalog.value }, providers::sessionKey,
            { attachments, _ -> check(attachments.isEmpty()); emptyMap() },
        ) { protocol, request, reply ->
            check(protocol == ChatProtocol.ChatCompletionsHttp)
            requests += request
            val attempt = requests.size
            val deltas = when (attempt) {
                1 -> chunk("""{"content":"Earlier reply"}""")
                2 -> chunk("""{"reasoning_content":"Partial thought"}""") + chunk("""{"content":"Partial answer"}""") +
                    chunk("""{"content":" unread tail"}""")
                3 -> chunk("""{"reasoning_content":"New thought"}""") + chunk("""{"content":"Replacement"}""")
                4 -> chunk("""{"content":"Follow-up reply"}""")
                else -> error("Unexpected generation")
            }
            val wire = deltas + chunk("{}", "\"stop\"") + "data: [DONE]\n\n"
            flow {
                wire.reader().buffered().use { reader ->
                    readChatCompletionsEvents(reader, reply) { update ->
                        emit(update)
                        if (attempt == 2 && update.text == "Partial answer") {
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled.complete(Unit)
                            }
                        }
                    }
                }
            }
        }

        val historyFile = File(files.root, "conversation-history-v1.json")
        fun session() = ConversationSession(this, repository, { state ->
            publishBackupFile(stageBackupHistory(historyFile, state), historyFile)
        }, newRoutes = providers::newThreadRoutes, showRoutes = providers::showRoutes)
        fun persisted() = decodeWorkspace(JSONObject(historyFile.readText())).history.single()
        suspend fun submit(session: ConversationSession, text: String) {
            session.setDraft(text)
            assertNull(sendDraft(providers, session.state.value, providers.catalog.value, session.state, { false },
                { _, _, _ -> error("Unexpected image request") },
                { name, parameters, image, route -> session.send(name, parameters, null, null, image, route) }))
        }
        suspend fun latest(session: ConversationSession, matches: (Message) -> Boolean): Message =
            withTimeout(5_000) {
                session.state.first { it.current.messages.lastOrNull()?.let(matches) == true }.current.messages.last()
            }

        val chat = session()
        submit(chat, "Earlier question")
        latest(chat) { it.status == Generation.Completed }
        submit(chat, "Current question")
        val partial = latest(chat) { it.status == Generation.Generating && it.text == "Partial answer" }
        assertEquals("Partial thought", partial.thought)
        chat.stop()
        withTimeout(5_000) { cancelled.await() }
        val stopped = partial.copy(status = Generation.Stopped)
        assertEquals(stopped, chat.state.value.current.messages.last())
        assertEquals(stopped, persisted().messages.last())

        chat.regenerate(stopped.id)
        val regenerated = latest(chat) { it.status == Generation.Completed }
        assertEquals("Replacement", regenerated.text)
        assertEquals("New thought", regenerated.thought)
        assertEquals(stopped.id, regenerated.id)
        assertEquals(stopped.replyTo, regenerated.replyTo)
        assertEquals(RemoteModel(provider.id, model.id), regenerated.remoteModel)
        val originalHistory = listOf("user" to "Earlier question", "assistant" to "Earlier reply", "user" to "Current question")
        assertEquals(originalHistory, transcript(requests[1]))
        assertEquals(originalHistory, transcript(requests[2]))
        assertEquals("wire-model", requests[2].body.getString("model"))
        val saved = persisted()
        assertEquals(listOf(stopped, regenerated), saved.versions.getValue(stopped.id).ordered(saved.messages.last()))

        val fresh = session()
        fresh.restore(decodeWorkspace(JSONObject(historyFile.readText())))
        fresh.openConversation(saved.id)
        assertEquals(regenerated, fresh.state.value.current.messages.last())
        fresh.chooseMessageVersion(stopped.id, 0)
        assertEquals(stopped, fresh.state.value.current.messages.last())
        assertEquals(stopped, persisted().messages.last())
        val reopened = session()
        reopened.restore(decodeWorkspace(JSONObject(historyFile.readText())))
        reopened.openConversation(saved.id)
        val restored = reopened.state.value.current
        assertEquals(stopped, restored.messages.last())
        assertEquals(0, restored.versions.getValue(stopped.id).selected)
        reopened.chooseMessageVersion(stopped.id, 1)
        assertEquals(regenerated, reopened.state.value.current.messages.last())
        submit(reopened, "Follow-up")
        latest(reopened) { it.status == Generation.Completed }
        assertEquals(4, requests.size)
        assertEquals(originalHistory + listOf("assistant" to "Replacement", "user" to "Follow-up"), transcript(requests.last()))
    }
}
