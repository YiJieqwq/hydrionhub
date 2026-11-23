package io.hydrion.hub.provider

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Completes only when the test explicitly responds, including after cancellation. No sockets. */
internal class TavilyFixtureCalls : Call.Factory {
    private val recorded = mutableListOf<TavilyFixtureCall>()
    val calls: List<TavilyFixtureCall> get() = synchronized(recorded) { recorded.toList() }

    override fun newCall(request: Request) = TavilyFixtureCall(request).also {
        synchronized(recorded) { recorded.add(it) }
    }
}

internal class TavilyFixtureCall(private val sent: Request) : Call {
    @Volatile private var callback: Callback? = null
    @Volatile private var cancelled = false
    override fun request() = sent
    override fun enqueue(responseCallback: Callback) { check(callback == null); callback = responseCallback }
    override fun cancel() { cancelled = true }
    override fun isCanceled() = cancelled
    override fun isExecuted() = callback != null
    override fun timeout() = Timeout.NONE
    override fun clone() = TavilyFixtureCall(sent)
    override fun execute(): Response = error("Only the asynchronous API is used")

    fun reply(body: String, status: Int = 200, type: String = "application/json") =
        replyBytes(body.toByteArray(), status, type)

    fun replyBytes(body: ByteArray, status: Int = 200, type: String = "application/json") {
        val response = Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(status)
            .message("fixture").body(body.toResponseBody(type.toMediaType())).build()
        checkNotNull(callback).onResponse(this, response)
    }

    fun fail() = checkNotNull(callback).onFailure(this, IOException("synthetic private network detail"))
}

class TavilySearchTest {
    private val calls = TavilyFixtureCalls()
    private val search = TavilySearch(calls)
    private val fixtureKey = "synthetic-only-session-key"

