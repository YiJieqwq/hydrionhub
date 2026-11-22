package io.hydrion.hub.provider

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONTokener

internal fun Provider.supportsModelDiscovery(): Boolean =
    id != DEEPSEEK_PROVIDER_ID && chatProtocol() == ChatProtocol.ChatCompletionsHttp

internal class ModelDiscovery(builder: OkHttpClient.Builder = OkHttpClient.Builder()) {
    private val client = builder.followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun fetch(provider: Provider, apiKey: String): List<String> {
        currentCoroutineContext().ensureActive()
        try {
            require(provider.supportsModelDiscovery())
            val endpoint = provider.chatEndpoint().removeSuffix("/chat/completions") + "/models"
            val request = Request.Builder().url(endpoint).header("Accept", "application/json")
            val key = apiKey.trim()
            require(apiKey.none { it == '\r' || it == '\n' } && key.all { it.code in 33..126 })
            if (key.isNotEmpty()) request.header("Authorization", "Bearer $key")
            val call = client.newCall(request.build())
            return suspendCancellableCoroutine { pending ->
                pending.invokeOnCancellation { call.cancel() }
                if (!pending.isActive) return@suspendCancellableCoroutine
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = pending.resumeWithException(e)

                    override fun onResponse(call: Call, response: Response) {
                        val ids = try {
                            response.use {
                                if (!pending.isActive) return
                                readIds(it)
                            }
                        } catch (e: Exception) {
                            pending.resumeWithException(e)
                            return
                        }
                        pending.resume(ids)
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IOException("Model discovery failed")
        }
    }

    private fun readIds(response: Response): List<String> {
        require(response.isSuccessful)
        val body = requireNotNull(response.body)
        val source = body.source()
        require(body.contentLength() <= MaxBytes && !source.request(MaxBytes + 1))
        val raw = source.readByteArray().decodeToString(throwOnInvalidSequence = true)
        validateJsonDepth(raw)
        val json = JSONTokener(raw)
        val data = (json.nextValue() as? JSONObject)?.optJSONArray("data")
        require(data != null && data.length() <= MaxRows && json.nextClean() == '\u0000')
        val ids = linkedSetOf<String>()
        for (index in 0 until data.length()) {
            val id = data.optJSONObject(index)?.opt("id") as? String
            require(id != null && id.length in 1..MaxIdLength && id == id.trim() && id.none(Char::isISOControl))
            ids.add(id)
        }
        return ids.toList()
    }

    // Android's JSONTokener recursively descends into unknown metadata as well as the model list.
    private fun validateJsonDepth(raw: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in raw) {
            if (escaped) escaped = false
            else if (quoted && char == '\\') escaped = true
            else if (char == '"') quoted = !quoted
            else if (!quoted) when (char) {
                '{', '[' -> require(++depth <= 32)
                '}', ']' -> require(--depth >= 0)
                else -> require(char in " \t\r\n,:-.+0123456789eEtrufalsn")
            }
        }
        require(!quoted && depth == 0)
    }

    private companion object {
        const val MaxBytes = 1024L * 1024
        const val MaxRows = 2048
        const val MaxIdLength = 512
    }
}
