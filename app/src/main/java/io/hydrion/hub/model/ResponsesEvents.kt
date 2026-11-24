package io.hydrion.hub.model

import java.io.BufferedReader
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal suspend fun readResponsesEvents(
    reader: BufferedReader,
    request: Message,
    emit: suspend (Message) -> Unit
) {
    val turn = ResponsesEventAccumulator(request)
    emit(turn.message)
    for ((eventName, data) in reader.sseEvents()) {
        if (data.trim() == "[DONE]") break
        if (turn.accept(data, eventName).deliver(emit)) return
    }
    throw IOException("连接在收到 Responses 结束事件前中断。")
}

internal data class ResponsesUpdate(val snapshot: Message? = null, val failure: IOException? = null) {
    suspend fun deliver(emit: suspend (Message) -> Unit): Boolean {
        snapshot?.let { emit(it) }
        failure?.let { throw it }
        return snapshot?.status == Generation.Completed
    }
}

/** Each collected turn owns its own reducer, regardless of the framing transport. */
internal class ResponsesEventAccumulator(request: Message) {
    var message = request.copy(status = Generation.Generating, tool = null, totalTokens = null, usage = null)
        private set
    private val parts = ResponseParts()

    fun accept(data: String, eventName: String): ResponsesUpdate {
        try {
            val event = JSONObject(data)
            val type = (event.opt("type") as? String).orEmpty().ifEmpty { eventName }
            if (type.isBlank()) throw IOException("Responses 流式事件缺少类型。")
            val before = message
            when (type) {
                "response.completed", "response.failed", "response.incomplete" -> {
                    val response = event.getJSONObject("response")
                    val declared = response.opt("status") as? String
                    val status = if (type == "response.completed" && declared != null) declared else type.substringAfter('.')
                    val failure = when (status) {
                        "completed" -> null
                        "incomplete" -> responseProblem("响应未完成", response.optJSONObject("incomplete_details"), "reason")
                        "failed" -> responseProblem("响应失败", response.optJSONObject("error"), "message")
                        else -> "响应未完成（$status）。"
                    }
                    response.optJSONArray("output")?.let { parts.reconcile(it, preservePartial = failure != null) }
                    val usage = response.optJSONObject("usage")?.tokenUsage()
                    message = parts.applyTo(message).copy(usage = usage, totalTokens = usage?.contextTokens)
                    if (failure == null) {
                        return ResponsesUpdate(message.copy(status = Generation.Completed))
                    }
                    return ResponsesUpdate(message.takeIf { it != before }, IOException(failure))
                }
                "error" -> return ResponsesUpdate(failure = IOException(
                    responseProblem("响应失败", event.optJSONObject("error") ?: event, "message")
                ))
                else -> if (parts.update(type, event)) {
                    message = parts.applyTo(message)
                    if (message != before) return ResponsesUpdate(message)
                }
            }
        } catch (_: JSONException) {
            throw IOException("服务端返回了无效的 Responses 流式事件。")
        }
        return ResponsesUpdate()
    }
}

private fun responseProblem(label: String, fields: JSONObject?, key: String): String {
    val detail = fields?.opt(key) as? String ?: fields?.opt("code") as? String
    return if (detail.isNullOrBlank()) "$label。" else "$label · $detail"
}

internal fun BufferedReader.sseEvents(): Sequence<Pair<String, String>> = sequence {
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

private fun JSONObject.tokenUsage(): TokenUsage? {
    fun JSONObject.count(key: String) = opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }
    return TokenUsage(
        inputTokens = count("input_tokens"), outputTokens = count("output_tokens"), totalTokens = count("total_tokens"),
        cachedInputTokens = optJSONObject("input_tokens_details")?.count("cached_tokens"),
        reasoningTokens = optJSONObject("output_tokens_details")?.count("reasoning_tokens")
    ).takeUnless { it == TokenUsage() }
}

/** Full .done parts and output snapshots replace deltas at the same indices. */
private class ResponseParts {
    private class Item {
        var tool: ReplyToolCall? = null
        val answer = sortedMapOf<Int, StringBuilder>()
        val reasoning = sortedMapOf<Int, StringBuilder>()
        val summary = sortedMapOf<Int, StringBuilder>()

        fun thought(): String {
            val publicSummary = summary.values.filter { it.isNotEmpty() }.joinToString("\n\n")
            return publicSummary.ifEmpty { reasoning.values.joinToString("") }
        }
    }

    private val items = sortedMapOf<Int, Item>()
    private val indicesById = mutableMapOf<String, Int>()

