package io.hydrion.hub.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.provider.LocalModelRequest
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenAiImagesTransportTest {
    private val endpoint = "https://api.openai.com/v1/images/generations"
    private val credential = "fixture-only-value"
    private val headers = mapOf("Authorization" to "Bearer $credential", "OpenAI-Project" to "fixture")
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAEUlEQVR4nGPg9P34/4tW2H8AEwYEunyvEL8AAAAASUVORK5CYII="
    private val item = """{"b64_json":"$png"}"""
    private val single = """{"output_format":"png","data":[$item]}"""

    private fun prepared(count: Int = 1, url: String = endpoint, requestHeaders: Map<String, String> = headers) =
        LocalModelRequest("fixture", requestHeaders, JSONObject().put("model", "gpt-image-1.5")
            .put("prompt", "a lighthouse").put("n", count), emptyMap(), url)

    private class FakeCall(
        private val req: Request,
        private val run: (FakeCall, Callback) -> Unit,
        private val abort: () -> Unit = {}
    ) : Call {
        var cancelled = false
        private var ran = false
        override fun request() = req
        override fun timeout() = Timeout.NONE
        override fun isExecuted() = ran
        override fun isCanceled() = cancelled
        override fun cancel() { cancelled = true; abort() }
        override fun execute(): Response = error("expected async request")
        override fun enqueue(responseCallback: Callback) {
            check(!ran)
            ran = true
            run(this, responseCallback)
        }
        public override fun clone(): Call = error("unexpected replay")
    }

    private fun content(json: String, type: String = "application/json", length: Long = -1L) = object : ResponseBody() {
        private val source = Buffer().writeUtf8(json)
        override fun contentType() = type.toMediaType()
        override fun contentLength() = length
        override fun source(): BufferedSource = source
    }

    private fun response(request: Request, body: ResponseBody, status: Int) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body).build()

    private fun fixture(body: ResponseBody, status: Int = 200, imageLimit: Int = 1000, batch: Int = 1500,
                        inspect: (Request) -> Unit = {}) =
        OpenAiImagesTransport(Call.Factory { req ->
            inspect(req)
            FakeCall(req, { call, callback -> callback.onResponse(call, response(req, body, status)) })
        }, imageLimit, batch)

    @Test fun forcesPngAndUsesSinglePostWithoutAlteringOriginalModelRequest() = runBlocking {
        val prepared = prepared(2)
        var requests = 0
        val json = """{"usage":{"total_tokens":5},"data":[$item,{"revised_prompt":"q","b64_json":"$png"}]}"""
        val images = fixture(content(json)) { request ->
            requests++
            assertEquals(endpoint, request.url.toString())
            assertEquals("POST", request.method)
            assertEquals("application/json", request.header("Accept"))
            assertEquals("Bearer $credential", request.header("Authorization"))
            assertEquals("fixture", request.header("OpenAI-Project"))
            val body = requireNotNull(request.body)
            assertTrue(body.isOneShot())
            val buf = Buffer()
            body.writeTo(buf)
            val wire = JSONObject(buf.readUtf8())
            assertEquals("png", wire.getString("output_format"))
            assertEquals(2, wire.getInt("n"))
            assertFalse(wire.has("response_format"))
            assertFalse(wire.toString().contains(credential))
        }.generate(prepared)
        assertEquals(1, requests)
        assertEquals(2, images.size)
        for (bytes in images) assertArrayEquals(Base64.getDecoder().decode(png), bytes)
        assertFalse(prepared.body.has("output_format"))
    }

    @Test fun rejectsUrlsDuplicateFieldsBadPngWrongCountsDepthAndTrailingContent() = runBlocking {
        val badEscape = "\\" + "uZZZZ"
        val bad = listOf(
            """{"data":[{"b64_json":"$badEscape"}]}""",
            """{"data":[{"url":"https://example.invalid/a"}]}""",
            """{"data":[{"url":null,"b64_json":"$png"}]}""",
            """{"data":[{"b64_json":"$png","b64_json":"$png"}]}""",
            """{"data":[$item],"data":[$item]}""",
            """{"output_format":"jpeg","data":[$item]}""",
            """{"data":[{"b64_json":"@@@@"}]}""",
            """{"data":[{"b64_json":"SGVsbG8="}]}""",
            """{"data":[{"b64_json":"${png.dropLast(8)}"}]}""",
            """{"data":[$item,$item]}""", """{"data":[]}""",
            """{"unknown":${"[".repeat(80)}true${"]".repeat(80)},"data":[$item]}""",
            single + " {}", single.dropLast(1)
        )
        for (input in bad) {
            val failure = runCatching { fixture(content(input)).generate(prepared()) }.exceptionOrNull()
            assertTrue(failure is IOException)
        }
        val missing = runCatching { fixture(content(single)).generate(prepared(2)) }.exceptionOrNull()
        assertTrue(missing is IOException)
    }

    @Test fun enforcesSizeUsingActualBytesEvenWithoutLength() = runBlocking {
        val bytes = Base64.getDecoder().decode(png).size
        val two = """{"data":[$item,$item]}"""
        val oversized = listOf(
            fixture(content(single, length = Long.MAX_VALUE)) to prepared(),
            fixture(content(" ".repeat(140 * 1024) + single)) to prepared(),
            fixture(content(single), imageLimit = bytes - 1) to prepared(),
            fixture(content(two), batch = 2 * bytes - 1) to prepared(2),
            fixture(content(single, type = "image/png")) to prepared()
        )
        for ((adapter, request) in oversized) {
            assertTrue(runCatching { adapter.generate(request) }.exceptionOrNull() is IOException)
        }
    }

    @Test fun rejectsUnsafeRequestsBeforeAccessingCallFactoryAndHidesServiceBodies() = runBlocking {
        val none = OpenAiImagesTransport(Call.Factory { error("unexpected request") })
        val requests = listOf(prepared(url = endpoint + "?"), prepared(url = endpoint.replace("https:", "http:")),
            prepared(url = endpoint.replace("api.openai.com", "example.invalid")), prepared(count = 11),
            prepared(requestHeaders = emptyMap()), prepared(requestHeaders = headers + ("Host" to "example.invalid")),
            prepared(requestHeaders = mapOf("Authorization" to "Basic sentry")))
        for (request in requests) {
            assertTrue(runCatching { none.generate(request) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (status in listOf(301, 307, 401, 403, 429, 503)) {
            val failure = runCatching {
                fixture(content("""{"error":{"message":"$credential"}}"""), status).generate(prepared())
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message.orEmpty().contains(status.toString()))
            assertFalse(failure.message.orEmpty().contains(credential))
        }
    }

    @Test fun cancellationOfBlockedResponseClosesUnderlyingIo() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val finished = CompletableDeferred<Unit>()
            lateinit var fake: FakeCall
            val blocked = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    entered.complete(Unit)
                    check(release.await(4, TimeUnit.SECONDS))
                    throw IOException("fixture closed")
                }
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = -1L
                override fun source() = blocked
            }
            val adapter = OpenAiImagesTransport(Call.Factory { request ->
                FakeCall(request, { call, callback ->
                    Thread {
                        try { callback.onResponse(call, response(request, body, 200)) }
                        finally { finished.complete(Unit) }
                    }.start()
                }, abort = release::countDown).also { fake = it }
            })
            val job = launch(Dispatchers.Default) { adapter.generate(prepared()) }
            entered.await()
            job.cancelAndJoin()
            finished.await()
            assertTrue(fake.cancelled)
        }
    }
}
