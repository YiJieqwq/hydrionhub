package io.hydrion.hub.provider

import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.Role
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal fun Provider.deepSeekEndpoint(): String {
    val base = baseUrl.trim().ifEmpty { DEEPSEEK_BASE_URL }
    val uri = runCatching { URI(base) }.getOrNull()
    require(uri != null && uri.scheme.equals("https", true) && uri.host != null &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535) && uri.path.split('/').none { it == "." || it == ".." }) {
        "DeepSeek 服务地址须是有效的 HTTPS 地址，且不含凭据、查询参数或片段。"
    }
    val path = uri.rawPath.orEmpty().trimEnd('/').removeSuffix("/responses").removeSuffix("/chat/completions").trimEnd('/')
    val prefix = if (uri.host.equals("api.deepseek.com", true) && path == "/v1") "" else path
    return "https://${uri.rawAuthority}$prefix/responses"
}

/** The caller supplies the visible transcript through the user turn being answered, including on retry. */
internal fun ModelCatalog.deepSeekRequest(
    provider: Provider,
    model: ProviderModel,
    history: List<Message>,
    apiKey: String,
    selected: Map<String, String> = parameters(model),
    attachmentData: (Attachment) -> String = { it.uri.orEmpty() }
): LocalModelRequest {
    require(provider.id == DEEPSEEK_PROVIDER_ID && provider.mode == Mode.Chat) { "请选择 DeepSeek 对话模型供应商。" }
    require((model.profile.budget ?: 1) > 0) { "当前模型的请求预算不足。" }
    val url = provider.deepSeekEndpoint()
    val headers = deepSeekHeaders(model.profile.headers, apiKey)
    val options = model.requestParameters(selected)
    val body = model.deepSeekBody(options)
    body.put("model", model.externalModelId).put("stream", true)
    body.put("input", deepSeekInput(history.deepSeekHistory(), model, attachmentData))
    return LocalModelRequest(model.name, headers, body, options, url)
}

/** Drop failed exchanges and empty placeholders. A stopped reply's visible text stays in the transcript. */
internal fun List<Message>.deepSeekHistory(): List<Message> {
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

private fun deepSeekInput(history: List<Message>, model: ProviderModel, attachmentData: (Attachment) -> String): JSONArray {
    val input = JSONArray()
    val lastReply = history.lastOrNull { it.role == Role.Assistant }
    var remaining = deepSeekImageBudget()
    var imageCount = 0
    for (message in history) {
        if (message.role == Role.Assistant) {
            // Assistant.attachments mirrors the user's input for editing and retry. Only imageKeys are generated images.
            require(message.imageKeys.isEmpty() && message.tool.isNullOrBlank()) {
                "历史消息含有工具结果或生成的图像，当前 DeepSeek 接入无法回放。请新建对话。"
            }
            val replay = when (model.profile.reasoningReplay) {
                "none" -> false
                "last" -> message == lastReply
                else -> true
            }
            if (replay && message.remoteModel?.providerId == DEEPSEEK_PROVIDER_ID && message.thought.isNotBlank()) {
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
            require(imageCount <= MAX_DEEPSEEK_IMAGES) { "每次 DeepSeek 请求最多可包含 600 张图片。" }
            require(model.externalModelId != "deepseek-v4-pro" && "image" in model.profile.input) {
                "当前模型仅支持文本输入。含图对话请选择 DeepSeek Flash。"
            }
            val parts = JSONArray()
            if (message.text.isNotBlank()) parts.put(JSONObject().put("type", "input_text").put("text", message.text))
            for (file in message.attachments) {
                val source = attachmentData(file)
                require(source.length <= remaining) { "图片总量过大，无法组装本轮请求。" }
                remaining -= source.length
                validateDeepSeekImage(file, source, model.profile.imageLinks)
                parts.put(JSONObject().put("type", "input_image").put("image_url", source).put("detail", "auto"))
            }
            parts
        }
        input.put(JSONObject().put("type", "message").put("role", "user").put("content", content))
    }
    return input
}

private fun ProviderModel.deepSeekBody(options: Map<String, String>): JSONObject {
    val body = if (profile.requestBody.isBlank()) JSONObject() else {
        JSONObject(RequestTemplate.preview(profile.requestBody, null).getOrElse {
            throw IllegalArgumentException("模型的自定义请求体须为有效的 JSON 对象。")
        })
    }
    require(!profile.codeInterpreter && (!body.has("tools") || body.optJSONArray("tools")?.length() == 0) &&
        (!body.has("tool_choice") || body.optString("tool_choice") in setOf("auto", "none"))) {
        "当前 DeepSeek 接入支持对话与推理，请关闭模型的工具配置。"
    }
    require(body.isNull("previous_response_id") && body.isNull("conversation")) {
        "DeepSeek 使用本机会话历史，请移除自定义请求体中的服务端会话参数。"
    }
    val effort = when (options["effort"]?.lowercase()) {
        "auto", null -> if (options["reasoning_enabled"] == "false") "none" else null
        "off", "none" -> "none"
        "minimal", "low" -> "low"
        "medium", "high", "extra high", "xhigh" -> "high"
        "max" -> "max"
        else -> throw IllegalArgumentException("DeepSeek 不支持当前推理档位。")
    }
    if (effort != null) {
        val reasoning = body.optJSONObject("reasoning") ?: JSONObject()
        body.put("reasoning", reasoning.put("effort", effort))
    }
    if (profile.sampling == "never/strip") {
        body.remove("temperature")
        body.remove("top_p")
    }
    return body
}

private val reservedHeaders = setOf("host", "content-length", "transfer-encoding", "connection", "upgrade", "proxy-authorization")

private fun deepSeekHeaders(custom: Map<String, String>, apiKey: String): Map<String, String> {
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
        require(apiKey.isNotBlank()) { "请在 DeepSeek 供应商设置中填写 API Key。" }
        require(apiKey.trim().all { it.code in 33..126 } && apiKey.none { it == '\r' || it == '\n' }) { "API Key 含有无效字符。" }
        headers["Authorization"] = "Bearer ${apiKey.trim()}"
    }
    return headers
}
