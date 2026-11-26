package io.hydrion.hub.model

import io.hydrion.hub.R
import java.io.BufferedReader
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

internal suspend fun readChatCompletionsEvents(
    reader: BufferedReader,
    request: Message,
    privacy: ToolRecordText = ToolRecordText(),
    questions: AskWireRound? = null,
    emit: suspend (Message) -> Unit
) {
    var message = request.copy(status = Generation.Generating, tool = null, usage = null, totalTokens = null)
    emit(message)
    var finish: String? = null
    var pendingTool: String? = null
    val callsByIndex = sortedMapOf<Int, ReplyToolCall>()
    val arguments = mutableMapOf<Int, ToolArgumentFragments>()
    val names = mutableMapOf<Int, String>()
    val indicesById = mutableMapOf<String, Int>()
    var nextThoughtSlot = 0
    fun recordCall(index: Int, function: JSONObject?) {
        val current = callsByIndex[index] ?: ReplyToolCall("", ToolCallState.Requested, message.text.length, slot = index)
        function?.optionalText("name")?.let { names[index] = (names[index].orEmpty() + it.take(129)).take(129) }
        function?.optionalText("arguments")?.let { arguments.getOrPut(index) { ToolArgumentFragments() }.append(it) }
        // A streamed name or argument can split a credential across frames. Publish it only at finish.
        callsByIndex[index] = current
    }
    var conflictingTool = false
    var done = false
    for ((event, data) in reader.sseEvents()) {
        if (data.trim() == "[DONE]") {
            done = true
            break
        }
        try {
            val chunk = JSONObject(data)
            if (event == "error" || chunk.optString("type") == "error" || !chunk.isNull("error")) {
                val error = chunk.optJSONObject("error") ?: chunk
                val detail = error.opt("message") as? String ?: error.opt("code") as? String
                throw IOException(if (detail.isNullOrBlank()) "Chat Completions 响应失败。" else "Chat Completions 响应失败 · $detail")
            }
            val kind = chunk.optionalText("object")
            if (kind != null && kind != "chat.completion.chunk") throw JSONException("object")
            val before = message
            val choices = chunk.getJSONArray("choices")
            for (i in 0 until choices.length()) {
                val choice = choices.getJSONObject(i)
                if (choice.getInt("index") != 0) continue
                val reason = choice.optionalText("finish_reason")
                val delta = choice.optJSONObject("delta")
                if (delta == null && (reason == null || choice.has("message"))) throw JSONException("delta")
                val content = delta?.optionalText("content").orEmpty() + delta?.optionalText("refusal").orEmpty()
                val reasoning = delta?.optionalText("reasoning_content").orEmpty()
                if (finish != null && (content.isNotEmpty() || reasoning.isNotEmpty() || (reason != null && finish != reason))) {
                    throw IOException("Chat Completions 在结束原因之后返回了冲突的数据。")
                }
                var steps = message.replySteps
                if (reasoning.isNotEmpty()) {
                    val last = steps.lastOrNull() as? ReplyStep.Thought
                    val end = message.thought.length + reasoning.length
                    steps = if (last != null && last.bodyOffset == message.text.length)
                        steps.dropLast(1) + last.copy(end = end)
                    else steps + ReplyStep.Thought(nextThoughtSlot++, message.text.length, message.thought.length, end)
                }
                val newCalls = mutableListOf<Int>()
                delta?.optJSONArray("tool_calls")?.let { calls ->
                    for (j in 0 until calls.length()) {
                        val call = calls.getJSONObject(j)
                        val id = call.optionalText("id")
                        val index = if (call.has("index")) call.getInt("index") else indicesById[id] ?: j
                        if (index < 0) throw JSONException("tool_calls index")
                        if (id != null && indicesById.put(id, index)?.let { it != index } == true) conflictingTool = true
                        if (!call.isNull("type") && call.opt("type") != "function" || finish != null) conflictingTool = true
                        if (index !in callsByIndex) newCalls += index
                        recordCall(index, call.optJSONObject("function"))
                        pendingTool = "tool_calls"
                    }
                }
                delta?.optJSONObject("function_call")?.let {
                    conflictingTool = true
                    if (0 !in callsByIndex) newCalls += 0
                    recordCall(0, it)
                    pendingTool = "function_call"
                }
                if (reason != null && pendingTool != null) {
                    for ((index, call) in callsByIndex.toMap()) callsByIndex[index] = call.copy(
                        name = privacy.name(names[index].orEmpty()), arguments = arguments[index]?.finish(privacy))
                }
                if (pendingTool != null) message = message.copy(toolCalls = callsByIndex.values.toList())
                if (content.isNotEmpty() || reasoning.isNotEmpty() || newCalls.isNotEmpty()) {
                    if (newCalls.isNotEmpty()) {
                        // Parallel calls can start in different chunks. Respect their declared
                        // indices within this run, without moving them across reasoning or text.
                        val adjacent = steps.takeLastWhile { it is ReplyStep.Tool && callsByIndex[it.slot]?.bodyOffset == message.text.length }
                        val batch = adjacent + newCalls.map { ReplyStep.Tool(it) }
                        steps = steps.dropLast(adjacent.size) + batch.sortedBy { it.slot }
                    }
                    message = message.copy(text = message.text + content, thought = message.thought + reasoning, replySteps = steps)
                }
                if (reason != null) finish = reason
            }
            // OpenAI can send usage on its own after finish_reason. DeepSeek includes it on the finishing choice.
            chunk.optJSONObject("usage")?.let { objectUsage ->
                val usage = objectUsage.chatUsage(message.usage)
                message = message.copy(usage = usage, totalTokens = usage?.contextTokens)
            }
            if (message != before) emit(message)
        } catch (_: JSONException) {
            throw IOException("服务端返回了无效的 Chat Completions 流式事件。")
        }
    }
    val reason = if (finish == null || finish == "stop") pendingTool ?: finish else finish
    when (reason) {
        null -> throw IOException("Chat Completions 在收到结束原因前中断。")
        "stop" -> if (!done) throw IOException("Chat Completions 在收到 [DONE] 前中断。")
        "length" -> throw IOException("响应未完成（length）· 已达到输出长度上限。")
        "content_filter" -> throw IOException("响应未完成（content_filter）· 内容已被服务端过滤。")
        "tool_calls", "function_call" -> {
            if (ASK_USER !in names.values) throw IOException("响应未完成（$reason）· 此对话尚不支持执行模型请求的工具。")
            val wire = questions ?: throw AskUserFailure(R.string.ask_disabled)
            if (!done || finish != "tool_calls" || conflictingTool || names.values.any { it != ASK_USER } ||
                indicesById.size != callsByIndex.size) throw AskUserFailure(R.string.ask_invalid)
            val requested = callsByIndex.keys.map { slot ->
                val id = indicesById.entries.singleOrNull { it.value == slot }?.key ?: throw AskUserFailure(R.string.ask_invalid)
                if (names[slot] != ASK_USER) throw AskUserFailure(R.string.ask_invalid)
                WireQuestion(id, slot, arguments[slot]?.completeArguments() ?: throw AskUserFailure(R.string.ask_invalid))
            }
            wire.completions(requested, message)
        }
        "insufficient_system_resource" -> throw IOException("响应未完成（$reason）· 服务端资源不足。")
        "aborted" -> throw IOException("响应未完成（aborted）· 生成已被服务端中断。")
        else -> throw IOException("Chat Completions 返回未知结束原因（$reason）。")
    }
    emit(message.copy(status = Generation.Completed))
}

private fun JSONObject.optionalText(key: String): String? {
    if (isNull(key)) return null
    return opt(key) as? String ?: throw JSONException(key)
}

private fun JSONObject.chatUsage(previous: TokenUsage?): TokenUsage? {
    fun JSONObject.count(key: String) = opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
    return TokenUsage(
        inputTokens = count("prompt_tokens") ?: previous?.inputTokens,
        outputTokens = count("completion_tokens") ?: previous?.outputTokens,
        totalTokens = count("total_tokens") ?: previous?.totalTokens,
        cachedInputTokens = optJSONObject("prompt_tokens_details")?.count("cached_tokens")
            ?: count("prompt_cache_hit_tokens") ?: previous?.cachedInputTokens,
        reasoningTokens = optJSONObject("completion_tokens_details")?.count("reasoning_tokens") ?: previous?.reasoningTokens
    ).takeUnless { it == TokenUsage() }
}