    private suspend fun request(query: String = "q", respond: (TavilyFixtureCall) -> Unit) = coroutineScope {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { search.search(query, fixtureKey) } }
        respond(calls.calls.last())
        pending.await()
    }

    @Test fun productionClientDisablesRedirectsAndRetryAndBoundsCallTime() {
        val client = TavilySearch.newClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(15_000, client.callTimeoutMillis)
    }

    @Test fun sendsOnlyTheExplicitJsonOnceAndMapsTheFirstCanonicalResult() = runBlocking {
        val hits = request("  合成 月相 & B  ") { call ->
            val sent = call.request()
            assertEquals("POST", sent.method)
            assertEquals("https://api.tavily.com/search", sent.url.toString())
            assertEquals("Bearer $fixtureKey", sent.header("Authorization"))
            assertEquals("application/json", sent.header("Accept"))
            val body = requireNotNull(sent.body)
            assertTrue(body.isOneShot())
            assertEquals("application/json", body.contentType().toString())
            val wire = Buffer().apply { body.writeTo(this) }.readUtf8()
            val json = JSONObject(wire)
            assertEquals(setOf("query", "max_results", "search_depth"), json.keys().asSequence().toSet())
            assertEquals("合成 月相 & B", json.getString("query"))
            assertEquals(10, json.getInt("max_results"))
            assertEquals("basic", json.getString("search_depth"))
            call.reply("""{"results":[{"title":"A","url":"https://Result.invalid/a","content":"一"},
                {"title":"later","url":"https://result.invalid:443/a"},
                {"url":"https://result.invalid/b","content":"乙"}]}""")
        }.getOrThrow()
        assertEquals(listOf(SearchHit("A", "https://result.invalid/a", "一"),
            SearchHit("result.invalid", "https://result.invalid/b", "乙")), hits)
        assertEquals(1, calls.calls.size)
    }

    @Test fun missingKeysAndInvalidInputsNeverConstructACall() = runBlocking {
        for (invalid in listOf("", "  ", "x".repeat(513), "q\u0000x")) {
            val e = runCatching { search.search(invalid, fixtureKey) }.exceptionOrNull() as SearchFailure
            assertEquals(SearchProblem.InvalidRequest, e.problem)
        }
        for (key in listOf("", "  ")) {
            val e = runCatching { search.search("q", key) }.exceptionOrNull() as SearchBlocked
            assertEquals(SearchBlocked.Reason.MissingCredential, e.reason)
        }
        for (key in listOf("a\r\nb", "ak 中")) {
            val e = runCatching { search.search("q", key) }.exceptionOrNull() as SearchFailure
            assertEquals("InvalidRequest", e.message)
        }
        assertTrue(calls.calls.isEmpty())
    }

    @Test fun boundsDisplayFieldsSkipsPrivateUrlsAndNeverDisplaysEchoedKey() = runBlocking {
        val rows = JSONArray().put(JSONObject().put("url", "file:///x"))
            .put(JSONObject().put("url", "https://person:secret@result.invalid/"))
            .put(JSONObject().put("url", "https://result.invalid/echo").put("content", fixtureKey))
            .put(JSONObject().put("url", "https://result.invalid/$fixtureKey"))
            .put(JSONObject().put("url", "https://result.invalid/title").put("title", "x".repeat(251) + fixtureKey))
            .put(JSONObject().put("url", "https://result.invalid/snippet").put("content", "y".repeat(1195) + fixtureKey))
        repeat(12) { i ->
            rows.put(JSONObject().put("url", "http://result.invalid/$i")
                .put("title", "x".repeat(300)).put("content", "y".repeat(1500)))
        }
        val hits = request { it.reply(JSONObject().put("results", rows).toString()) }.getOrThrow()
        assertEquals(10, hits.size)
        assertEquals("http://result.invalid/0", hits.first().url)
        assertEquals(256, hits.first().title.length)
        assertEquals(1200, hits.first().snippet.length)
        assertFalse(hits.any { fixtureKey in it.toString() })
        for (body in listOf("""{"results":[]}""", """{"results":[null,{"url":"mailto:me"}]}""")) {
            assertTrue(request { it.reply(body) }.getOrThrow().isEmpty())
        }
    }

    @Test fun classifiesFormatsLimitsAndFailureBodiesWithoutExposingTheirContents() = runBlocking {
        val malformed = listOf("""{"results":{}}""", "{}", """{"results":[]} {}""", """{'results':[]}""",
            "[".repeat(33) + "]".repeat(33), "{\"results\":[", """{"results":[falsee]}""",
            """{"results":[true,]}""", """{"results":[],}""", """{"results":[0.]}""",
            """{"results":[null false]}""", """{"results":["\z"]}""", """{"results":["\uZ001"]}""")
        for (body in malformed) {
            val e = request { it.reply(body) }.exceptionOrNull() as SearchFailure
            assertEquals(SearchProblem.InvalidResponse, e.problem)
        }
        val html = request { it.reply("<html>sensitive fixture</html>", type = "text/html") }.exceptionOrNull() as SearchFailure
        assertEquals(SearchProblem.InvalidResponse, html.problem)
        val badUtf8 = request { it.replyBytes(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }.exceptionOrNull() as SearchFailure
        assertEquals(SearchProblem.InvalidResponse, badUtf8.problem)
        val large = request { it.reply("x".repeat(512 * 1024 + 1)) }.exceptionOrNull() as SearchFailure
        assertEquals(SearchProblem.TooLarge, large.problem)

        for (status in listOf(302, 401, 403, 429, 500)) {
            val before = calls.calls.size
            val e = request { it.reply("synthetic private diagnostic $fixtureKey", status) }.exceptionOrNull() as SearchFailure
            assertEquals(before + 1, calls.calls.size)
            assertEquals(status, e.status)
            assertEquals("Http", e.message)
            assertNull(e.cause)
        }
        val network = request { it.fail() }.exceptionOrNull() as SearchFailure
        assertEquals("Network", network.message)
        assertNull(network.cause)
    }

    @Test fun validatesJsonNumbersEscapesAndTheExactNestingBoundary() = runBlocking {
        val body = """{"results":[null,false,true,-12.5e+5,0,{"url":"https://result.invalid/a","title":"\u4e59\nA"}],
            "metadata":""" + "[".repeat(31) + "true" + "]".repeat(31) + "}"
        assertEquals(listOf(SearchHit("乙\nA", "https://result.invalid/a", "")), request { it.reply(body) }.getOrThrow())
        val tooDeep = body.replace("\"metadata\":[", "\"metadata\":[[").dropLast(1) + "]}"
        val e = request { it.reply(tooDeep) }.exceptionOrNull() as SearchFailure
        assertEquals(SearchProblem.InvalidResponse, e.problem)
    }
}
