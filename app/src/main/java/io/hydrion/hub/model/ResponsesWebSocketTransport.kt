package io.hydrion.hub.model

import io.hydrion.hub.provider.LocalModelRequest
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

private const val OFFICIAL_WS = "wss://api.openai.com/v1/responses"

private val officialClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS) // HTTP upgrade
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .build()
}

/**
 * OkHttp 4.12 can follow a 503 Retry-After: 0 even with connection retries disabled.
 * Its WebSocket path skips network interceptors. The bridge asks the cookie jar before
 * writing each HTTP request, including follow-ups. Each new socket gets one allowance.
 */
internal class SingleHandshake : CookieJar {
    private val used = AtomicBoolean()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!used.compareAndSet(false, true)) throw IOException("已阻止重复的 WebSocket 握手。")
        return emptyList()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
}

internal class ResponsesWebSocketTransport(
    private val open: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        officialClient.newBuilder().cookieJar(SingleHandshake()).build().newWebSocket(request, listener)
    },
    private val handshakeTimeoutMs: Long = 20_000,
    private val idleTimeoutMs: Long = 180_000
) {
    fun stream(prepared: LocalModelRequest, request: Message): Flow<Message> = flow {
        val handshake = handshakeRequest(prepared)
        val payload = withContext(Dispatchers.Default) { createPayload(prepared.body) }
        val opened = CompletableDeferred<Unit>()
        // Backpressure the reader instead of dropping deltas or buffering unbounded snapshots.
        val incoming = Channel<String>(8)
        val listener = object : WebSocketListener() {
            private fun fail(error: IOException) {
                opened.completeExceptionally(error)
                incoming.close(error)
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.code != 101 || response.header("Sec-WebSocket-Protocol") != null) {
                    fail(IOException("OpenAI WebSocket 握手协议不匹配。"))
                    webSocket.cancel()
                } else {
                    opened.complete(Unit)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 4 * 1024 * 1024) {
                    fail(IOException("WebSocket 事件超过大小限制。"))
                    webSocket.cancel()
                } else {
                    incoming.trySendBlocking(text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                fail(IOException("Responses WebSocket 返回了二进制事件。"))
                webSocket.cancel()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = closedEarly(code)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closedEarly(code)

            private fun closedEarly(code: Int) = fail(IOException("在收到 Responses 结束事件前连接已关闭（$code）。"))

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { "OpenAI WebSocket 握手失败（HTTP ${it.code}）。" }
                    ?: "OpenAI WebSocket 连接失败。"
                fail(IOException(detail))
            }
        }

        val turn = ResponsesEventAccumulator(request, ToolRecordText(prepared.headers))
        emit(turn.message)
        currentCoroutineContext().ensureActive()
        val socket = open(handshake, listener)
        var completed = false
        try {
            withTimeoutOrNull(handshakeTimeoutMs) { opened.await() } ?: throw IOException("OpenAI WebSocket 握手超时。")
            currentCoroutineContext().ensureActive()
            if (!withContext(Dispatchers.IO) { socket.send(payload) }) throw IOException("未能发送 WebSocket 对话请求。")
            while (true) {
                val frame = withTimeoutOrNull(idleTimeoutMs) { incoming.receive() }
                    ?: throw IOException("等待 OpenAI WebSocket 事件超时。")
                val update = withContext(Dispatchers.Default) { turn.accept(frame, "") }
                val failure = update.failure
                val safe = if (failure == null) update else update.copy(
                    failure = IOException(redactTransportError(failure.message.orEmpty(), prepared.headers))
                )
                if (safe.deliver { emit(it) }) {
                    completed = true
                    break
                }
            }
        } finally {
            incoming.cancel()
            opened.cancel()
            if (!completed || !socket.close(1000, null)) socket.cancel()
        }
    }

    private fun handshakeRequest(prepared: LocalModelRequest): Request {
        val uri = runCatching { URI(prepared.url) }.getOrNull()
        require(uri != null && (uri.scheme.equals("wss", true) || uri.scheme.equals("https", true)) &&
            (uri.rawAuthority.equals("api.openai.com", true) || uri.rawAuthority.equals("api.openai.com:443", true)) &&
            uri.rawPath == "/v1/responses" && uri.rawQuery == null && uri.rawFragment == null) {
            "Responses WebSocket 仅支持 OpenAI 官方 /v1/responses 地址。"
        }
        val builder = Request.Builder().url(OFFICIAL_WS)
        val allowed = setOf("authorization", "openai-organization", "openai-project", "x-client-request-id")
        val seen = mutableSetOf<String>()
        for ((name, value) in prepared.headers) {
            val key = name.lowercase()
            if (key == "accept" || key == "content-type") continue
            require(key in allowed && seen.add(key)) { "WebSocket 握手不支持此自定义请求头。" }
            require(value.isNotBlank() && value.all { it.code in 32..126 }) { "WebSocket 请求头含有无效字符。" }
            if (key == "authorization") {
                require(Regex("(?i)Bearer [!-~]+").matches(value)) { "OpenAI WebSocket 需要 Bearer 授权。" }
            }
            builder.header(name, value)
        }
        require("authorization" in seen) { "OpenAI WebSocket 需要 Bearer 授权。" }
        return builder.build()
    }

    private fun createPayload(body: JSONObject): String {
        require((body.opt("model") as? String)?.isNotBlank() == true && (body.optJSONArray("input")?.length() ?: 0) > 0) {
            "WebSocket 请求缺少模型或完整对话历史。"
        }
        require(body.isNull("previous_response_id") && body.isNull("conversation") &&
            (body.isNull("background") || body.opt("background") == false) &&
            !body.has("stream_id") && !body.has("generate") && !body.has("type")) {
            "WebSocket 每轮使用本机完整历史，请移除服务端会话或流控制字段。"
        }
        val frame = JSONObject()
        val httpFields = setOf("stream", "background", "previous_response_id", "conversation", "stream_options")
        body.keys().forEach { key -> if (key !in httpFields) frame.put(key, body.get(key)) }
        return frame.put("type", "response.create").put("store", false).toString()
    }
}
