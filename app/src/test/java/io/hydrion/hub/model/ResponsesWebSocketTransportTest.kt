package io.hydrion.hub.model

import io.hydrion.hub.provider.LocalModelRequest
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesWebSocketTransportTest {
    private val reply = Message(role = Role.Assistant, status = Generation.Sending)
    private val credential = "fixture-authorization-value"
    private val endpoint = "https://api.openai.com/v1/responses"
    private val headers = mapOf("Authorization" to "Bearer $credential", "Accept" to "text/event-stream")
    private val delta = """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"answer"}"""
    private val done = """{"type":"response.completed","response":{"status":"completed","usage":{"total_tokens":17}}}"""

    private fun history(vararg texts: String) = JSONObject().put("model", "fixture-model").put("input",
        JSONArray().also { input ->
            texts.forEachIndexed { index, text ->
                input.put(JSONObject().put("role", if (index % 2 == 0) "user" else "assistant").put("content", text))
            }
        })

    private fun prepared(url: String = endpoint, fields: Map<String, String> = headers, body: JSONObject = history("one")) =
        LocalModelRequest(model = "Fixture", headers = fields, body = body, parameters = emptyMap(), url = url)

    private class Socket(
        private val handshake: Request,
        private val listener: WebSocketListener,
        private val script: Socket.() -> Unit
    ) : WebSocket {
        val sent = mutableListOf<String>()
        var cancelled = false
        var closeCode: Int? = null

        fun open(subprotocol: String? = null) {
            val response = Response.Builder().request(handshake).protocol(Protocol.HTTP_1_1).code(101).message("Upgrade")
            if (subprotocol != null) response.header("Sec-WebSocket-Protocol", subprotocol)
            listener.onOpen(this, response.build())
        }
        fun text(frame: String) = listener.onMessage(this, frame)
        fun binary() = listener.onMessage(this, ByteString.EMPTY)
        fun disconnect() = listener.onClosing(this, 1001, "")
        override fun request() = handshake
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            sent.add(text)
            script()
            return true
        }
        override fun send(bytes: ByteString): Boolean = error("unexpected binary request")
        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            return true
        }
        override fun cancel() { cancelled = true }
    }

    @Test fun eachRequestAndExplicitRetryUseOneNewSocketAndOneFullHistoryFrame() = runBlocking {
        val sockets = mutableListOf<Socket>()
        val transport = ResponsesWebSocketTransport(open = { request, listener ->
            Socket(request, listener) {
                text("""{"type":"response.reasoning_summary_text.delta","summary_index":0,"delta":"summary"}""")
                text(delta)
                text(done)
            }.also { sockets += it; it.open() }
        })
        val bodies = listOf(history("one"), history("one", "answer", "two"), history("one"))
        for (body in bodies) {
            body.put("stream", true).put("background", false)
            val messages = transport.stream(prepared(body = body), reply).toList()
            assertEquals(Generation.Generating, messages.first().status)
            assertEquals("summary", messages.last().thought)
            assertEquals("answer", messages.last().text)
            assertEquals(17L, messages.last().usage?.totalTokens)
            assertEquals(Generation.Completed, messages.last().status)
        }
        assertEquals(3, sockets.size)
        sockets.forEachIndexed { index, socket ->
            assertEquals("Bearer $credential", socket.request().header("Authorization"))
            assertNull(socket.request().header("Accept"))
            assertEquals(endpoint, socket.request().url.toString())
            assertEquals(1000, socket.closeCode)
            assertFalse(socket.cancelled)
            val frame = JSONObject(socket.sent.single())
            assertEquals("response.create", frame.getString("type"))
            assertEquals("fixture-model", frame.getString("model"))
            assertFalse(frame.getBoolean("store"))
            assertEquals(bodies[index].getJSONArray("input").toString(), frame.getJSONArray("input").toString())
            listOf("previous_response_id", "stream", "background").forEach { assertFalse(frame.has(it)) }
            assertFalse(frame.toString().contains(credential))
            assertTrue(bodies[index].has("stream"))
        }
    }

    @Test fun errorsAreRedactedAndRetainPreviouslyDeliveredText() = runBlocking {
        val scripts = listOf<Socket.() -> Unit>(
            { text("""{"type":"error","error":{"message":"bad $credential"}}""") },
            { text("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""") },
            { text("""{"type":"response.failed","response":{"error":{"message":"busy"}}}""") },
            { text("invalid-json") }, { disconnect() }, { binary() }
        )
        for (script in scripts) {
            lateinit var socket: Socket
            val transport = ResponsesWebSocketTransport(open = { req, listener ->
                Socket(req, listener) { text(delta); script() }.also { socket = it; it.open() }
            })
            val seen = mutableListOf<Message>()
            val error = runCatching { transport.stream(prepared(), reply).toList(seen) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(error!!.message.orEmpty().contains(credential))
            assertEquals("answer", seen.last().text)
            assertFalse(seen.any { it.status == Generation.Completed })
            assertTrue(socket.cancelled)
            assertEquals(1, socket.sent.size)
        }
    }

    @Test fun unsafeDestinationCredentialsHandshakeAndStateFieldsAreRejectedBeforeOpening() = runBlocking {
        val transport = ResponsesWebSocketTransport(open = { _, _ -> error("unexpected network attempt") })
        val invalid = listOf(
            prepared("ws://api.openai.com/v1/responses"),
            prepared("wss://api.deepseek.com/v1/responses"),
            prepared("wss://api.openai.com.example/v1/responses"),
            prepared("wss://user@api.openai.com/v1/responses"),
            prepared("wss://api.openai.com:8443/v1/responses"),
            prepared("$endpoint?"), prepared("$endpoint#fragment"),
            prepared("wss://api.openai.com/v1/realtime"),
            prepared(fields = headers + ("Sec-WebSocket-Protocol" to "realtime")),
            prepared(fields = headers + ("Host" to "other")),
            prepared(fields = emptyMap()),
            prepared(fields = mapOf("Authorization" to "Basic fixture")),
            prepared(body = history("one").put("previous_response_id", "r1")),
            prepared(body = history("one").put("generate", false)),
            prepared(body = history("one").put("stream_id", "other"))
        )
        invalid.forEach { request ->
            assertTrue(runCatching { transport.stream(request, reply).toList() }.exceptionOrNull() is IllegalArgumentException)
        }
        val guard = SingleHandshake()
        val url = Request.Builder().url(endpoint).build().url
        assertTrue(guard.loadForRequest(url).isEmpty())
        assertThrows(IOException::class.java) { guard.loadForRequest(url) }
        Unit
    }

    @Test fun cancellingBeforeOrAfterOpenAbortsSocketWithoutReplay() = runBlocking {
        withTimeout(5_000) {
            for (upgraded in listOf(false, true)) {
                val ready = CompletableDeferred<Socket>()
                val sent = CompletableDeferred<Unit>()
                val transport = ResponsesWebSocketTransport(open = { req, listener ->
                    Socket(req, listener) { sent.complete(Unit) }.also {
                        ready.complete(it)
                        if (upgraded) it.open()
                    }
                })
                val job = launch { transport.stream(prepared(), reply).collect() }
                val socket = ready.await()
                if (upgraded) sent.await()
                job.cancelAndJoin()
                if (!upgraded) socket.open()
                assertTrue(socket.cancelled)
                assertEquals(if (upgraded) 1 else 0, socket.sent.size)
            }
        }
    }

    @Test fun handshakeAndIdleTimeoutAndForeignSubprotocolAbort() = runBlocking {
        for (stage in listOf("handshake", "idle", "protocol")) {
            lateinit var socket: Socket
            val transport = ResponsesWebSocketTransport(open = { req, listener ->
                Socket(req, listener) { }.also {
                    socket = it
                    if (stage != "handshake") it.open(if (stage == "protocol") "realtime" else null)
                }
            }, handshakeTimeoutMs = 40, idleTimeoutMs = 40)
            assertTrue(runCatching { transport.stream(prepared(), reply).toList() }.exceptionOrNull() is IOException)
            assertTrue(socket.cancelled)
            assertEquals(if (stage == "idle") 1 else 0, socket.sent.size)
        }
    }
}
