package io.hydrion.hub.model

import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCancellationRaceTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val repository = CallbackRepository()
    private val errors = mutableListOf<Pair<String, String>>()
    private val session = ConversationSession(scope, repository, persist = {},
        onGenerationError = { conversation, detail -> errors += conversation to detail })

    @After fun releaseCallbacks() {
        scope.cancel()
        repository.attempts.forEach { it.finishIfWaiting() }
    }

    @Test fun stoppedCallbacksAndCleanupCannotChangeRetryOrAnotherConversation() {
        val stopped = send("first prompt")
        val firstConversation = session.state.value.current.id
        stopped.emit("prefix", Generation.Generating)
        session.stop()
        assertTrue(stopped.job.isCancelled)
        assertFalse(stopped.cleanedUp)

        session.regenerate(stopped.request.id)
        val retry = repository.attempts.last()
        assertTrue(retry.retry)
        assertEquals(stopped.request.id, retry.request.id)
        retry.emit("retry prefix", Generation.Generating)

        session.newConversation()
        val other = send("second prompt")
        other.emit("other prefix", Generation.Generating)
        session.setDraft("draft in second conversation")
        val before = session.state.value

        stopped.emit("obsolete delta", Generation.Generating)
        assertEquals(before, session.state.value)
        stopped.emit("obsolete completion", Generation.Completed)
        assertEquals(before, session.state.value)
        stopped.fail("late read error")
        assertTrue(stopped.cleanedUp)
        assertTrue(stopped.job.isCompleted)
        assertEquals(before, session.state.value)
        assertTrue(errors.isEmpty())

        retry.emit("retry answer", Generation.Completed)
        retry.finishIfWaiting()
        val updated = session.state.value.history.single { it.id == firstConversation }
        assertEquals("retry answer", updated.messages.last().text)
        assertEquals(Generation.Completed, updated.messages.last().status)
        val previous = updated.versions.getValue(stopped.request.id).others.single()
        assertEquals("prefix", previous.text)
        assertEquals(Generation.Stopped, previous.status)
        assertEquals(before.current, session.state.value.current)
        assertEquals(before.draft, session.state.value.draft)

        session.stop()
        assertTrue(other.job.isCancelled)
        assertEquals("other prefix", session.state.value.current.messages.last().text)
        assertEquals(Generation.Stopped, session.state.value.current.messages.last().status)
    }

    @Test fun retryStartedByStoppedStateObserverKeepsItsOwnJob() {
        val stopped = send("retry immediately")
        stopped.emit("first attempt", Generation.Generating)
        val observer = scope.launch {
            session.state.first { it.current.messages.lastOrNull()?.status == Generation.Stopped }
            session.regenerate(stopped.request.id)
        }

        session.stop()
        assertTrue(observer.isCompleted)
        val retry = repository.attempts.last()
        assertTrue(retry.retry)
        assertEquals(stopped.request.id, retry.request.id)
        stopped.fail("old callback finished after retry")
        assertTrue(stopped.cleanedUp)

        retry.emit("live retry", Generation.Generating)
        assertEquals("live retry", session.state.value.current.messages.last().text)
        session.stop()
        assertTrue(retry.job.isCancelled)
        assertEquals(Generation.Stopped, session.state.value.current.messages.last().status)
        assertTrue(errors.isEmpty())
    }

    @Test fun stoppingOnFirstSendingStatePreventsTransportStartup() {
        val observer = scope.launch {
            session.state.first { it.current.messages.lastOrNull()?.status == Generation.Sending }
            session.stop()
        }
        session.setDraft("stop before the first fragment")
        session.send("fixture", emptyMap(), null, null)

        assertTrue(observer.isCompleted)
        assertEquals(Generation.Stopped, session.state.value.current.messages.last().status)
        assertTrue("The stopped request reached the repository", repository.attempts.isEmpty())
    }

    @Test fun stoppingAndRetryingOnFirstSendingStateStartsOnlyTheReplacement() {
        val observer = scope.launch {
            val sending = session.state.first { it.current.messages.lastOrNull()?.status == Generation.Sending }
            val replyId = sending.current.messages.last().id
            session.stop()
            session.regenerate(replyId)
        }
        session.setDraft("retry before transport startup")
        session.send("fixture", emptyMap(), null, null)

        assertTrue(observer.isCompleted)
        assertEquals(listOf(true), repository.attempts.map { it.retry })
        val replacement = repository.attempts.single()
        replacement.emit("replacement answer", Generation.Completed)
        assertEquals("replacement answer", session.state.value.current.messages.last().text)
        assertEquals(Generation.Completed, session.state.value.current.messages.last().status)
    }

    @Test fun dictatedErrorPublishesRestoredDraftBeforeImmediateRetry() {
        session.setDraft("dictated prompt")
        session.addAttachment(Attachment("fixture", "note.txt"))
        val originalDraft = session.state.value.draft
        session.send("fixture", emptyMap(), null, null, restoreDraftOnError = true)
        val first = repository.attempts.single()
        first.emit("partial answer", Generation.Generating)
        assertTrue(session.state.value.draft.text.isEmpty())

        // Deliver the state observer inline even while the failed collector is unwinding.
        val inlineObserver = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        }
        var firstErrorState: HubState? = null
        val observer = scope.launch(inlineObserver) {
            val failed = session.state.first { it.current.messages.lastOrNull()?.status == Generation.Error }
            firstErrorState = failed
            session.regenerate(failed.current.messages.last().id)
        }

        first.fail("failed stream")
        assertTrue(observer.isCompleted)
        val observed = checkNotNull(firstErrorState)
        assertEquals(originalDraft, observed.draft)
        assertEquals("partial answer", observed.current.messages.last().text)
        assertTrue(errors.isEmpty())
        val retry = repository.attempts.last()
        assertTrue(retry.retry)
        assertEquals(first.request.id, retry.request.id)
        retry.emit("new retry fragment", Generation.Generating)
        assertEquals("new retry fragment", session.state.value.current.messages.last().text)
        assertEquals(Generation.Generating, session.state.value.current.messages.last().status)
    }

    private fun send(prompt: String): Attempt {
        session.setDraft(prompt)
        session.send("fixture", emptyMap(), null, null)
        return repository.attempts.last()
    }

    // A callback already in flight can complete after cancel(). Drive it on the collector's
    // original coroutine, one event per resume, so the session itself must reject stale events.
    private class Attempt(val request: Message, val retry: Boolean, val job: Job) {
        var cleanedUp = false
        private var waiting: Continuation<Message?>? = null

        suspend fun next(): Message? = suspendCoroutine { waiting = it }

        private fun take(): Continuation<Message?> = checkNotNull(waiting).also { waiting = null }

        fun emit(text: String, status: Generation) = take().resume(request.copy(text = text, status = status))
        fun fail(detail: String) = take().resumeWithException(IOException(detail))
        fun finishIfWaiting() { if (waiting != null) take().resume(null) }
    }

    private class CallbackRepository : GenerationRepository {
        val attempts = mutableListOf<Attempt>()

        @OptIn(InternalCoroutinesApi::class)
        override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>,
                              systemPrompt: String): Flow<Message> = object : Flow<Message> {
            override suspend fun collect(collector: FlowCollector<Message>) {
                val attempt = Attempt(request, retry, checkNotNull(currentCoroutineContext()[Job]))
                attempts += attempt
                try {
                    while (true) collector.emit(attempt.next() ?: break)
                } finally {
                    attempt.cleanedUp = true
                }
            }
        }
    }
}
