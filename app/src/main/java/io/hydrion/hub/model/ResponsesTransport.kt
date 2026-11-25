package io.hydrion.hub.model

import io.hydrion.hub.provider.LocalModelRequest
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

internal class ResponsesTransport(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    fun stream(prepared: LocalModelRequest, request: Message): Flow<Message> =
        streamEvents(prepared, request, "Responses", ::readResponsesEvents)

    fun streamChatCompletions(prepared: LocalModelRequest, request: Message): Flow<Message> =
        streamEvents(prepared, request, "Chat Completions", ::readChatCompletionsEvents)

    private fun streamEvents(
        prepared: LocalModelRequest,
        request: Message,
        protocol: String,
        parse: suspend (BufferedReader, Message, ToolRecordText, AskWireRound?, suspend (Message) -> Unit) -> Unit
    ): Flow<Message> = callbackFlow {
        val connection = try {
            openConnection(URL(prepared.url))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IOException(redactTransportError(failure.message ?: "无法打开 $protocol 连接。", prepared.headers))
        }
        val reader = launch {
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 180_000
                connection.useCaches = false
                connection.doOutput = true
                prepared.headers.forEach(connection::setRequestProperty)
                connection.setRequestProperty("Accept", "text/event-stream")
                val bytes = prepared.body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                ensureActive()
                connection.outputStream.use {
                    ensureActive()
                    it.write(bytes)
                }
                ensureActive()
                val status = connection.responseCode
                if (status !in 200..299) throw IOException(connection.httpError(status, protocol))
                if (!connection.contentType.orEmpty().substringBefore(';').trim().equals("text/event-stream", true)) {
                    throw IOException("服务端未返回 $protocol 事件流（text/event-stream）。")
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                    parse(input, request, ToolRecordText(prepared.headers), prepared.questions) { send(it) }
                }
                close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A platform read can return EOF or an IOException when cancellation closes the socket.
                ensureActive()
                this@callbackFlow.ensureActive()
                val detail = failure.message?.takeIf(String::isNotBlank) ?: "$protocol 连接中断。"
                close(if (failure is AskUserFailure) failure else IOException(redactTransportError(detail, prepared.headers)))
            }
        }
        // Cancellation has to close the socket from outside the coroutine blocked on read.
        awaitClose {
            reader.cancel()
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}

private fun HttpURLConnection.httpError(status: Int, protocol: String): String {
    val prefix = "$protocol HTTP $status"
    if (status == 401) return "$prefix · API Key 无效或已过期。"
    if (status == 403) return "$prefix · 当前 API Key 没有访问权限。"
    val body = errorStream?.bufferedReader(Charsets.UTF_8)?.use { input ->
        val buffer = CharArray(8_192)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count <= 0) break
            size += count
        }
        String(buffer, 0, size)
    } ?: return prefix
    val detail = try {
        val json = JSONObject(body)
        val fields = json.optJSONObject("error") ?: json
        fields.opt("message") as? String ?: fields.opt("code") as? String
    } catch (_: JSONException) {
        null
    }
    return if (detail.isNullOrBlank()) prefix else "$prefix · $detail"
}

internal fun redactTransportError(detail: String, headers: Map<String, String>): String =
    redactTransportSecrets(detail, headers).take(800)

internal fun redactTransportSecrets(detail: String, headers: Map<String, String>): String {
    var safe = detail
    for ((name, value) in headers) {
        if (value.isBlank() || name.equals("Content-Type", true) || name.equals("Accept", true)) continue
        safe = safe.replace(value, "[redacted]")
        if (name.equals("Authorization", true)) {
            val credential = value.substringAfter(' ').trim()
            if (credential.isNotEmpty()) safe = safe.replace(credential, "[redacted]")
        }
    }
    safe = bearerToken.replace(safe, "Bearer [redacted]")
    safe = apiKey.replace(safe, "[redacted]")
    return safe
}

private val bearerToken = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
private val apiKey = Regex("""\bsk-[A-Za-z0-9_-]+""")
