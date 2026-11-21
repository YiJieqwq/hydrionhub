package io.hydrion.hub.provider

import android.content.Context
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal enum class SearchAdapter { None, Searxng }

internal data class SearchEndpoint(
    val id: String,
    val name: String,
    val url: String,
    val adapter: SearchAdapter = SearchAdapter.None,
)

internal fun searchServiceAddress(url: String): URI? {
    if (url.length > 2048) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    return uri.takeIf { it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.rawUserInfo == null &&
        it.rawQuery == null && it.rawFragment == null && (it.port == -1 || it.port in 1..65535) }
}

internal data class WebSearchSettings(
    val localEnabled: Boolean = true,
    val endpoints: List<SearchEndpoint> = emptyList(),
) {
    fun withEndpoint(endpoint: SearchEndpoint): WebSearchSettings? {
        val item = endpoint.copy(name = endpoint.name.trim(), url = endpoint.url.trim())
        if (item.id.isBlank() || item.url.length > 2048) return null
        if (item.name.isEmpty() || item.name.length > 80 || item.name.any(Char::isISOControl)) return null
        if (searchServiceAddress(item.url) == null) return null
        return copy(endpoints = if (endpoints.none { it.id == item.id }) endpoints + item
            else endpoints.map { if (it.id == item.id) item else it })
    }

    fun encode() = JSONObject().put("localEnabled", localEnabled).put("endpoints", JSONArray(endpoints.map {
        JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)
            .put("adapter", if (it.adapter == SearchAdapter.Searxng) "searxng" else null)
    })).toString()

    companion object {
        fun decode(raw: String?): WebSearchSettings {
            if (raw == null) return WebSearchSettings()
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return WebSearchSettings()
            var settings = WebSearchSettings(localEnabled = json.optBoolean("localEnabled", true))
            val rows = json.optJSONArray("endpoints") ?: return settings
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val adapter = if (row.optString("adapter") == "searxng") SearchAdapter.Searxng else SearchAdapter.None
                val endpoint = SearchEndpoint(row.optString("id"), row.optString("name"), row.optString("url"), adapter)
                settings = settings.withEndpoint(endpoint) ?: settings
            }
            return settings
        }
    }
}

/** Legacy endpoints need an explicit adapter selection before they can be queried. */
internal class WebSearchStore(context: Context) {
    private val prefs = context.getSharedPreferences("web_search_v1", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(WebSearchSettings.decode(prefs.getString("settings", null)))
    val settings = state.asStateFlow()
    fun enableLocal(enabled: Boolean) = update(state.value.copy(localEnabled = enabled))
    fun save(endpoint: SearchEndpoint): Boolean {
        val next = state.value.withEndpoint(endpoint) ?: return false
        update(next)
        return true
    }
    fun remove(id: String) = update(state.value.copy(endpoints = state.value.endpoints.filterNot { it.id == id }))
    private fun update(next: WebSearchSettings) {
        prefs.edit().putString("settings", next.encode()).apply()
        state.value = next
    }
}
