package io.hydrion.hub.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ChatMessageActionsTest {
    private val a = Message(role = Role.User, text = "question A")
    private val b = Message(role = Role.Assistant, text = "answer A", replyTo = a.id,
        thought = "original reasoning", totalTokens = 7, usage = TokenUsage(4, 3, 7))
    private val c = Message(role = Role.User, text = "question B")
    private val d = Message(role = Role.Assistant, text = "answer B", replyTo = c.id)
    private val route = RemoteModel("fixture", "text")
    private val original = Conversation(title = "Sample", messages = listOf(a, b, c, d),
        environmentId = "env", modelRoutes = mapOf(Mode.Chat to route))

    @Test fun forkCopiesOnlyVisiblePrefixAndReroutesItsLinksWithoutChangingSourceOrDraft() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        val thread = original.appendVersion(b, b.copy(text = "chosen")).copy(contextStart = a.id)
        val draft = Draft("unfinished", listOf(Attachment("attachment", "notes")))
        session.restore(SavedWorkspace(listOf(thread), mapOf(thread.id to draft)))
        session.openConversation(thread.id)
        assertTrue(session.forkMessage(b.id, "New branch"))
        val branch = session.state.value.current
        assertEquals(listOf(a.text, "chosen"), branch.messages.map { it.text })
        assertEquals(branch.messages[0].id, branch.messages[1].replyTo)
        assertEquals(branch.messages[0].id, branch.contextStart)
        assertTrue(branch.versions.isEmpty())
        assertTrue(branch.messages.none { it.id in thread.messages.map(Message::id) })
        assertNotEquals(thread.id, branch.id)
        assertEquals(thread.environmentId, branch.environmentId)
        assertEquals(thread.modelRoutes, branch.modelRoutes)
        assertEquals(thread.assistantId, branch.assistantId)
        assertEquals(Draft(), session.state.value.draft)
        session.editMessage(branch.messages[1].id, "branch only")
        val fresh = ConversationSession(this, FakeGenerationRepository {}, {})
        fresh.restore(decodeWorkspace(encodeWorkspace(session.snapshot())))
        fresh.openConversation(thread.id)
        assertEquals(thread, fresh.state.value.current)
        assertEquals(draft, fresh.state.value.draft)
        fresh.openConversation(branch.id)
        assertEquals("branch only", fresh.state.value.current.messages.last().text)
        fresh.chooseMessageVersion(branch.messages[1].id, 0)
        assertEquals("chosen", fresh.state.value.current.messages.last().text)
    }

    @Test fun branchEndingAtPromptCanAnswerIndependentlyAndExcludesLaterContextStart() = runBlocking {
        val source = original.copy(contextStart = c.id)
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(SavedWorkspace(listOf(source)))
        session.openConversation(source.id)
        assertTrue(session.forkMessage(a.id, "from first question"))
        val fork = session.state.value.current
        assertNull(fork.contextStart)
        val question = fork.messages.single()
        session.regenerate(question.id)
        yield()
        assertEquals(question.id, session.state.value.current.messages.last().replyTo)
        assertEquals(Generation.Completed, session.state.value.current.messages.last().status)
        assertEquals(source, session.state.value.history.single { it.id == source.id })
    }

    @Test fun resendAndRegenerateUseOnlyTheChosenPromptAndKeepFollowingTurns() = runBlocking {
        val requests = mutableListOf<List<Message>>()
        val release = CompletableDeferred<Unit>()
        val repository = object : GenerationRepository {
            override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>) = flow {
                assertTrue(retry)
                requests += history
                release.await()
                emit(request.copy(status = Generation.Completed, text = "response to " + history.last().text))
            }
        }
        val session = ConversationSession(this, repository, {})
        session.restore(SavedWorkspace(listOf(original)))
        session.openConversation(original.id)
        assertTrue(session.editMessage(a.id, "edited A"))
        session.regenerate(a.id)
        yield()
        assertEquals(listOf("edited A"), requests.single().map { it.text })
        val active = session.state.value.current
        assertFalse(session.forkMessage(c.id, "too early"))
        assertFalse(session.editMessage(c.id, "too early"))
        session.chooseMessageVersion(b.id, 0)
        session.setContextStart(c.id)
        session.deleteChatVersion(b.id)
        session.regenerate(a.id)
        assertEquals(active, session.state.value.current)
        release.complete(Unit)
        yield()
        val latest = session.state.value.current
        assertEquals(listOf(c, d), latest.messages.drop(2))
        assertEquals(b, latest.versions.getValue(b.id).others.single())
        session.chooseMessageVersion(b.id, 0)
        assertEquals(b, session.state.value.current.messages[1])
        session.setContextStart(c.id)
        session.regenerate(b.id)
        yield()
        assertEquals(1, requests.size)
        session.regenerate(d.id)
        yield()
        assertEquals(listOf(c), requests.last())
        session.setContextStart(null)
        session.regenerate(d.id)
        yield()
        assertEquals(listOf(a.id, b.id, c.id), requests.last().map { it.id })
        assertEquals("edited A", requests.last().first().text)
        assertEquals(b, requests.last()[1])
    }

    @Test fun batchDeletionIsAtomicAndCannotOrphanAnUnselectedOrVersionedReply() = runBlocking {
        val writes = mutableListOf<SavedWorkspace>()
        val session = ConversationSession(this, FakeGenerationRepository {}, { writes += it })
        val thread = original.appendVersion(b, b.copy(text = "new"))
        session.restore(SavedWorkspace(listOf(thread)))
        session.openConversation(thread.id)
        val pair = setOf(a.id, b.id)
        val before = writes.size
        session.deleteSelectedVersions(thread.id, setOf(a.id), true)
        session.deleteSelectedVersions(thread.id, pair, false)
        session.deleteSelectedVersions("different", pair, true)
        assertEquals(thread, session.state.value.current)
        assertEquals(before, writes.size)
        session.setContextStart(a.id)
        session.deleteSelectedVersions(thread.id, pair, true)
        assertEquals(4, session.state.value.current.messages.size)
        session.setContextStart(null)
        session.deleteSelectedVersions(thread.id, setOf(b.id), false)
        assertEquals(b, session.state.value.current.messages[1])
        val lastCount = writes.size
        session.deleteSelectedVersions(thread.id, pair, true)
        assertEquals(lastCount + 1, writes.size)
        assertEquals(listOf(c, d), writes.last().history.single().messages)
        assertTrue(writes.last().history.single().versions.isEmpty())
    }

    @Test fun assistantEditsKeepOriginalMetadataOnlyOnOriginalAndPersistSelection() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(SavedWorkspace(listOf(original)))
        session.openConversation(original.id)
        assertTrue(session.editMessage(b.id, "    formatted"))
        val edited = session.state.value.current.messages[1]
        assertEquals("    formatted", edited.text)
        assertEquals("", edited.thought)
        assertNull(edited.usage)
        assertEquals(c, session.state.value.current.messages[2])
        session.chooseMessageVersion(b.id, 0)
        val fresh = ConversationSession(this, FakeGenerationRepository {}, {})
        fresh.restore(decodeWorkspace(encodeWorkspace(session.snapshot())))
        fresh.openConversation(original.id)
        assertEquals(b, fresh.state.value.current.messages[1])
        fresh.chooseMessageVersion(b.id, 1)
        assertEquals(edited, fresh.state.value.current.messages[1])
        fresh.deleteChatVersion(b.id)
        assertEquals(original.messages, fresh.state.value.current.messages)
        assertTrue(fresh.state.value.current.versions.isEmpty())
        fresh.editMessage(b.id, "kept")
        fresh.deleteChatVersion(b.id, otherVersions = true)
        assertEquals("kept", fresh.state.value.current.messages[1].text)
        assertTrue(fresh.state.value.current.versions.isEmpty())
        fresh.setContextStart(b.id)
        fresh.deleteChatVersion(a.id)
        assertEquals(4, fresh.state.value.current.messages.size)
        fresh.setContextStart(null)
        fresh.deleteChatVersion(a.id)
        assertEquals(listOf(c, d), fresh.state.value.current.messages)
    }
}
