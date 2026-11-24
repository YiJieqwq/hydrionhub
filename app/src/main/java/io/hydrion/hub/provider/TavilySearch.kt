package io.hydrion.hub.provider

import io.hydrion.hub.json.JsonSyntax
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal class TavilySearch(private val calls: Call.Factory = newClient()) {

    suspend fun search(query: String, sessionKey: String): List<SearchHit> {
        val term = query.trim()
        if (term.isEmpty() || term.length > SearxngSearch.MaxQuery || term.any(Char::isISOControl)) {
            throw SearchFailure(SearchProblem.InvalidRequest)
        }
        val key = tavilyToken(sessionKey)
        if (key.isEmpty()) throw SearchBlocked(SearchBlocked.Reason.MissingCredential)
        val payload = JSONObject().put("query", term).put("max_results", 10).put("search_depth", "basic")
            .toString().toByteArray(Charsets.UTF_8)
        val body = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = payload.size.toLong()
            override fun writeTo(sink: BufferedSink) { sink.write(payload) }
            override fun isOneShot() = true
        }
        val request = Request.Builder().url("https://api.tavily.com/search")
            .header("Authorization", "Bearer $key").header("Accept", "application/json").post(body).build()
        return suspendCancellableCoroutine { pending ->
            if (!pending.isActive) return@suspendCancellableCoroutine
            val call = calls.newCall(request)
            pending.invokeOnCancellation { call.cancel() }
            if (!pending.isActive) return@suspendCancellableCoroutine
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (pending.isActive) pending.resumeWithException(SearchFailure(SearchProblem.Network))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.body.use {
                        if (!pending.isActive) return
                        val hits = try {
                            readResults(response, key)
                        } catch (failure: SearchFailure) {
                            pending.resumeWithException(failure)
                            return
                        } catch (_: JSONException) {
                            pending.resumeWithException(SearchFailure(SearchProblem.InvalidResponse))
                            return
                        } catch (_: CharacterCodingException) {
                            pending.resumeWithException(SearchFailure(SearchProblem.InvalidResponse))
                            return
                        } catch (_: IOException) {
                            pending.resumeWithException(SearchFailure(SearchProblem.Network))
                            return
                        }
                        pending.resume(hits)
                    }
                }
            })
        }
    }

    private fun readResults(response: Response, key: String): List<SearchHit> {
        if (!response.isSuccessful) throw SearchFailure(SearchProblem.Http, response.code)
        val body = response.body ?: throw SearchFailure(SearchProblem.InvalidResponse)
        val type = body.contentType()?.subtype
        if (type != "json" && type?.endsWith("+json") != true) throw SearchFailure(SearchProblem.InvalidResponse)
        val source = body.source()
        if (body.contentLength() > MaxBytes || source.request(MaxBytes + 1)) throw SearchFailure(SearchProblem.TooLarge)
        val raw = source.readByteArray().decodeToString(throwOnInvalidSequence = true)
        JsonSyntax(raw) { throw SearchFailure(SearchProblem.InvalidResponse) }.check()
        val tokener = JSONTokener(raw)
        val rows = (tokener.nextValue() as? JSONObject)?.optJSONArray("results")
        if (rows == null || tokener.nextClean() != '\u0000') throw SearchFailure(SearchProblem.InvalidResponse)

        val hits = linkedMapOf<String, SearchHit>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val rawUrl = row.opt("url") as? String ?: continue
            if (rawUrl.length > 2048 || key in rawUrl) continue
            val url = rawUrl.toHttpUrlOrNull() ?: continue
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) continue
            val address = url.toString()
            val rawTitle = (row.opt("title") as? String).orEmpty()
            val rawContent = (row.opt("content") as? String).orEmpty()
            // A provider can echo its Authorization value even in a successful JSON payload.
            if (key in address || key in rawTitle || key in rawContent) continue
            val title = rawTitle.trim().take(256)
            val snippet = rawContent.trim().take(1200)
            hits.putIfAbsent(address, SearchHit(title.ifEmpty { url.host }, address, snippet))
            if (hits.size == 10) break
        }
        return hits.values.toList()
    }

    companion object {
        private const val MaxBytes = 512L * 1024

        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
