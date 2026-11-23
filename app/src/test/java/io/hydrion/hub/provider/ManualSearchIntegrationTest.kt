package io.hydrion.hub.provider

import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManualSearchIntegrationTest {
    private val legacyJson = """{"localEnabled":false,"endpoints":[{"id":"old","name":"Old","url":"https://old.invalid"},{"id":"tavily","name":"S","url":"https://s.invalid/instance","adapter":"searxng"}]}"""

    @Test fun settingsAndManualSessionsReachBothProvidersWithOnlyMemoryResponses() = runBlocking {
        val legacy = WebSearchSettings.decode(legacyJson)
        val legacyRequest = AtomicReference<Request>()
        val searxng = SearxngSearch(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            legacyRequest.set(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"results":[]}""".toResponseBody("application/json".toMediaType())).build()
        })
        val calls = TavilyFixtureCalls()
        var flags = false to false
        fun openPort() = DefaultManualSearchPort(
            TavilyManualStore(flags.first, flags.second) { enabled, added -> flags = enabled to added },
            { id -> legacy.endpoints.find { it.id == id } }, searxng, TavilySearch(calls)
        )
        val port = openPort()
        val page = ManualSearchSession(ManualTarget.Tavily, this, port::search)
        assertEquals(TavilySetup(false, false, false), port.tavily.value)
        page.submit("fixture")
        assertEquals(SearchBlocked.Reason.TargetUnavailable, page.state.awaitValue<ManualSearchState.Blocked>().reason)
        port.setPilotEnabled(true)
        port.saveTavily(true)
        page.submit("fixture")
        assertEquals(SearchBlocked.Reason.MissingCredential, page.state.awaitValue<ManualSearchState.Blocked>().reason)
        assertTrue(calls.calls.isEmpty())

        port.saveTavily(true, "fixture-key")
        val query = "合成 月相 & B"
        page.submit("  $query  ")
        page.submit(query)
        val exchange = calls.awaitCall(1)
        assertEquals(1, calls.calls.size)
        val request = exchange.request()
        assertEquals("https://api.tavily.com/search", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer fixture-key", request.header("Authorization"))
        val body = JSONObject(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8())
        assertEquals(setOf("query", "max_results", "search_depth"), body.keys().asSequence().toSet())
        assertEquals(query, body.getString("query"))
        assertEquals(10, body.getInt("max_results"))
        assertEquals("basic", body.getString("search_depth"))
        exchange.reply("""{"results":[{"url":"https://result.invalid/a","title":"A"},{"url":"https://result.invalid/a","title":"duplicate"},{"url":"https://result.invalid/b","content":"乙"}]}""")
        assertEquals(listOf(SearchHit("A", "https://result.invalid/a", ""),
            SearchHit("result.invalid", "https://result.invalid/b", "乙")), page.state.awaitValue<ManualSearchState.Results>().hits)
        page.close()

        assertEquals(TavilySetup(true, true, false), openPort().tavily.value)
        port.setPilotEnabled(false)
        assertEquals(false to true, flags)
        assertEquals(listOf(SearchAdapter.None, SearchAdapter.Searxng), legacy.endpoints.map { it.adapter })
        assertFalse(legacy.localEnabled)
        val blocked = runCatching { port.search(ManualTarget.Searxng("old"), "fixture") }.exceptionOrNull()
        assertEquals(SearchBlocked.Reason.TargetUnavailable, (blocked as SearchBlocked).reason)
        assertNull(legacyRequest.get())
        val oldPage = ManualSearchSession(ManualTarget.Searxng("tavily"), this, port::search)
        oldPage.submit(query)
        oldPage.state.awaitValue<ManualSearchState.Empty>()
        val oldRequest = requireNotNull(legacyRequest.get())
        assertEquals("https://s.invalid/instance/search", oldRequest.url.toString())
        assertNull(oldRequest.header("Authorization"))
        val form = Buffer().also { requireNotNull(oldRequest.body).writeTo(it) }.readUtf8().split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        assertEquals(mapOf("q" to query, "format" to "json"), form)
        assertEquals(1, calls.calls.size)
        oldPage.close()
    }

    @Test fun leavingCancelsTheRealCallAndALateReplyCannotEndTheNextSearch() = runBlocking {
        val calls = TavilyFixtureCalls()
        val port = DefaultManualSearchPort(TavilyManualStore(), { null }, tavilySearch = TavilySearch(calls))
        port.setPilotEnabled(true)
        port.saveTavily(true, "fixture-key")
        val firstPage = ManualSearchSession(ManualTarget.Tavily, this, port::search)
        firstPage.submit("A")
        val a = calls.awaitCall(1)
        firstPage.close()
        val secondPage = ManualSearchSession(ManualTarget.Tavily, this, port::search)
        secondPage.submit("B")
        val b = calls.awaitCall(2)
        assertTrue(a.isCanceled())
        a.reply("""{"results":[{"url":"https://late.invalid/"}]}""")
        yield()
        assertEquals(ManualSearchState.Loading, secondPage.state.value)
        b.reply("""{"results":[{"url":"https://current.invalid/","title":"B"}]}""")
        assertEquals(listOf(SearchHit("B", "https://current.invalid/", "")),
            secondPage.state.awaitValue<ManualSearchState.Results>().hits)
        secondPage.close()
    }

    private suspend fun TavilyFixtureCalls.awaitCall(count: Int): TavilyFixtureCall = withTimeout(5_000) {
        while (calls.size < count) yield()
        calls[count - 1]
    }

    private suspend inline fun <reified T : ManualSearchState> StateFlow<ManualSearchState>.awaitValue(): T =
        withTimeout(5_000) { first { it is T } as T }
}
