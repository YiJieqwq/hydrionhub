package io.hydrion.hub.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

internal sealed interface ManualSearchState {
    data object Idle : ManualSearchState
    data object Loading : ManualSearchState
    data object Empty : ManualSearchState
    data class Results(val hits: List<SearchHit>) : ManualSearchState
    data class Failure(val error: SearchFailure) : ManualSearchState
    data class Blocked(val reason: SearchBlocked.Reason) : ManualSearchState
}

/** One instance per visible page and target. Its commands and coroutine run on the UI dispatcher. */
internal class ManualSearchSession(
    private val target: ManualTarget,
    private val scope: CoroutineScope,
    private val search: suspend (ManualTarget, String) -> List<SearchHit>,
) {
    private var active: Job? = null
    private var closed = false
    private val current = MutableStateFlow<ManualSearchState>(ManualSearchState.Idle)
    val state = current.asStateFlow()

    fun submit(query: String) {
        if (closed || active != null || !scope.isActive) return
        val term = query.trim()
        if (term.isEmpty() || term.length > SearxngSearch.MaxQuery || term.any(Char::isISOControl)) {
            current.value = ManualSearchState.Failure(SearchFailure(SearchProblem.InvalidRequest))
            return
        }
        val request = scope.launch(start = CoroutineStart.LAZY) {
            var outcome: ManualSearchState = ManualSearchState.Idle
            try {
                val hits = search(target, term)
                outcome = if (hits.isEmpty()) ManualSearchState.Empty else ManualSearchState.Results(hits)
            } catch (error: SearchFailure) {
                outcome = ManualSearchState.Failure(error)
            } catch (error: SearchBlocked) {
                outcome = ManualSearchState.Blocked(error.reason)
            } finally {
                // A cancelled transport may still finish after another submission or a new visit.
                if (active === coroutineContext.job) {
                    active = null
                    current.value = if (isActive) outcome else ManualSearchState.Idle
                }
            }
        }
        active = request
        current.value = ManualSearchState.Loading
        request.start()
    }

    fun resetResults() {
        if (active == null) current.value = ManualSearchState.Idle
    }

    fun cancel() {
        val request = active
        active = null
        current.value = ManualSearchState.Idle
        request?.cancel()
    }

    fun close() {
        closed = true
        cancel()
    }
}
