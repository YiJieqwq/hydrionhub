package io.hydrion.hub.provider

import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.Role
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal fun Provider.usesResponsesApi(): Boolean = mode == Mode.Chat && id != "demo-chat" &&
    (id == DEEPSEEK_PROVIDER_ID || protocol.trim().equals("OpenAI Responses", ignoreCase = true))

internal fun Provider.needsOpenAiResponsesProtocol(): Boolean {
    if (mode != Mode.Chat || id == "demo-chat" || usesResponsesApi()) return false
    val host = runCatching { URI(baseUrl.trim()).host?.trimEnd('.') }.getOrNull()
    return host.equals("api.openai.com", ignoreCase = true)
}

internal fun Provider.responsesEndpoint(): String {
    require(usesResponsesApi()) { "请选择使用 OpenAI Responses 协议的聊天模型供应商。" }
    val deepSeek = id == DEEPSEEK_PROVIDER_ID
    val base = baseUrl.trim().ifEmpty { if (deepSeek) DEEPSEEK_BASE_URL else "" }
    require(base.isNotEmpty()) { "请填写该 Responses 供应商的 HTTPS Base URL。" }
    val uri = runCatching { URI(base) }.getOrNull()
    require(uri != null && uri.scheme.equals("https", true) && uri.host != null &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535) && uri.path.split('/').none { it == "." || it == ".." }) {
        "服务地址须是有效的 HTTPS 地址，且不含凭据、查询参数或片段。"
    }
    val host = uri.host.trimEnd('.')
    require(!deepSeek || !host.equals("api.openai.com", true)) {
        "请为 OpenAI 官方服务单独配置 Responses 供应商和 API Key。"
    }
    val path = uri.rawPath.orEmpty().trimEnd('/').removeSuffix("/responses").removeSuffix("/chat/completions").trimEnd('/')
    val prefix = when {
        host.equals("api.deepseek.com", true) && path == "/v1" -> ""
        host.equals("api.openai.com", true) && path.isEmpty() -> "/v1"
        else -> path
    }
    return "https://${uri.rawAuthority}$prefix/responses"
}

internal fun Provider.deepSeekEndpoint(): String {
    require(id == DEEPSEEK_PROVIDER_ID) { "请选择 DeepSeek 聊天模型供应商。" }
    return responsesEndpoint()
}

/** The caller supplies the visible transcript through the user turn being answered, including on retry. */
internal fun ModelCatalog.responsesRequest(
    provider: Provider,
    model: ProviderModel,
    history: List<Message>,
    apiKey: String,
    selected: Map<String, String> = parameters(model),
    attachmentData: (Attachment) -> String = { it.uri.orEmpty() }
): LocalModelRequest {
    val url = provider.responsesEndpoint()
    require(model.typeFor(provider.mode) == ModelType.Chat) { "请选择聊天模型。" }
    require((model.profile.budget ?: 1) > 0) { "当前模型的请求预算不足。" }
    val deepSeek = provider.id == DEEPSEEK_PROVIDER_ID
    val headers = responsesHeaders(model.profile.headers, apiKey, if (deepSeek) "DeepSeek" else "Responses")
    val options = model.requestParameters(selected, Mode.Chat)
    val body = model.responsesBody(options, deepSeek)
    body.put("model", model.externalModelId).put("stream", true)
    body.put("input", responsesInput(history.responsesHistory(), model, provider, attachmentData))
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

private fun responsesInput(
    history: List<Message>, model: ProviderModel, provider: Provider, attachmentData: (Attachment) -> String
): JSONArray {
    val deepSeek = provider.id == DEEPSEEK_PROVIDER_ID
    val input = JSONArray()
    val lastReply = history.lastOrNull { it.role == Role.Assistant }
    var remaining = responsesImageBudget()
    var imageCount = 0
    for (message in history) {
        if (message.role == Role.Assistant) {
            // Assistant.attachments mirrors the user's input for editing and retry. Only imageKeys are generated images.
            require(message.imageKeys.isEmpty() && message.tool.isNullOrBlank()) {
                "历史消息含有工具结果或生成的图像，当前 Responses 接入无法回放。请新建对话。"
            }
            val replay = when (model.profile.reasoningReplay) {
                "none" -> false
                "last" -> message == lastReply
                else -> true
            }
            if (deepSeek && replay && message.remoteModel?.providerId == DEEPSEEK_PROVIDER_ID && message.thought.isNotBlank()) {
                val parts = JSONArray().put(JSONObject().put("type", "reasoning_text").put("text", message.thought))
                input.put(JSONObject().put("type", "reasoning").put("content", parts))
            }
            input.put(JSONObject().put("type", "message").put("role", "assistant").put("content", message.text))
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
            if (message.text.isNotBlank()) parts.put(JSONObject().put("type", "input_text").put("text", message.text))
            for (file in message.attachments) {
                val source = attachmentData(file)
                require(source.length <= remaining) { "图片总量过大，无法组装本轮请求。" }
                remaining -= source.length
                validateResponsesImage(file, source, model.profile.imageLinks)
                parts.put(JSONObject().put("type", "input_image").put("image_url", source).put("detail", "auto"))
            }
            parts
        }
        input.put(JSONObject().put("type", "message").put("role", "user").put("content", content))
    }
    return input
}

private fun ProviderModel.responsesBody(options: Map<String, String>, deepSeek: Boolean): JSONObject {
    val body = if (profile.requestBody.isBlank()) JSONObject() else {
        JSONObject(RequestTemplate.preview(profile.requestBody, null).getOrElse {
            throw IllegalArgumentException("模型的自定义请求体须为有效的 JSON 对象。")
        })
    }
    require(!profile.codeInterpreter && (!body.has("tools") || body.optJSONArray("tools")?.length() == 0) &&
        (!body.has("tool_choice") || body.optString("tool_choice") in setOf("auto", "none"))) {
        "当前 Responses 接入支持对话与推理，请关闭模型的工具配置。"
    }
    require(body.isNull("previous_response_id") && body.isNull("conversation")) {
        "当前使用本机会话历史，请移除自定义请求体中的服务端会话参数。"
    }
    require(body.isNull("background") || body.opt("background") == false) { "请关闭后台生成。" }
    if (!deepSeek) {
        require(body.isNull("prompt")) { "当前使用本机会话历史，请移除托管提示词。" }
        body.put("store", false)
    }
    val effort = when (val level = options["effort"]?.lowercase()) {
        "auto", null -> if (deepSeek && options["reasoning_enabled"] == "false") "none" else null
        "off", "none" -> "none"
        "minimal" -> if (deepSeek) "low" else "minimal"
        "medium" -> if (deepSeek) "high" else "medium"
        "extra high", "xhigh" -> if (deepSeek) "high" else "xhigh"
        "low", "high", "max" -> level
        else -> throw IllegalArgumentException("不支持当前推理档位。")
    }
    val reasoning = body.optJSONObject("reasoning") ?: JSONObject()
    if (effort != null) reasoning.put("effort", effort)
    if (!deepSeek && "reasoning" in profile.abilities && reasoning.optString("effort") != "none" &&
        !reasoning.has("summary")) reasoning.put("summary", "auto")
    if (reasoning.length() > 0) body.put("reasoning", reasoning)
    if (profile.sampling == "never/strip") {
        body.remove("temperature")
        body.remove("top_p")
    }
    return body
}

private val reservedHeaders = setOf("host", "content-length", "transfer-encoding", "connection", "upgrade", "proxy-authorization")

private fun responsesHeaders(custom: Map<String, String>, apiKey: String, service: String): Map<String, String> {
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
