package io.hydrion.hub.model

import java.io.BufferedReader
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

internal suspend fun readChatCompletionsEvents(
    reader: BufferedReader,
    request: Message,
    privacy: ToolRecordText = ToolRecordText(),
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
    fun recordCall(index: Int, function: JSONObject?) {
        val current = callsByIndex[index] ?: ReplyToolCall("", ToolCallState.Requested, message.text.length, slot = index)
        function?.optionalText("name")?.let { names[index] = (names[index].orEmpty() + it.take(129)).take(129) }
        function?.optionalText("arguments")?.let { arguments.getOrPut(index) { ToolArgumentFragments() }.append(it) }
        // A streamed name or argument can split a credential across frames. Publish it only at finish.
        callsByIndex[index] = current
    }
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
                delta?.optJSONArray("tool_calls")?.let { calls ->
                    for (j in 0 until calls.length()) {
                        val call = calls.getJSONObject(j)
                        val id = call.optionalText("id")
                        val index = if (call.has("index")) call.getInt("index") else indicesById[id] ?: j
                        if (index < 0) throw JSONException("tool_calls index")
                        if (id != null) indicesById[id] = index
                        recordCall(index, call.optJSONObject("function"))
                        pendingTool = "tool_calls"
                    }
                }
                delta?.optJSONObject("function_call")?.let {
                    recordCall(0, it)
                    pendingTool = "function_call"
                }
                if (reason != null && pendingTool != null) {
                    for ((index, call) in callsByIndex.toMap()) callsByIndex[index] = call.copy(
                        name = privacy.name(names[index].orEmpty()), arguments = arguments[index]?.finish(privacy))
                }
                if (pendingTool != null) message = message.copy(toolCalls = callsByIndex.values.toList())
                if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                    message = message.copy(text = message.text + content, thought = message.thought + reasoning)
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
        "tool_calls", "function_call" -> throw IOException("响应未完成（$reason）· 此对话尚不支持执行模型请求的工具。")
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
