package io.hydrion.hub.model

import io.hydrion.hub.provider.LocalModelRequest
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekResponsesTransportTest {
    private val reply = Message(role = Role.Assistant, status = Generation.Sending)
    private val key = "session-test-credential"

    private fun prepared(url: String = "https://api.deepseek.com/responses") = LocalModelRequest(
        model = "DeepSeek", url = url,
        headers = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
        body = JSONObject("""{"model":"deepseek-flash","input":"你好","stream":true}"""), parameters = emptyMap()
    )

    private fun event(json: String) = "data: $json\n\n"

    @Test fun postsPreparedBodyAndStreamsReasoningAndAnswerOverHttp() = runBlocking {
        val wire = "\uFEFF" + """
            event: response.reasoning_text.delta
            data: {"output_index":0,"content_index":0,"delta":"思考"}

            : heartbeat

            data: {"type":"response.reasoning_text.done","output_index":0,"content_index":0,"text":"思考好了"}

            data: {"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"你"}

            data: {"type":"response.output_text.done","output_index":1,"content_index":0,"text":"你好"}

            event: response.completed
            data: {"response":{"status":"completed","usage":{"total_tokens":42,"input_tokens":6,"output_tokens":7},"output":[
            data: {"type":"reasoning","content":[{"type":"reasoning_text","text":"思考好了"}]},
            data: {"type":"message","content":[{"type":"output_text","text":"你好"},{"type":"output_text","text":"！"}]}]}}
        """.trimIndent().replace("\n", "\r\n") + "\r\n\r\n"
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val exchange = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = DataInputStream(socket.getInputStream().buffered())
                    @Suppress("DEPRECATION") // HTTP headers are ASCII; the UTF-8 body is read as bytes.
                    val headers = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    val body = ByteArray(length)
                    input.readFully(body)
                    val bytes = wire.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                    headers to String(body, Charsets.UTF_8)
                }
            }
            val request = prepared("http://127.0.0.1:${server.localPort}/responses")
            val received = DeepSeekResponsesTransport().stream(request, reply).toList()
            val (headers, body) = exchange.await()

            assertEquals("POST /responses HTTP/1.1", headers.first())
            assertTrue(headers.any { it.equals("Accept: text/event-stream", true) })
            assertTrue(headers.any { it.equals("Authorization: Bearer $key", true) })
            assertEquals(request.body.toString(), body)
            assertTrue(received.any { it.status == Generation.Generating && it.thought == "思考" })
            assertTrue(received.any { it.status == Generation.Generating && it.text == "你" })
            val last = received.last()
            assertEquals("思考好了", last.thought)
            assertEquals("你好！", last.text)
            assertEquals(Generation.Completed, last.status)
            assertEquals(42L, last.totalTokens)
        }
    }

    @Test fun distinguishesMissingUsageFromZeroAndFallsBackToTheComponentSum() = runBlocking {
        val usages = listOf(
            "" to null,
            ""","usage":{"input_tokens":7}""" to null,
            ""","usage":{"output_tokens":11}""" to null,
            ""","usage":{"input_tokens":9223372036854775807,"output_tokens":1}""" to null,
            ""","usage":{"input_tokens":7,"output_tokens":11}""" to 18L,
            ""","usage":{"total_tokens":0,"input_tokens":0,"output_tokens":0}""" to 0L
        )
        for ((usage, expected) in usages) {
            val response = event("""{"type":"response.completed","response":{"status":"completed","output":[]$usage}}""")
            val connection = RecordingConnection(response.byteInputStream())
            val messages = DeepSeekResponsesTransport { connection }.stream(prepared(), reply).toList()
            assertEquals(expected, messages.last().totalTokens)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun incompleteFailedAndBrokenStreamsKeepTheLastPartialReply() = runBlocking {
        val partial = event("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"partial"}""")
        val incomplete = event("""{"type":"response.incomplete","response":{"usage":{"total_tokens":13},"incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"message","content":[{"type":"output_text","text":"partial+"}]}]}}""")
        val failed = event("""{"type":"response.failed","response":{"error":{"message":"busy"}}}""")
        for ((tail, detail) in listOf(incomplete to "max_output_tokens", failed to "busy", "" to "中断")) {
            val connection = RecordingConnection((partial + tail).byteInputStream())
            val seen = mutableListOf<Message>()
            val failure = runCatching {
                DeepSeekResponsesTransport { connection }.stream(prepared(), reply).toList(seen)
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure?.message.orEmpty().contains(detail))
            assertEquals(if (tail == incomplete) "partial+" else "partial", seen.last().text)
            assertEquals(if (tail == incomplete) 13L else null, seen.last().totalTokens)
            assertFalse(seen.any { it.status == Generation.Completed })
            assertTrue(connection.disconnected)
        }
    }

    @Test fun httpFailuresDoNotExposeCredentialsOrFollowRedirects() = runBlocking {
        for (status in listOf(307, 400, 401, 403)) {
            val connection = RecordingConnection("""{"error":{"message":"bad $key"}}""".byteInputStream(), status)
            val failure = runCatching {
                DeepSeekResponsesTransport { connection }.stream(prepared(), reply).toList()
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            val detail = failure?.message.orEmpty()
            assertTrue(detail.contains(status.toString()))
            assertFalse(detail.contains(key))
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun stopDisconnectsAReadBlockedBeforeTheFirstEvent() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val disconnected = CountDownLatch(1)
        val blocked = object : InputStream() {
            override fun read(): Int {
                reading.complete(Unit)
                check(disconnected.await(8, TimeUnit.SECONDS))
                return -1
            }
        }
        val connection = RecordingConnection(blocked, onDisconnect = { disconnected.countDown() })
        val job = launch { DeepSeekResponsesTransport { connection }.stream(prepared(), reply).collect() }
        withTimeout(4_000) { reading.await() }
        withTimeout(4_000) { job.cancelAndJoin() }
        assertTrue(connection.disconnected)
    }

    @Test fun stopDuringRequestSetupNeverStartsThePost() = runBlocking {
        val configuring = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        val connection = object : RecordingConnection("".byteInputStream()) {
            override fun setRequestMethod(method: String) {
                configuring.complete(Unit)
                check(resume.await(8, TimeUnit.SECONDS))
                super.setRequestMethod(method)
            }
        }
        val job = launch { DeepSeekResponsesTransport { connection }.stream(prepared(), reply).collect() }
        try {
            withTimeout(4_000) { configuring.await() }
            job.cancel()
        } finally {
            resume.countDown()
        }
        withTimeout(4_000) { job.join() }
        assertFalse(connection.startedPost)
        assertTrue(connection.disconnected)
    }

    private open class RecordingConnection(
        private val source: InputStream,
        private val status: Int = 200,
        private val onDisconnect: () -> Unit = {}
    ) : HttpURLConnection(URL("https://api.deepseek.com/responses")) {
        @Volatile var disconnected = false
        @Volatile var startedPost = false
        override fun disconnect() {
            disconnected = true
            onDisconnect()
        }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream(): ByteArrayOutputStream {
            startedPost = true
            return ByteArrayOutputStream()
        }
        override fun getInputStream() = source
        override fun getErrorStream() = source
        override fun getResponseCode() = status
        override fun getContentType() = "text/event-stream; charset=utf-8"
    }
}
