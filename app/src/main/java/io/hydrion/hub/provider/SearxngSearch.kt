package io.hydrion.hub.provider

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONException
import org.json.JSONObject

internal data class SearchHit(val title: String, val url: String, val snippet: String)
internal enum class SearchProblem { InvalidRequest, Network, Http, InvalidResponse, TooLarge }
internal class SearchFailure(val problem: SearchProblem, val status: Int = 0) : IOException(problem.name)

internal class SearxngSearch(builder: OkHttpClient.Builder = OkHttpClient.Builder()) {
    private val client = builder.followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun search(endpoint: SearchEndpoint, query: String): List<SearchHit> {
        val term = query.trim()
        if (endpoint.adapter != SearchAdapter.Searxng || searchServiceAddress(endpoint.url) == null ||
            term.isEmpty() || term.length > MaxQuery || term.any(Char::isISOControl)) throw SearchFailure(SearchProblem.InvalidRequest)
        val base = endpoint.url.trimEnd('/').toHttpUrlOrNull() ?: throw SearchFailure(SearchProblem.InvalidRequest)
        val url = if (base.pathSegments.last() == "search") base else base.newBuilder().addPathSegment("search").build()
        val form = FormBody.Builder().add("q", term).add("format", "json").build()
        val body = object : RequestBody() {
            override fun contentType() = form.contentType()
            override fun contentLength() = form.contentLength()
            override fun writeTo(sink: BufferedSink) = form.writeTo(sink)
            override fun isOneShot() = true
        }
        val call = client.newCall(Request.Builder().url(url).post(body).header("Accept", "application/json").build())
        return suspendCancellableCoroutine { pending ->
            pending.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (pending.isActive) pending.resumeWithException(SearchFailure(SearchProblem.Network))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!pending.isActive) return
                        try {
                            pending.resume(readResults(it))
                        } catch (failure: SearchFailure) {
                            pending.resumeWithException(failure)
                        } catch (_: IOException) {
                            pending.resumeWithException(SearchFailure(SearchProblem.Network))
                        } catch (_: JSONException) {
                            pending.resumeWithException(SearchFailure(SearchProblem.InvalidResponse))
                        }
                    }
                }
            })
        }
    }

    private fun readResults(response: Response): List<SearchHit> {
        if (!response.isSuccessful) throw SearchFailure(SearchProblem.Http, response.code)
        val body = response.body ?: throw SearchFailure(SearchProblem.InvalidResponse)
        val type = body.contentType()?.subtype
        if (type != "json" && type?.endsWith("+json") != true) throw SearchFailure(SearchProblem.InvalidResponse)
        val source = body.source()
        if (body.contentLength() > MaxBytes || source.request(MaxBytes + 1)) throw SearchFailure(SearchProblem.TooLarge)
        val raw = source.readUtf8()
        checkJsonStructure(raw)
        val rows = JSONObject(raw).getJSONArray("results")
        val found = linkedMapOf<String, SearchHit>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val link = (row.opt("url") as? String)?.takeIf { it.length <= 2048 }?.toHttpUrlOrNull() ?: continue
            if (link.username.isNotEmpty() || link.password.isNotEmpty()) continue
            val title = (row.opt("title") as? String)?.trim()?.take(256).orEmpty()
            val snippet = (row.opt("content") as? String)?.trim()?.take(1200).orEmpty()
            val address = link.toString()
            found.putIfAbsent(address, SearchHit(title.ifBlank { link.host }, address, snippet))
            if (found.size == 10) break
        }
        return found.values.toList()
    }

    companion object {
        const val MaxQuery = 512
        private const val MaxBytes = 512L * 1024
    }
}

/** Android's JSONObject parser recurses and also accepts non-JSON quoting/comments. */
private fun checkJsonStructure(raw: String) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in raw) {
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > 32) throw SearchFailure(SearchProblem.InvalidResponse)
            '}', ']' -> if (--depth < 0) throw SearchFailure(SearchProblem.InvalidResponse)
            else -> if (char !in " \n\r\t,:-+.0123456789eEtrufalsn") throw SearchFailure(SearchProblem.InvalidResponse)
        }
    }
    if (quoted || depth != 0) throw SearchFailure(SearchProblem.InvalidResponse)
}
