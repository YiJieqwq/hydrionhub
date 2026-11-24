package io.hydrion.hub.model

import io.hydrion.hub.provider.deepSeekProvider
import io.hydrion.hub.provider.demoCatalog
import io.hydrion.hub.provider.withDeepSeekPreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ReplyTranslationTest {
    private val prompt = Message(role = Role.User, text = "previous question")
    private val source = Message(role = Role.Assistant, replyTo = prompt.id, text = "Translate only **this**.",
        thought = "separate reasoning", usage = TokenUsage(inputTokens = 0))
    private val chat = Conversation(messages = listOf(prompt, source))
    private val route = RemoteModel("fixture", "translator")

    @Test fun duplicateTapStreamsOnceAndSavedTranslationFollowsOnlyItsOwnRevision() = runBlocking {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val api = object : GenerationRepository {
            override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String) = flow {
                calls++
                assertEquals(route, request.remoteModel)
                assertEquals(listOf(source.text), history.map { it.text })
                assertEquals(Role.User, history.single().role)
                assertTrue(history.single().attachments.isEmpty())
                assertTrue(history.single().thought.isEmpty())
                assertTrue(systemPrompt.contains("中文"))
                assertFalse(retry)
                emit(request.copy(status = Generation.Generating, text = "译"))
                gate.await()
                emit(request.copy(status = Generation.Completed, text = "译文"))
            }
        }
        val session = ConversationSession(this, api, {})
        session.restore(SavedWorkspace(listOf(chat)))
        session.openConversation(chat.id)
        val service = ReplyTranslations(this, session, api)
        service.start(chat.id, source, "中文", route)
        service.start(chat.id, source, "中文", route)
        yield()
        assertEquals("译", service.progress.value[source.id]?.text)
        assertNull(session.state.value.current.messages.last().translation)
        gate.complete(Unit)
        yield()
        assertEquals(1, calls)
        assertTrue(service.progress.value.isEmpty())
        val translated = session.state.value.current.messages.last()
        assertEquals(ReplyTranslation("中文", "译文"), translated.translation)
        assertEquals(source.text, translated.text)
        assertEquals(source.usage, translated.usage)
        session.editMessage(source.id, "New version")
        assertNull(session.state.value.current.messages.last().translation)
        assertNull(session.state.value.current.messages.last().timing)
        val saved = decodeWorkspace(encodeWorkspace(session.snapshot())).history.single()
        assertEquals(translated, saved.withVersion(source.id, 0).messages.last())
        session.chooseMessageVersion(source.id, 0)
        service.clear(chat.id, session.state.value.current.messages.last())
        assertNull(session.state.value.current.messages.last().translation)
    }

    @Test fun selectedProviderBuildsAnIsolatedRequestAndOnlyItsTransportIsReplaced() = runBlocking {
        val catalog = demoCatalog().withDeepSeekPreset()
        val target = RemoteModel(deepSeekProvider.id, deepSeekProvider.models.first().id)
        val api = ChatGenerationRepository({ catalog }, { "synthetic-placeholder" }, { attachments, _ ->
            assertTrue(attachments.isEmpty())
            emptyMap()
        }) { _, prepared, reply ->
            val input = prepared.body.getJSONArray("input")
            assertEquals(2, input.length())
            assertEquals("system", input.getJSONObject(0).getString("role"))
            assertTrue(input.getJSONObject(0).getString("content").contains("English"))
            assertEquals(source.text, input.getJSONObject(1).getString("content"))
            assertFalse(prepared.body.has("tools"))
            assertFalse(prepared.body.toString().contains(prompt.text))
            assertFalse(prepared.body.toString().contains(source.thought))
            flowOf(reply.copy(text = "Streamed translation", status = Generation.Completed))
        }
        val session = ConversationSession(this, api, {})
        session.restore(SavedWorkspace(listOf(chat)))
        session.openConversation(chat.id)
        ReplyTranslations(this, session, api).start(chat.id, source, "English", target)
        val updated = withTimeout(5_000) { session.state.first { it.current.messages.last().translation != null } }
        assertEquals(ReplyTranslation("English", "Streamed translation"), updated.current.messages.last().translation)
        assertEquals(chat.messages.size, updated.current.messages.size)
    }

    @Test fun staleCompletionAfterEditAndPageCancellationCannotChangeVisibleOrArchivedReply() = runBlocking {
        var gate = CompletableDeferred<Unit>()
        val api = object : GenerationRepository {
            override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String) = flow {
                gate.await()
                emit(request.copy(status = Generation.Completed, text = "late translation"))
            }
        }
        val session = ConversationSession(this, api, {})
        session.restore(SavedWorkspace(listOf(chat)))
        session.openConversation(chat.id)
        val service = ReplyTranslations(this, session, api)
        service.start(chat.id, source, "English", route)
        yield()
        session.editMessage(source.id, "edited")
        gate.complete(Unit)
        yield()
        assertTrue(session.state.value.current.allStoredMessages().all { it.translation == null })
        gate = CompletableDeferred()
        service.start(chat.id, session.state.value.current.messages.last(), "English", route)
        yield()
        service.retain(chat.id, emptyList())
        gate.complete(Unit)
        yield()
        assertTrue(service.progress.value.isEmpty())
        assertTrue(session.state.value.current.allStoredMessages().all { it.translation == null })
    }
}