    fun applyTo(message: Message) = message.copy(
        toolCalls = buildList {
            var bodyOffset = 0
            for (item in items.values) {
                item.tool?.let { add(it.copy(bodyOffset = bodyOffset)) }
                bodyOffset += item.answer.values.sumOf { it.length }
            }
        },
        text = items.values.joinToString("") { it.answer.values.joinToString("") },
        thought = items.values.map { it.thought() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    )

    fun update(type: String, event: JSONObject): Boolean {
        val itemId = event.opt("item_id") as? String ?: event.optJSONObject("item")?.opt("id") as? String
        val index = if (event.has("output_index")) event.getInt("output_index") else indicesById[itemId] ?: 0
        (event.opt("item_id") as? String)?.let { indicesById[it] = index }
        val item = items.getOrPut(index) { Item() }
        val part = event.optInt("content_index")
        val summary = event.optInt("summary_index")
        when (type) {
            "response.output_text.delta", "response.refusal.delta" -> item.answer.append(part, event.getString("delta"))
            "response.output_text.done" -> item.answer.replace(part, event.getString("text"))
            "response.refusal.done" -> item.answer.replace(part, event.getString("refusal"))
            "response.reasoning_text.delta" -> item.reasoning.append(part, event.getString("delta"))
            "response.reasoning_text.done" -> item.reasoning.replace(part, event.getString("text"))
            "response.reasoning_summary_text.delta" -> item.summary.append(summary, event.getString("delta"))
            "response.reasoning_summary_text.done" -> item.summary.replace(summary, event.getString("text"))
            "response.reasoning_summary_part.added", "response.reasoning_summary_part.done" ->
                item.content(summary, event.getJSONObject("part"))
            "response.function_call_arguments.delta", "response.function_call_arguments.done" -> {
                val name = (event.opt("name") as? String)?.take(128) ?: item.tool?.name.orEmpty()
                item.tool = ReplyToolCall(name, ToolCallState.Requested)
            }
            "response.content_part.added", "response.content_part.done" -> item.content(part, event.getJSONObject("part"))
            "response.output_item.added", "response.output_item.done" -> output(index, event.getJSONObject("item"))
            else -> {
                val kind = type.removePrefix("response.").substringBefore('.')
                if (kind !in hostedToolTypes) return false
                val state = toolState(type.substringAfterLast('.')) ?: return false
                item.tool = ReplyToolCall(item.tool?.name ?: kind, state)
            }
        }
        return true
    }

    fun reconcile(outputs: JSONArray, preservePartial: Boolean) {
        for (i in 0 until outputs.length()) {
            val item = outputs.getJSONObject(i)
            val index = indicesById[item.optString("id")] ?: i
            output(index, item, preservePartial)
        }
    }

    private fun output(index: Int, json: JSONObject, preservePartial: Boolean = false) {
        (json.opt("id") as? String)?.let { indicesById[it] = index }
        val item = items.getOrPut(index) { Item() }
        val kind = json.optString("type")
        if (kind in clientToolTypes) {
            // Finalized arguments are a model request, not evidence that a client executed it.
            item.tool = ReplyToolCall((json.opt("name") as? String ?: item.tool?.name ?: kind).take(128), ToolCallState.Requested)
        } else if (kind in hostedToolTypes) {
            val state = toolState(json.optString("status")) ?: item.tool?.state ?: ToolCallState.Requested
            item.tool = ReplyToolCall((json.opt("name") as? String ?: item.tool?.name ?: kind).take(128), state)
        }
        json.optJSONArray("content")?.let { parts ->
            for (i in 0 until parts.length()) item.content(i, parts.getJSONObject(i), preservePartial)
        }
        json.optJSONArray("summary")?.let { parts ->
            for (i in 0 until parts.length()) item.content(i, parts.getJSONObject(i), preservePartial)
        }
    }

    private fun Item.content(index: Int, part: JSONObject, preservePartial: Boolean = false) {
        when (part.optString("type")) {
            "output_text" -> answer.replace(index, part.getString("text"), preservePartial)
            "refusal" -> answer.replace(index, part.getString("refusal"), preservePartial)
            "reasoning_text" -> reasoning.replace(index, part.getString("text"), preservePartial)
            "summary_text" -> summary.replace(index, part.getString("text"), preservePartial)
        }
    }

    private fun MutableMap<Int, StringBuilder>.append(index: Int, delta: String) {
        getOrPut(index) { StringBuilder() }.append(delta)
    }

    private fun MutableMap<Int, StringBuilder>.replace(index: Int, full: String, preservePartial: Boolean = false) {
        val current = this[index]
        if (preservePartial && current != null && current.startsWith(full)) return
        this[index] = StringBuilder(full)
    }
}

// Only project provider-reported tool lifecycle. Arguments, returned documents and commands stay out of history.
private val clientToolTypes = setOf("function_call", "custom_tool_call", "computer_call", "local_shell_call")
private val hostedToolTypes = setOf("web_search_call", "file_search_call", "code_interpreter_call", "image_generation_call",
    "mcp_call")

private fun toolState(status: String): ToolCallState? = when (status) {
    "in_progress", "searching", "interpreting", "generating", "calling" -> ToolCallState.Running
    "completed" -> ToolCallState.Completed
    "failed" -> ToolCallState.Failed
    "incomplete" -> ToolCallState.Incomplete
    else -> null
}
