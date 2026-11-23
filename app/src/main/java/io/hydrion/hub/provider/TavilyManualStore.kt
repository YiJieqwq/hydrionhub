package io.hydrion.hub.provider

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job

internal class TavilyManualStore(
    enabled: Boolean = false,
    added: Boolean = false,
    private val persist: (Boolean, Boolean) -> Unit = { _, _ -> },
) {
    constructor(context: Context) : this(context::getSharedPreferences)

    internal constructor(open: (String, Int) -> SharedPreferences) : this(open("web_search_tavily_v1", Context.MODE_PRIVATE))

    private constructor(prefs: SharedPreferences) : this(
        prefs.getBoolean("enabled", false),
        prefs.getBoolean("added", false),
        { enabled, added -> prefs.edit().putBoolean("enabled", enabled).putBoolean("added", added).apply() },
    )

    private val state = MutableStateFlow(TavilySetup(enabled, added, hasKey = false))
    val setup = state.asStateFlow()
    private var key = ""
    private val searches = mutableSetOf<Job>()

    fun setEnabled(enabled: Boolean) = update(enabled = enabled)

    fun save(added: Boolean, sessionKey: String?) {
        val replacement = if (added) sessionKey?.let(::tavilyToken) else null
        update(added = added, replacement = replacement)
    }

    private fun update(enabled: Boolean? = null, added: Boolean? = null, replacement: String? = null) {
        val cancelled = synchronized(this) {
            val old = state.value
            val nextEnabled = enabled ?: old.enabled
            val nextAdded = added ?: old.added
            val nextKey = if (!nextEnabled || !nextAdded) "" else replacement ?: key
            if (old.enabled != nextEnabled || old.added != nextAdded) persist(nextEnabled, nextAdded)
            val invalidated = if ((!nextEnabled || !nextAdded || nextKey != key) && searches.isNotEmpty()) {
                searches.toList().also { searches.clear() }
            } else emptyList()
            key = nextKey
            state.value = TavilySetup(nextEnabled, nextAdded, key.isNotEmpty())
            invalidated
        }
        cancelled.forEach { it.cancel() }
    }

    /** Only this operation gets the key. Revocation cancels its own job, not the caller's scope. */
    suspend fun <T> withSessionKey(search: suspend (String) -> T): T = coroutineScope {
        val job = coroutineContext.job
        val token = synchronized(this@TavilyManualStore) {
            if (!state.value.enabled || !state.value.added) throw SearchBlocked(SearchBlocked.Reason.TargetUnavailable)
            if (key.isEmpty()) throw SearchBlocked(SearchBlocked.Reason.MissingCredential)
            searches.add(job)
            key
        }
        try {
            search(token)
        } finally {
            // A revocation that won the race invalidates both successful replies and failures.
            if (!synchronized(this@TavilyManualStore) { searches.remove(job) }) throw CancellationException()
        }
    }
}

internal fun tavilyToken(input: String): String {
    if (input.isBlank()) return ""
    val token = input.trim(' ')
    if (token.any { it.code !in 33..126 }) throw SearchFailure(SearchProblem.InvalidRequest)
    return token
}
