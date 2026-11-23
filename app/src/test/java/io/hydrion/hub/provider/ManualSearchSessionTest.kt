package io.hydrion.hub.provider

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import org.junit.Assert.*
import org.junit.Test

class ManualSearchSessionTest {
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val port = PendingSearches()
    private val page = ManualSearchSession(ManualTarget.Tavily, scope, port::search)
    private val hit = SearchHit("B", "https://result.invalid/b", "Excerpt")

    @Test fun onlyAnExplicitSubmissionStartsAndDoubleTapWhileLoadingSharesOneRequest() {
        page.resetResults()
        assertTrue(port.requests.isEmpty())
        page.submit("  合成 月相 & B  ")
        page.submit("合成 月相 & B")
        assertEquals(ManualSearchState.Loading, page.state.value)
        val request = port.requests.single()
        assertEquals("合成 月相 & B", request.query)
        assertEquals(ManualTarget.Tavily, request.target)
        request.reply.resume(listOf(hit))
        assertEquals(ManualSearchState.Results(listOf(hit)), page.state.value)
        page.submit("separate submission")
        port.requests.last().reply.resume(emptyList())
        assertEquals(2, port.requests.size)
        assertEquals(ManualSearchState.Empty, page.state.value)
        page.resetResults()
        assertEquals(ManualSearchState.Idle, page.state.value)
    }

    @Test fun cancelledRequestsCannotFinishOrClearTheNewerRequest() {
        page.submit("A")
        val first = port.requests.single()
        page.cancel()
        assertTrue(first.reply.context.job.isCancelled)
        assertEquals(ManualSearchState.Idle, page.state.value)
        page.submit("B")
        first.reply.resumeWithException(SearchFailure(SearchProblem.Http, 429))
        assertEquals(ManualSearchState.Loading, page.state.value)
        page.submit("duplicate B")
        assertEquals(2, port.requests.size)
        port.requests.last().reply.resume(listOf(hit))
        assertEquals(ManualSearchState.Results(listOf(hit)), page.state.value)
    }

    @Test fun leavingAndReturningKeepsLateSuccessOutEvenForTheSameTarget() {
        page.submit("A")
        val first = port.requests.single()
        page.close()
        assertTrue(first.reply.context.job.isCancelled)
        val reopened = ManualSearchSession(ManualTarget.Tavily, scope, port::search)
        reopened.submit("B")
        port.requests.last().reply.resume(listOf(hit))
        first.reply.resume(emptyList())
        assertEquals(ManualSearchState.Results(listOf(hit)), reopened.state.value)
        assertEquals(ManualSearchState.Idle, page.state.value)
        page.submit("stale button")
        assertEquals(2, port.requests.size)
    }

    @Test fun emptyOversizeAndControlInputsNeverReachThePort() {
        for (query in listOf("  ", "x".repeat(513), "a\u0000b")) {
            page.submit(query)
            val failure = page.state.value as ManualSearchState.Failure
            assertEquals(SearchProblem.InvalidRequest, failure.error.problem)
        }
        assertTrue(port.requests.isEmpty())
    }

    @Test fun aBlockedTargetAndAnHttpErrorStayDistinctFromEmptyResults() {
        for (reason in SearchBlocked.Reason.entries) {
            page.submit("fixture")
            port.requests.last().reply.resumeWithException(SearchBlocked(reason))
            assertEquals(ManualSearchState.Blocked(reason), page.state.value)
        }
        page.submit("fixture")
        port.requests.last().reply.resumeWithException(SearchFailure(SearchProblem.Http, 401))
        val failure = page.state.value as ManualSearchState.Failure
        assertEquals(SearchProblem.Http, failure.error.problem)
        assertEquals(401, failure.error.status)
    }

    private class PendingSearches : ManualSearchPort {
        data class Pending(val target: ManualTarget, val query: String, val reply: Continuation<List<SearchHit>>)

        val requests = mutableListOf<Pending>()
        override val tavily = MutableStateFlow(TavilySetup(enabled = true, added = true, hasKey = true))
        override fun setPilotEnabled(enabled: Boolean): Unit = error("unexpected settings change")
        override fun saveTavily(added: Boolean, sessionKey: String?): Unit = error("unexpected settings change")

        // Deliberately allow a late completion after cancellation.
        override suspend fun search(target: ManualTarget, query: String): List<SearchHit> =
            suspendCoroutine { requests += Pending(target, query, it) }
    }
}
