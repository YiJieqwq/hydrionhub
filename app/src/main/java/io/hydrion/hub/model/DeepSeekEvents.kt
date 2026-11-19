package io.hydrion.hub.model

import java.io.BufferedReader
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal suspend fun readDeepSeekEvents(
    reader: BufferedReader,
    request: Message,
    emit: suspend (Message) -> Unit
) {
    var message = request.copy(status = Generation.Generating, tool = null, totalTokens = null)
    emit(message)
    val parts = ResponseParts()
    for ((event, data) in reader.sseEvents()) {
        try {
            val json = JSONObject(data)
            val type = json.optString("type").ifEmpty { event }
            val before = message
            when (type) {
                "response.completed", "response.failed", "response.incomplete" -> {
                    val response = json.getJSONObject("response")
                    response.optJSONArray("output")?.let(parts::reconcile)
                    message = parts.applyTo(message).copy(totalTokens = response.optJSONObject("usage")?.tokenCount())
                    if (type == "response.completed") {
                        emit(message.copy(status = Generation.Completed))
                        return
                    }
                    if (message != before) emit(message)
                    val detail = response.optJSONObject("error")?.opt("message") as? String
                        ?: response.optJSONObject("incomplete_details")?.opt("reason") as? String
                    throw IOException(detail?.takeIf(String::isNotBlank) ?: "DeepSeek 未能完成回复。")
                }
                "error" -> {
                    val detail = json.optJSONObject("error")?.opt("message") as? String ?: json.opt("message") as? String
                    throw IOException(detail?.takeIf(String::isNotBlank) ?: "DeepSeek 生成失败。")
                }
                else -> if (parts.update(type, json)) {
                    message = parts.applyTo(message)
                    if (message != before) emit(message)
                }
            }
        } catch (_: JSONException) {
            throw IOException("DeepSeek 返回了无效的流式事件。")
        }
    }
    throw IOException("DeepSeek 连接在回复完成前中断。")
}

private fun BufferedReader.sseEvents(): Sequence<Pair<String, String>> = sequence {
    var event = ""
    var firstLine = true
    val data = mutableListOf<String>()
    while (true) {
        val raw = readLine() ?: break
        val line = if (firstLine) raw.removePrefix("\uFEFF") else raw
        firstLine = false
        when {
            line.startsWith("event:") -> event = line.substring(6).removePrefix(" ")
            line.startsWith("data:") -> data += line.substring(5).removePrefix(" ")
            line.isEmpty() -> {
                if (data.isNotEmpty()) yield(event to data.joinToString("\n"))
                event = ""
                data.clear()
            }
        }
    }
}

private fun JSONObject.tokenCount(): Long? {
    fun count(key: String) = opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }
    count("total_tokens")?.let { return it }
    val input = count("input_tokens") ?: return null
    val output = count("output_tokens") ?: return null
    return if (input <= Long.MAX_VALUE - output) input + output else null
}

private class ResponseParts {
    // The .done events repeat whole parts. Replace the matching part so final snapshots
    // can repair missing deltas without duplicating content already shown to the user.
    private val order = compareBy<Pair<Int, Int>>({ it.first }, { it.second })
    private val answer = sortedMapOf<Pair<Int, Int>, StringBuilder>(order)
    private val thought = sortedMapOf<Pair<Int, Int>, StringBuilder>(order)

    fun applyTo(message: Message) = message.copy(
        text = answer.values.joinToString(""), thought = thought.values.joinToString("")
    )

    fun update(type: String, event: JSONObject): Boolean {
        val index = event.optInt("output_index")
        val part = event.optInt("content_index")
        when (type) {
            "response.reasoning_text.delta", "response.output_text.delta" ->
                text(index, part, type == "response.reasoning_text.delta", event.getString("delta"), append = true)
            "response.reasoning_text.done", "response.output_text.done" ->
                text(index, part, type == "response.reasoning_text.done", event.getString("text"), append = false)
            "response.content_part.added", "response.content_part.done" -> content(index, part, event.getJSONObject("part"))
            "response.output_item.added", "response.output_item.done" -> item(index, event.getJSONObject("item"))
            else -> return false
        }
        return true
    }

    fun reconcile(items: JSONArray) {
        val snapshot = ResponseParts()
        for (i in 0 until items.length()) snapshot.item(i, items.getJSONObject(i))
        if (snapshot.answer.isNotEmpty()) {
            answer.clear()
            answer.putAll(snapshot.answer)
        }
        if (snapshot.thought.isNotEmpty()) {
            thought.clear()
            thought.putAll(snapshot.thought)
        }
    }

    private fun item(index: Int, item: JSONObject) {
        val parts = item.optJSONArray("content") ?: return
        for (i in 0 until parts.length()) content(index, i, parts.getJSONObject(i))
    }

    private fun content(item: Int, part: Int, json: JSONObject) {
        when (json.optString("type")) {
            "reasoning_text" -> text(item, part, true, json.getString("text"), append = false)
            "output_text" -> text(item, part, false, json.getString("text"), append = false)
        }
    }

    private fun text(item: Int, part: Int, isThought: Boolean, value: String, append: Boolean) {
        val parts = if (isThought) thought else answer
        val buffer = parts.getOrPut(item to part) { StringBuilder() }
        if (!append) buffer.setLength(0)
        buffer.append(value)
    }
}
