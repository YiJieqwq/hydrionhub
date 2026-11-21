package io.hydrion.hub.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DrawerOperationsTest {
    private val owner = LocalAssistant.Default
    private val alt = LocalAssistant("other", "Writer")
    private val question = Message(role = Role.User, text = "question")
    private val response = Message(role = Role.Assistant, text = "answer", replyTo = question.id, imageKeys = listOf("image"))
    private val first = Conversation(id = "one", title = "First", messages = listOf(question, response))
    private val second = Conversation(id = "two", title = "Second")
    private val other = Conversation(id = "three", title = "Other", assistantId = alt.id)
    private val draft = Draft("draft", listOf(Attachment("picked", "sample")))
    private val saved = SavedWorkspace(history = listOf(first, second, other), drafts = mapOf(first.id to draft),
        assistants = listOf(owner, alt), lastThreads = mapOf(owner.id to first.id))

    @Test fun mixedPinsAndMovesAreEachOneWriteAndKeepDraftsOnTheirThreads() = runBlocking {
        val writes = mutableListOf<SavedWorkspace>()
        val session = ConversationSession(this, FakeGenerationRepository {}, { writes += it })
        session.restore(saved)
        val ids = setOf(first.id, second.id)
        writes.clear()
        session.setPinned(ids, true)
        session.setPinned(ids, true)
        assertEquals(1, writes.size)
        assertTrue(writes.single().history.filter { it.id in ids }.all { it.pinned })
        session.moveConversations(ids, alt.id)
        assertEquals(2, writes.size)
        val moved = writes.last()
        assertEquals(owner.id, session.state.value.current.assistantId)
        assertFalse(session.state.value.modeLocked)
        assertTrue(moved.history.all { it.assistantId == alt.id })
        assertEquals(draft, moved.drafts[first.id])
        val cold = ConversationSession(this, FakeGenerationRepository {}, {})
        cold.restore(decodeWorkspace(encodeWorkspace(moved)))
        cold.openConversation(first.id)
        assertEquals(alt.id, cold.state.value.current.assistantId)
        assertEquals(draft, cold.state.value.draft)
        cold.setPinned(ids, false)
        assertTrue(cold.state.value.history.none { it.pinned })
    }

    @Test fun duplicateHasIndependentIdsAndRepliesDeletionIsScoped() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(saved)
        session.duplicateConversation(first.id, "Copy")
        val copy = session.state.value.history.single { it.title == "Copy" }
        assertNotEquals(first.id, copy.id)
        assertTrue(copy.messages.none { m -> first.messages.any { it.id == m.id } })
        assertEquals(copy.messages.first().id, copy.messages.last().replyTo)
        assertEquals(response.imageKeys, copy.messages.last().imageKeys)
        assertFalse(session.hasDraft(copy.id))
        assertTrue(session.hasDraft(first.id))
        session.deleteConversations(setOf(copy.id, second.id))
        assertEquals(setOf(first.id, other.id), session.state.value.history.map { it.id }.toSet())
        assertEquals(draft, session.snapshot().drafts[first.id])
    }

    @Test fun groupsRoundTripAndRemovingAssistantCannotDiscardHistoryOrParkedDraft() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(saved)
        assertTrue(session.groupAssistant(alt.id, "  Notes  "))
        assertFalse(session.groupAssistant(alt.id, "x".repeat(65)))
        session.removeEmptyAssistant(alt.id)
        assertEquals("Notes", session.state.value.assistants.last().sidebarGroup)
        val cold = ConversationSession(this, FakeGenerationRepository {}, {})
        cold.restore(decodeWorkspace(encodeWorkspace(session.snapshot())))
        assertEquals("Notes", cold.state.value.assistants.last().sidebarGroup)
        cold.addAssistant("Spare", "Notes")
        val spare = cold.state.value.assistants.last()
        cold.selectAssistant(spare.id)
        cold.setDraft("unsent")
        cold.selectAssistant(owner.id)
        cold.removeEmptyAssistant(spare.id)
        cold.selectAssistant(spare.id)
        assertEquals("unsent", cold.state.value.draft.text)
        cold.setDraft("")
        assertTrue(cold.canRemoveAssistant(spare.id))
        cold.removeEmptyAssistant(spare.id)
        assertEquals(listOf(owner.id, alt.id), cold.state.value.assistants.map { it.id })
        assertEquals(owner.id, cold.state.value.current.assistantId)
        assertFalse(cold.canRemoveAssistant(owner.id))
    }

    @Test fun removingLastEmptyAssistantKeepsRemainingUnsentDraftAcrossRestart() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.addAssistant("Empty")
        session.setDraft("still here")
        session.addAttachment(Attachment("doc", "sample"))
        val original = session.state.value.current.id
        val empty = session.state.value.assistants.last().id
        session.selectAssistant(empty)
        session.removeEmptyAssistant(empty)
        val cold = ConversationSession(this, FakeGenerationRepository {}, {})
        cold.restore(decodeWorkspace(encodeWorkspace(session.snapshot())))
        assertEquals(original, cold.state.value.current.id)
        assertEquals("still here", cold.state.value.draft.text)
        assertEquals("doc", cold.state.value.draft.attachments.single().id)
    }

    @Test fun runningTurnCannotBeDuplicatedOrMovedByAStaleMenu() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(saved)
        session.setDraft("test")
        session.send("Local", emptyMap(), null, null)
        assertTrue(session.state.value.isGenerating)
        session.duplicateConversation(first.id, "Copy")
        session.moveConversations(setOf(first.id, second.id), alt.id)
        assertEquals(owner.id, session.state.value.current.assistantId)
        assertTrue(session.state.value.history.none { it.title == "Copy" })
        assertEquals(owner.id, session.state.value.history.single { it.id == second.id }.assistantId)
        session.stop()
    }
}
