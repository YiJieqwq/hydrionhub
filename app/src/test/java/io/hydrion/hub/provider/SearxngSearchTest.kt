package io.hydrion.hub.provider

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearxngSearchTest {
    private val endpoint = SearchEndpoint("fixture", "My instance", "https://fixture.invalid/service/", SearchAdapter.Searxng)
    private fun fixture(body: String, status: Int = 200, type: String = "application/json") =
        SearxngSearch(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(status).body(body.toResponseBody(type.toMediaType())).build()
        })

    @Test fun savedOrUnknownServicesNeverBecomeExecutableImplicitly() = runBlocking {
        val legacy = """{"endpoints":[{"id":"fixture","name":"My instance","url":"https://fixture.invalid/service/"}]}"""
        val old = WebSearchSettings.decode(legacy)
        assertEquals(SearchAdapter.None, old.endpoints.single().adapter)
        assertEquals(old.endpoints, WebSearchSettings.decode(legacy.replace("\"name\"", "\"adapter\":\"other\",\"name\"")).endpoints)
        val chosen = requireNotNull(old.withEndpoint(endpoint))
        assertEquals(chosen, WebSearchSettings.decode(chosen.encode()))
        val transport = SearxngSearch(OkHttpClient.Builder().addInterceptor { error("unexpected request") })
        for ((row, query) in listOf(old.endpoints.single() to "query", endpoint to "   ",
                endpoint to "a".repeat(513), endpoint to "a\u0000b",
                endpoint.copy(url = "https://token@fixture.invalid/") to "query",
                endpoint.copy(url = "http://fixture.invalid/") to "query")) {
            val ex = runCatching { transport.search(row, query) }.exceptionOrNull() as SearchFailure
            assertEquals(SearchProblem.InvalidRequest, ex.problem)
        }
    }

    @Test fun syntheticLoopbackServerReceivesOnlyManualQueryAsFormAndRedirectionIsRejected() = runBlocking {
        val query = "find & test 中文"
        val reply = """{"results":[{"title":"Found","url":"https://result.invalid/item","content":"Excerpt"}]}"""
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val seen = async(Dispatchers.IO) {
                (0..1).map { step ->
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val line = reader.readLine()
                        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                        val count = headers.first { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim().toInt()
                        val form = String(CharArray(count) { reader.read().toChar() })
                        val result = if (step == 0) "200 OK\r\nContent-Type: application/json" else
                            "302 Found\r\nLocation: http://127.0.0.1:${server.localPort}/never"
                        val text = if (step == 0) reply else "sensitive response body"
                        socket.getOutputStream().write(("HTTP/1.1 $result\r\nContent-Length: ${text.length}\r\nConnection: close\r\n\r\n$text").toByteArray())
                        Triple(line, headers, form)
                    }
                }
            }
            val transport = SearxngSearch(OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("fixture.invalid", request.url.host)
                val local = request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.localPort).build()
                chain.proceed(request.newBuilder().url(local).build())
            })
            assertEquals(listOf(SearchHit("Found", "https://result.invalid/item", "Excerpt")), transport.search(endpoint, query))
            val failure = runCatching { transport.search(endpoint.copy(url = "https://fixture.invalid/service/search"), query) }
                .exceptionOrNull() as SearchFailure
            assertEquals(SearchProblem.Http, failure.problem)
            assertEquals(302, failure.status)
            assertEquals("Http", failure.message)
            seen.await().forEach { (line, headers, form) ->
                assertEquals("POST /service/search HTTP/1.1", line)
                assertTrue(headers.none { it.startsWith("Authorization:", true) || it.startsWith("Cookie:", true) })
                val fields = form.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                assertEquals(mapOf("q" to query, "format" to "json"), fields)
            }
        }
    }

    @Test fun boundsAndFiltersReturnedTextWithoutFetchingResultUrls() = runBlocking {
        val rows = JSONArray().put(JSONObject().put("url", "file:///data/content"))
            .put(JSONObject().put("url", "https://name:secret@result.invalid/private"))
        repeat(15) {
            val row = JSONObject().put("url", "https://result.invalid/$it").put("title", "x".repeat(500)).put("content", "z".repeat(2000))
            rows.put(row).put(row)
        }
        val results = fixture(JSONObject().put("results", rows).toString()).search(endpoint, "q")
        assertEquals(10, results.size)
        assertEquals("https://result.invalid/0", results.first().url)
        assertEquals(10, results.distinctBy { it.url }.size)
        assertEquals(256, results.first().title.length)
        assertEquals(1200, results.first().snippet.length)
        assertTrue(fixture("""{"results":[]}""").search(endpoint, "q").isEmpty())
    }

    @Test fun rejectsHtmlOversizedAndMalformedBodiesWithoutEchoingThem() = runBlocking {
        val cases = listOf(fixture("<html>private</html>", type = "text/html") to SearchProblem.InvalidResponse,
            fixture("{}".repeat(270_000)) to SearchProblem.TooLarge, fixture("{}") to SearchProblem.InvalidResponse,
            fixture("[".repeat(128) + "]".repeat(128)) to SearchProblem.InvalidResponse,
            fixture("private server diagnostic", 403) to SearchProblem.Http)
        for ((transport, reason) in cases) {
            val failure = runCatching { transport.search(endpoint, "private query") }.exceptionOrNull() as SearchFailure
            assertEquals(reason, failure.problem)
            assertEquals(reason.name, failure.message)
        }
    }

    @Test fun leavingDuringARequestCancelsTheCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val canceled = CountDownLatch(1)
        val transport = SearxngSearch(OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            started.complete(Unit)
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!chain.call().isCanceled() && System.nanoTime() < end) Thread.sleep(5)
            if (chain.call().isCanceled()) canceled.countDown()
            throw IOException("fixture")
        }))
        val job = launch { transport.search(endpoint, "fixture") }
        withTimeout(5_000) { started.await() }
        job.cancelAndJoin()
        assertTrue(canceled.await(5, TimeUnit.SECONDS))
    }
}
