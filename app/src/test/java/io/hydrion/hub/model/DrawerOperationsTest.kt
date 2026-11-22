package io.hydrion.hub.model

import io.hydrion.hub.ui.sidebar.globalDrawerHits
import io.hydrion.hub.ui.sidebar.historySections
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

    @Test fun openingAHistoryHitParksTheSingleAssistantsDraftAndRestoresBothLocations() = runBlocking {
        val writes = mutableListOf<SavedWorkspace>()
        fun session() = ConversationSession(this, FakeGenerationRepository {}, { writes += it })
        fun restart() = session().apply { restore(decodeWorkspace(encodeWorkspace(writes.last()))) }
        val live = session()
        live.restore(SavedWorkspace(history = listOf(first, second), drafts = mapOf(first.id to draft)))
        live.setDraft("Continue writing")
        live.addAttachment(Attachment("figure", "fixture.png", mimeType = "image/png"))
        val pendingId = live.state.value.current.id
        val pendingDraft = live.state.value.draft

        live.openConversation("removed-result")
        assertEquals(pendingId, live.state.value.current.id)
        assertTrue(writes.isEmpty())
        val hit = globalDrawerHits(live.state.value.history, "question").single()
        live.openConversation(hit.id)
        assertEquals(first.id, live.state.value.current.id)
        assertEquals(draft, live.state.value.draft)
        assertEquals(pendingId, writes.single().unsent?.id)

        val reopened = restart()
        assertEquals(first.id, reopened.state.value.current.id)
        assertEquals(draft, reopened.state.value.draft)
        reopened.openConversation(second.id)
        val next = restart()
        assertEquals(second.id, next.state.value.current.id)
        assertEquals(Draft(), next.state.value.draft)
        next.newConversation()
        assertEquals(pendingId, next.state.value.current.id)
        assertEquals(pendingDraft, next.state.value.draft)
        val resumed = restart()
        assertEquals(pendingId, resumed.state.value.current.id)
        assertEquals(pendingDraft, resumed.state.value.draft)

        resumed.newConversation()
        assertNull(writes.last().unsent)
        assertFalse(pendingId in writes.last().drafts)
        assertEquals(draft, writes.last().drafts[first.id])
        assertEquals(Draft(), restart().state.value.draft)
    }

    @Test fun aHistoryVisitFromAnUntouchedNewChatDoesNotCreateAPendingDraft() = runBlocking {
        val writes = mutableListOf<SavedWorkspace>()
        val live = ConversationSession(this, FakeGenerationRepository {}, { writes += it })
        live.restore(SavedWorkspace(history = listOf(first)))
        live.openConversation(first.id)
        assertTrue(writes.isEmpty())
        assertTrue(live.snapshot().pending.isEmpty())
        val reopened = ConversationSession(this, FakeGenerationRepository {}, {})
        reopened.restore(decodeWorkspace(encodeWorkspace(live.snapshot())))
        assertFalse(reopened.state.value.modeLocked)
        assertEquals(Draft(), reopened.state.value.draft)
    }

    @Test fun crossAssistantGlobalHitRestoresTheOwnersThreadAndKeepsBothDrafts() = runBlocking {
        val writes = mutableListOf<SavedWorkspace>()
        fun session() = ConversationSession(this, FakeGenerationRepository {}, { writes += it })
        val live = session()
        val target = other.copy(messages = listOf(Message(role = Role.User, text = "shared reference")))
        live.restore(saved.copy(history = listOf(first, target), drafts = mapOf(target.id to draft)))
        live.newConversation()
        live.addAttachment(Attachment("pending", "unsent.txt"))
        val pending = live.state.value.current.id
        val contents = live.state.value.draft

        val hit = globalDrawerHits(live.state.value.history, "reference").single()
        live.openConversation(hit.id)
        val cold = session().apply { restore(decodeWorkspace(encodeWorkspace(writes.last()))) }
        assertEquals(target.id, cold.state.value.current.id)
        assertEquals(alt.id, cold.state.value.current.assistantId)
        assertEquals(draft, cold.state.value.draft)
        cold.selectAssistant(owner.id)
        assertEquals(pending, cold.state.value.current.id)
        assertEquals(contents, cold.state.value.draft)
        cold.selectAssistant(alt.id)
        assertEquals(target.id, cold.state.value.current.id)
        assertEquals(draft, cold.state.value.draft)
    }

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

    @Test fun sameTitleDeletionKeepsItsTargetAfterPinReordersTheFilteredHistory() = runBlocking {
        val older = first.copy(title = "Same", updatedAt = 1_000)
        val newer = second.copy(title = "Same", updatedAt = 2_000)
        val foreign = other.copy(title = "Same", updatedAt = 3_000)
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(saved.copy(history = listOf(newer, foreign, older)))
        fun filteredIds() = historySections(session.state.value.history, " SAME ", owner.id)
            .flatMap { it.rows }.map { it.id }

        assertEquals(listOf(newer.id, older.id), filteredIds())
        val pendingId = filteredIds().first()
        session.togglePin(older.id)
        assertEquals(listOf(older.id, newer.id), filteredIds())
        session.deleteConversation(pendingId)

        assertEquals(listOf(older.id), filteredIds())
        val remaining = session.state.value.history.associateBy { it.id }
        assertEquals(setOf(older.id, foreign.id), remaining.keys)
        assertEquals(older.copy(pinned = true), remaining[older.id])
        assertEquals(foreign, remaining[foreign.id])
        assertEquals(draft, session.snapshot().drafts[older.id])
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
