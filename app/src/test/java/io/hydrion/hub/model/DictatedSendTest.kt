package io.hydrion.hub.model

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class DictatedSendTest {
    @Test fun emittedErrorRestoresExactDraftAndAttachmentsWithoutResending() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.setDraft("  /error  ")
        session.addAttachment(Attachment("attachment", "reference"))
        val original = session.state.value.draft
        session.send("local", emptyMap(), null, null, restoreDraftOnError = true)
        assertEquals(Draft(), session.state.value.draft)
        yield()

        val state = session.state.value
        assertEquals(original, state.draft)
        assertEquals(original, session.snapshot().drafts[state.current.id])
        assertEquals(2, state.current.messages.size)
        assertEquals("/error", state.current.messages.first().text)
        assertEquals(Generation.Error, state.current.messages.last().status)
    }

    @Test fun thrownFailureRestoresOnlyTheBackgroundOwnersDraft() = runBlocking {
        val failing = object : GenerationRepository {
            override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String) =
                flow<Message> { throw IOException("offline") }
        }
        val session = ConversationSession(this, failing, {})
        val owner = session.state.value.current.id
        session.setDraft("dictated message")
        session.send("local", emptyMap(), null, null, restoreDraftOnError = true)
        session.newConversation()
        session.setDraft("new conversation")
        val other = session.state.value.current.id
        yield()

        assertEquals(other, session.state.value.current.id)
        assertEquals("new conversation", session.state.value.draft.text)
        session.openConversation(owner)
        assertEquals("dictated message", session.state.value.draft.text)
        assertEquals(Generation.Error, session.state.value.current.messages.last().status)
    }

    @Test fun editingEvenBackToEmptyPreventsRollback() = runBlocking {
        for (replacement in listOf("", "keep my edit")) {
            val session = ConversationSession(this, FakeGenerationRepository {}, {})
            session.setDraft("/error")
            session.send("local", emptyMap(), null, null, restoreDraftOnError = true)
            session.setDraft("changed after send")
            session.setDraft(replacement)
            yield()
            assertEquals(replacement, session.state.value.draft.text)
            assertEquals(Generation.Error, session.state.value.current.messages.last().status)
        }
    }

    @Test fun stoppingDoesNotRestoreOrLeaveARecoveryForTheNextNormalSend() = runBlocking {
        var stopFirst = true
        val session = ConversationSession(this, FakeGenerationRepository {
            if (stopFirst) awaitCancellation()
        }, {})
        session.setDraft("dictated")
        session.send("local", emptyMap(), null, null, restoreDraftOnError = true)
        yield()
        session.stop()
        assertEquals(Generation.Stopped, session.state.value.current.messages.last().status)
        assertEquals(Draft(), session.state.value.draft)

        stopFirst = false
        session.setDraft("/error")
        session.send("local", emptyMap(), null, null)
        yield()
        assertEquals(Generation.Error, session.state.value.current.messages.last().status)
        assertEquals(Draft(), session.state.value.draft)
    }
}
