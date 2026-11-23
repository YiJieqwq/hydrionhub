package io.hydrion.hub.provider

import kotlinx.coroutines.flow.StateFlow

internal sealed interface ManualTarget {
    data class Searxng(val id: String) : ManualTarget
    data object Tavily : ManualTarget
}

internal data class TavilySetup(val enabled: Boolean, val added: Boolean, val hasKey: Boolean)

internal class SearchBlocked(val reason: Reason) : Exception(reason.name) {
    enum class Reason { TargetUnavailable, MissingCredential }
}

/** Explicit settings-page searches. Query text, hits and credentials are never persisted. */
internal interface ManualSearchPort {
    val tavily: StateFlow<TavilySetup>

    fun setPilotEnabled(enabled: Boolean)

    /** Null keeps the session key, blank clears it, and removing the target always clears it. */
    fun saveTavily(added: Boolean, sessionKey: String? = null)

    /** Cancellation propagates to the call. An empty successful list is a valid search result. */
    suspend fun search(target: ManualTarget, query: String): List<SearchHit>
}
