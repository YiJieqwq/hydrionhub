package io.hydrion.hub.ui.composer

import io.hydrion.hub.model.ASK_USER
import io.hydrion.hub.model.ResponsesTransport
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/** A byte-level HTTP fixture behind both production SSE readers. Both peers deliberately reuse the same call id. */
internal class QuestionPeer {
    data class Exchange(val host: String, val prompt: String, val body: JSONObject, val answer: JSONObject?)
    val sent = CopyOnWriteArrayList<Exchange>()
    val transport = ResponsesTransport { url ->
        check(url.host in setOf("questions-a.invalid", "questions-b.invalid"))
        object : HttpURLConnection(url) {
            val bytes = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = bytes
            override fun getResponseCode() = 200
            override fun getContentType() = "text/event-stream"
            override fun getInputStream() = response(url.host, JSONObject(bytes.toString("UTF-8"))).byteInputStream()
        }
    }

    private fun response(host: String, body: JSONObject): String {
        val completions = body.has("messages")
        val data = body.getJSONArray(if (completions) "messages" else "input")
        val items = List(data.length()) { data.getJSONObject(it) }
        val prompt = items.last { it.optString("role") == "user" }.getString("content")
        val returned = items.last().takeIf { it.optString("role") == "tool" || it.optString("type") == "function_call_output" }
        val answer = returned?.let { JSONObject(it.getString(if (completions) "content" else "output")) }
        sent += Exchange(host, prompt, body, answer)
        val q = JSONObject().put("id", "pick").put("question", "Choose for $prompt")
            .put("options", JSONArray(listOf("Blue", "Green")))
        val arguments = JSONObject().put("questions", JSONArray().put(q)).toString()
        val text = "Finished $prompt"
        fun frame(value: JSONObject) = "data: " + value + "\n\n"
        if (completions) {
            fun delta(chunk: JSONObject, reason: String? = null) = frame(JSONObject().put("choices", JSONArray().put(
                JSONObject().put("index", 0).put("delta", chunk).put("finish_reason", reason ?: JSONObject.NULL))))
            if (answer != null) return delta(JSONObject().put("content", text), "stop") + "data: [DONE]\n\n"
            fun call(function: JSONObject, start: Boolean) = JSONObject().put("index", 0).put("function", function).apply {
                if (start) put("id", "shared-call").put("type", "function")
            }
            val head = call(JSONObject().put("name", ASK_USER).put("arguments", arguments.take(8)), true)
            val tail = call(JSONObject().put("arguments", arguments.drop(8)), false)
            return delta(JSONObject().put("tool_calls", JSONArray().put(head))) +
                delta(JSONObject().put("tool_calls", JSONArray().put(tail)), "tool_calls") + "data: [DONE]\n\n"
        }
        val item = if (answer == null) JSONObject().put("type", "function_call").put("id", "function-item")
            .put("name", ASK_USER).put("call_id", "shared-call").put("arguments", arguments)
        else JSONObject().put("type", "message").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
        return frame(JSONObject().put("type", "response.completed").put("response",
            JSONObject().put("status", "completed").put("output", JSONArray().put(item))))
    }
}
