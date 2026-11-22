package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ModelDiscoveryTest {
    private val provider = Provider("custom", Mode.Chat, "Fixture", ChatProtocol.ChatCompletionsHttp.label,
        "https://fixture.invalid:8443/tenant%20one/v1/chat/completions", emptyList())
    private val json = "application/json".toMediaType()

    private fun discovery(handle: (Request) -> Response) =
        ModelDiscovery(OkHttpClient.Builder().addInterceptor { chain -> handle(chain.request()) })

    private fun response(request: Request, body: ResponseBody, status: Int = 200, location: String? = null): Response {
        val result = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(status).message("fixture").body(body)
        if (location != null) result.header("Location", location)
        return result.build()
    }

    private suspend fun assertCleanFailure(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("Model discovery failed", failure?.message)
        assertNull(failure?.cause)
    }

    @Test fun keepsTheProxyPathAndSendsOnlyTheProvidedKey() = runBlocking {
        val requests = mutableListOf<Request>()
        val fixture = discovery { request ->
            requests += request
            val body = """{"data":[{"id":"Team/Chat:v1"},{"id":"team/chat:v1"},{"id":"Team/Chat:v1"}]}"""
            response(request, body.toResponseBody(json))
        }
        val model = ProviderModel("local", "Fixture", profile = ModelProfile(headers = mapOf(
            "Authorization" to "ignored", "X-Model-Header" to "ignored")))
        val expected = listOf("Team/Chat:v1", "team/chat:v1")
        assertEquals(expected, fixture.fetch(provider.copy(models = listOf(model)), "test-token"))
        assertEquals(expected, fixture.fetch(provider.copy(baseUrl = "https://api.openai.com"), ""))

        assertEquals(listOf("https://fixture.invalid:8443/tenant%20one/v1/models", "https://api.openai.com/v1/models"),
            requests.map { it.url.toString() })
        assertTrue(requests.all { it.method == "GET" && it.body == null && it.header("Accept") == "application/json" })
        assertEquals("Bearer test-token", requests.first().header("Authorization"))
        assertNull(requests.last().header("Authorization"))
        assertTrue(requests.none { it.header("X-Model-Header") != null })
    }

    @Test fun unsupportedProvidersAndInvalidEndpointsNeverDispatch() = runBlocking {
        var calls = 0
        val fixture = discovery { request ->
            calls++
            response(request, """{"data":[]}""".toResponseBody(json))
        }
        for (row in listOf(provider.copy(id = "demo-chat"), provider.copy(id = DEEPSEEK_PROVIDER_ID),
            provider.copy(mode = Mode.Image), provider.copy(protocol = "OpenAI Compatible"),
            provider.copy(protocol = ChatProtocol.ResponsesHttp.label),
            provider.copy(protocol = ChatProtocol.ResponsesWebSocket.label))) {
            assertFalse(row.supportsModelDiscovery())
            assertCleanFailure { fixture.fetch(row, "") }
        }
        assertTrue(provider.copy(baseUrl = "").supportsModelDiscovery())
        for (base in listOf("", "http://fixture.invalid/v1", "https://fixture.invalid/v1?private=1",
            "https://test@fixture.invalid/v1", "https://fixture.invalid/v1/../other")) {
            assertCleanFailure { fixture.fetch(provider.copy(baseUrl = base), "") }
        }
        assertCleanFailure { fixture.fetch(provider, "bad\r\nheader") }
        assertEquals(0, calls)
    }

    @Test fun redirectsAndHttpOrTransportFailuresNeverExposeDetails() = runBlocking {
        for (target in listOf("/same-origin", "https://other.invalid/models")) {
            var calls = 0
            val fixture = discovery { request ->
                calls++
                response(request, "private redirect".toResponseBody(json), 307, target)
            }
            assertCleanFailure { fixture.fetch(provider, "test-token") }
            assertEquals(1, calls)
        }
        val forbidden = discovery { request -> response(request, "private diagnostic".toResponseBody(json), 403) }
        assertCleanFailure { forbidden.fetch(provider, "") }
        val failed = discovery { throw IOException("private transport diagnostic") }
        assertCleanFailure { failed.fetch(provider, "") }
    }

    @Test fun validatesIdsAndBoundsUnknownLengthResponsesBeforeReturningResults() = runBlocking {
        val tooMany = List(2049) { """{"id":"same"}""" }.joinToString(",")
        val inputs = listOf("{", "{}", """{"data":{}}""", """{"data":[null]}""", """{"data":[{"id":12}]}""",
            """{"data":[{"id":" leading"}]}""", """{"data":[{"id":"trailing "}]}""", """{"data":[{"id":"\u0001"}]}""",
            """{"data":[{"id":""}]}""", """{"data":[{"id":"${"x".repeat(513)}"}]}""", """{"data":[$tooMany]}""",
            """{"data":[]}another document""", "[".repeat(256) + "]".repeat(256), " ".repeat(1024 * 1024 + 1))
        for (input in inputs) {
            val fixture = discovery { request -> response(request, Buffer().writeUtf8(input).asResponseBody(json, -1L)) }
            assertCleanFailure { fixture.fetch(provider, "") }
        }
        val empty = discovery { request -> response(request, """{"data":[]}""".toResponseBody(json)) }
        assertEquals(emptyList<String>(), empty.fetch(provider, ""))
    }

    @Test fun cancelingAnInFlightFetchCancelsItsCall() = runBlocking {
        val started = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val fixture = ModelDiscovery(OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            release.await(5, TimeUnit.SECONDS)
            throw IOException("fixture canceled")
        })
        val fetching = launch { fixture.fetch(provider, "") }
        try {
            val call = withTimeout(5_000) { started.await() }
            fetching.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            fetching.cancel()
        }
    }
}
