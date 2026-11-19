package io.hydrion.hub.provider

import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.Role
import org.json.JSONArray
import org.json.JSONObject

/** The caller supplies the visible transcript through the user turn being answered, including on retry. */
internal fun ModelCatalog.responsesRequest(
    provider: Provider,
    model: ProviderModel,
    history: List<Message>,
    apiKey: String,
    selected: Map<String, String> = parameters(model, provider),
    attachmentData: (Attachment) -> String = { it.uri.orEmpty() }
): LocalModelRequest {
    val protocol = requireNotNull(provider.chatProtocol()) { "请选择 Responses 协议。" }
    require(protocol == ChatProtocol.ResponsesHttp || protocol == ChatProtocol.ResponsesWebSocket) {
        "请选择 Responses 协议。"
    }
    val url = provider.chatEndpoint()
    require(model.typeFor(provider.mode) == ModelType.Chat) { "请选择聊天模型。" }
    require((model.profile.budget ?: 1) > 0) { "当前模型的请求预算不足。" }
    val deepSeek = provider.usesDeepSeekDialect()
    val headers = chatHeaders(model.profile.headers, apiKey, if (deepSeek) "DeepSeek" else "Responses")
    val options = model.requestParameters(selected, Mode.Chat)
    val body = model.responsesBody(options, deepSeek)
    body.put("model", model.externalModelId)
    if (protocol == ChatProtocol.ResponsesWebSocket) {
        body.remove("stream")
        body.remove("background")
    } else body.put("stream", true)
    body.put("input", chatTranscript(history.responsesHistory(), model, provider, protocol, attachmentData))
    return LocalModelRequest(model.name, headers, body, options, url)
}

/** Drop failed exchanges and empty placeholders. A stopped reply's visible text stays in the transcript. */
internal fun List<Message>.responsesHistory(): List<Message> {
    val current = lastOrNull()
    require(current?.role == Role.User) { "缺少本轮用户消息。" }
    val excluded = mutableSetOf<String>()
    forEachIndexed { index, message ->
        if (message.role == Role.Assistant) {
            val empty = message.text.isBlank() && message.imageKeys.isEmpty() && message.tool.isNullOrBlank()
            if (message.status == Generation.Error || empty) {
                excluded.add(message.id)
                val promptId = message.replyTo ?: getOrNull(index - 1)?.takeIf { it.role == Role.User }?.id
                promptId?.let(excluded::add)
            }
        }
    }
    return filter { it.id !in excluded || it == current }
}

internal fun chatTranscript(
    history: List<Message>, model: ProviderModel, provider: Provider, protocol: ChatProtocol,
    attachmentData: (Attachment) -> String
): JSONArray {
    val deepSeek = provider.usesDeepSeekDialect()
    val completions = protocol == ChatProtocol.ChatCompletionsHttp
    val input = JSONArray()
    fun addMessage(role: String, content: Any) {
        val item = JSONObject().put("role", role).put("content", content)
        if (!completions) item.put("type", "message")
        input.put(item)
    }
    val lastReply = history.lastOrNull { it.role == Role.Assistant }
    var remaining = responsesImageBudget()
    var imageCount = 0
    for (message in history) {
        if (message.role == Role.Assistant) {
            // Assistant.attachments mirrors the user's input for editing and retry. Only imageKeys are generated images.
            require(message.imageKeys.isEmpty() && message.tool.isNullOrBlank()) {
                "历史消息含有工具结果或生成的图像，当前聊天协议无法回放。请新建对话。"
            }
            val replay = when (model.profile.reasoningReplay) {
                "none" -> false
                "last" -> message == lastReply
                else -> true
            }
            if (!completions && provider.id == DEEPSEEK_PROVIDER_ID && replay &&
                message.remoteModel?.providerId == provider.id && message.thought.isNotBlank()) {
                val parts = JSONArray().put(JSONObject().put("type", "reasoning_text").put("text", message.thought))
                input.put(JSONObject().put("type", "reasoning").put("content", parts))
            }
            addMessage("assistant", message.text)
            continue
        }
        require(message.text.isNotBlank() || message.attachments.isNotEmpty()) { "请输入消息或添加图片。" }
        require(message.text.isBlank() || "text" in model.profile.input) { "当前模型没有启用文本输入。" }
        val content: Any = if (message.attachments.isEmpty()) message.text else {
            imageCount += message.attachments.size
            require(imageCount <= provider.responsesImageLimit) { "每次请求最多可包含 ${provider.responsesImageLimit} 张图片。" }
            require((!deepSeek || model.externalModelId != "deepseek-v4-pro") && "image" in model.profile.input) {
                "当前模型仅支持文本输入，请选择支持图像输入的聊天模型。"
            }
            val parts = JSONArray()
            if (message.text.isNotBlank()) {
                parts.put(JSONObject().put("type", if (completions) "text" else "input_text").put("text", message.text))
            }
            for (file in message.attachments) {
                val source = attachmentData(file)
                require(source.length <= remaining) { "图片总量过大，无法组装本轮请求。" }
                remaining -= source.length
                validateResponsesImage(file, source, model.profile.imageLinks)
                val part = if (completions) JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", source).put("detail", "auto"))
                else JSONObject().put("type", "input_image").put("image_url", source).put("detail", "auto")
                parts.put(part)
            }
            parts
        }
        addMessage("user", content)
    }
    return input
}

