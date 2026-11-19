package io.hydrion.hub.model

import io.hydrion.hub.provider.LocalModelRequest
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

internal class DeepSeekResponsesTransport(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    fun stream(prepared: LocalModelRequest, request: Message): Flow<Message> = callbackFlow {
        val connection = openConnection(URL(prepared.url))
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
                if (status !in 200..299) throw IOException(connection.httpError(status))
                if (!connection.contentType.orEmpty().substringBefore(';').trim().equals("text/event-stream", true)) {
                    throw IOException("DeepSeek 未返回 Responses 流式响应。")
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                    readDeepSeekEvents(input, request) { send(it) }
                }
                close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val detail = failure.message?.takeIf(String::isNotBlank) ?: "DeepSeek 连接中断。"
                close(IOException(redact(detail, prepared.headers)))
            }
        }
        // Cancellation has to close the socket from outside the coroutine blocked on read.
        awaitClose {
            reader.cancel()
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}

private fun HttpURLConnection.httpError(status: Int): String {
    val prefix = "DeepSeek HTTP $status"
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
        JSONObject(body).optJSONObject("error")?.opt("message") as? String
    } catch (_: JSONException) {
        null
    }
    return if (detail.isNullOrBlank()) prefix else "$prefix · $detail"
}

private fun redact(detail: String, headers: Map<String, String>): String {
    var safe = detail
    for ((name, value) in headers) {
        if (value.isBlank() || name.equals("Content-Type", true) || name.equals("Accept", true)) continue
        safe = safe.replace(value, "[redacted]")
        if (name.equals("Authorization", true)) {
            val credential = value.substringAfter(' ').trim()
            if (credential.isNotEmpty()) safe = safe.replace(credential, "[redacted]")
        }
    }
    return safe.take(800)
}
