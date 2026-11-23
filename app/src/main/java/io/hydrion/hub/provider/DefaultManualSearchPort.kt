package io.hydrion.hub.provider

import android.content.Context

internal class DefaultManualSearchPort(
    private val store: TavilyManualStore,
    private val findEndpoint: (String) -> SearchEndpoint?,
    private val searxng: SearxngSearch = SearxngSearch(),
    private val tavilySearch: TavilySearch = TavilySearch(),
) : ManualSearchPort {
    constructor(context: Context, legacy: WebSearchStore) : this(
        TavilyManualStore(context), { id -> legacy.settings.value.endpoints.find { it.id == id } },
    )

    override val tavily = store.setup

    override fun setPilotEnabled(enabled: Boolean) = store.setEnabled(enabled)

    override fun saveTavily(added: Boolean, sessionKey: String?) = store.save(added, sessionKey)

    override suspend fun search(target: ManualTarget, query: String): List<SearchHit> = when (target) {
        is ManualTarget.Searxng -> {
            val endpoint = findEndpoint(target.id)
            if (endpoint?.adapter != SearchAdapter.Searxng) throw SearchBlocked(SearchBlocked.Reason.TargetUnavailable)
            searxng.search(endpoint, query)
        }
        ManualTarget.Tavily -> store.withSessionKey { key -> tavilySearch.search(query, key) }
    }
}