internal fun ProviderModel.chatBaseBody(deepSeek: Boolean): JSONObject {
    val body = if (profile.requestBody.isBlank()) JSONObject() else {
        JSONObject(RequestTemplate.preview(profile.requestBody, null).getOrElse {
            throw IllegalArgumentException("模型的自定义请求体须为有效的 JSON 对象。")
        })
    }
    require(!profile.codeInterpreter && (!body.has("tools") || body.optJSONArray("tools")?.length() == 0) &&
        (!body.has("tool_choice") || body.optString("tool_choice") in setOf("auto", "none"))) {
        "当前接入仅支持对话与推理，请关闭模型的工具配置。"
    }
    require(body.isNull("previous_response_id") && body.isNull("conversation")) {
        "当前使用本机会话历史，请移除自定义请求体中的服务端会话参数。"
    }
    require(body.isNull("background") || body.opt("background") == false) { "请关闭后台生成。" }
    require(body.isNull("prompt")) { "当前使用本机会话历史，请移除托管提示词。" }
    if (deepSeek) body.remove("store") else body.put("store", false)
    if (profile.sampling == "never/strip") {
        body.remove("temperature")
        body.remove("top_p")
    }
    return body
}

internal fun reasoningEffort(options: Map<String, String>, deepSeek: Boolean): String? =
    when (val level = options["effort"]?.lowercase()) {
        "auto", null -> if (deepSeek && options["reasoning_enabled"] == "false") "none" else null
        "off", "none" -> "none"
        "minimal" -> if (deepSeek) "low" else "minimal"
        "medium" -> if (deepSeek) "high" else "medium"
        "extra high", "xhigh" -> if (deepSeek) "high" else "xhigh"
        "low", "high", "max" -> level
        else -> throw IllegalArgumentException("不支持当前推理档位。")
    }

private fun ProviderModel.responsesBody(options: Map<String, String>, deepSeek: Boolean): JSONObject {
    val body = chatBaseBody(deepSeek)
    val effort = reasoningEffort(options, deepSeek)
    val reasoning = body.optJSONObject("reasoning") ?: JSONObject()
    if (effort != null) reasoning.put("effort", effort)
    if (!deepSeek && "reasoning" in profile.abilities && reasoning.optString("effort") != "none" &&
        !reasoning.has("summary")) reasoning.put("summary", "auto")
    if (reasoning.length() > 0) body.put("reasoning", reasoning)
    return body
}

private val reservedHeaders = setOf("host", "content-length", "transfer-encoding", "connection", "upgrade", "proxy-authorization")

internal fun chatHeaders(custom: Map<String, String>, apiKey: String, service: String): Map<String, String> {
    val headers = mutableMapOf("Content-Type" to "application/json", "Accept" to "text/event-stream")
    for ((name, value) in custom) {
        if (value.isEmpty()) continue // Persisted header names have no values after restarting the app.
        require(headerName.matches(name) && value.all { it.code in 32..126 || it == '\t' }) { "模型请求头含有无效字符。" }
        if (value.isBlank()) continue
        require(name.lowercase() !in reservedHeaders) { "此请求头由网络客户端管理。" }
        if (name.equals("Accept", true) || name.equals("Content-Type", true)) continue
        require(headers.keys.none { it.equals(name, true) }) { "模型请求头名称重复。" }
        headers[name] = value.trim()
    }
    if (headers.keys.none { it.equals("Authorization", true) }) {
        require(apiKey.isNotBlank()) { "请在 $service 供应商设置中填写 API Key。" }
        require(apiKey.trim().all { it.code in 33..126 } && apiKey.none { it == '\r' || it == '\n' }) { "API Key 含有无效字符。" }
        headers["Authorization"] = "Bearer ${apiKey.trim()}"
    }
    return headers
}
