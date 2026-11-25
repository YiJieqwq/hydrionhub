package io.hydrion.hub.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal interface GenerationRepository {
    /** Visible context through the question, or through the edited answer when continuing an assistant turn. */
    fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String = ""): Flow<Message>

    fun chatWithQuestions(request: Message, retry: Boolean, history: List<Message>, systemPrompt: String, turn: AskUserTurn): Flow<Message> =
        generate(Mode.Chat, request, retry, history, systemPrompt)
}

internal class FakeGenerationRepository(
    private val wait: suspend (Long) -> Unit = { delay(it) }
) : GenerationRepository {
    override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String): Flow<Message> = flow {
        wait(350)
        var reply = request.copy(status = Generation.Generating, thought = if (mode == Mode.Chat && request.parameters["reasoning_enabled"] != "false")
            "已读取当前消息与附件，正在整理本地示例回复。" else "")
        emit(reply)
        wait(320)
        if (!retry && request.prompt.trim().equals("/error", ignoreCase = true)) {
            emit(reply.copy(status = Generation.Error, error = "模拟生成失败，可以重新生成。"))
            return@flow
        }

        val makesImage = mode == Mode.Image || request.tool?.contains("图片生成") == true
        if (makesImage) {
            wait(1150)
            val count = request.parameters["n"]?.toIntOrNull()?.coerceIn(1, 4) ?: 1
            val seed = newId()
            emit(reply.copy(status = Generation.Completed, imageKeys = List(count) { "$seed-$it" },
                text = if (mode == Mode.Image) "" else "已按提示词生成本地示例图。"))
            return@flow
        }

        val topic = request.prompt.ifBlank { request.attachments.joinToString { it.name } }
            .replace('\n', ' ').take(90)
        val answer = when {
            request.prompt.contains("代码") || request.prompt.contains("code", ignoreCase = true) ||
                request.prompt.contains("Kotlin", ignoreCase = true) ->
                "一个简短的 **Kotlin 示例**：\n\n```kotlin\nfun greet(name: String) = \"你好，\$name\"\n```\n\n调用 `greet(\"HydrionHub\")` 即可得到问候语。"
            request.attachments.isNotEmpty() ->
                "已收到 **${request.attachments.size} 个附件**。\n\n" +
                    request.attachments.joinToString("\n") { "- ${it.name}" } +
                    "\n\n这条本地示例回复展示附件清单。"
            request.tool != null ->
                "关于「$topic」，这里是**模拟搜索记录**。\n\n- 已匹配关键词\n- 已整理示例摘要\n\n本次演示使用本机数据。"
            else -> "收到「$topic」。\n\n**本地模拟回复**\n\n这条消息正在逐段生成。你可以展开思考过程，或在回复下方复制和重新生成。"
        }
        val buffer = StringBuilder()
        for (chunk in answer.chunked(6)) {
            wait(if (request.parameters["speed"] == "Fast") 25 else 60)
            buffer.append(chunk)
            reply = reply.copy(text = buffer.toString())
            emit(reply)
        }
        emit(reply.copy(status = Generation.Completed))
    }
}

internal fun selectedTool(mode: Mode, draft: Draft, allowSearch: Boolean = true): String? {
    if (mode != Mode.Chat) return null
    val prompt = draft.text.lowercase()
    return when {
        draft.imageTool != "禁用" && listOf("生成图", "画一", "画幅", "draw ", "generate an image").any { it in prompt } ->
            "图片生成 · ${draft.imageTool}"
        allowSearch && draft.search != "禁用" && listOf("搜索", "查找", "search", "查询").any { it in prompt } ->
            "网络搜索 · ${draft.search}"
        else -> null
    }
}
