package io.hydrion.hub.provider

import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Mode
import org.json.JSONObject

internal fun ModelCatalog.chatCompletionsRequest(
    provider: Provider,
    model: ProviderModel,
    history: List<Message>,
    apiKey: String,
    selected: Map<String, String> = parameters(model, provider),
    systemPrompt: String = "",
    attachmentData: (Attachment) -> String = { it.uri.orEmpty() }
): LocalModelRequest {
    val protocol = ChatProtocol.ChatCompletionsHttp
    require(provider.chatProtocol() == protocol) { "请选择 Chat Completions 协议。" }
    val url = provider.chatEndpoint()
    require(model.typeFor(provider.mode) == ModelType.Chat) { "请选择聊天模型。" }
    require((model.profile.budget ?: 1) > 0) { "当前模型的请求预算不足。" }
    val options = model.requestParameters(selected, Mode.Chat)
    val deepSeek = provider.usesDeepSeekDialect()
    val headers = chatHeaders(model.profile.headers, apiKey, if (deepSeek) "DeepSeek" else "Chat Completions")
    val body = model.chatBaseBody(deepSeek)
    require(body.isNull("web_search_options") && body.isNull("functions") && body.isNull("function_call")) {
        "当前接入仅支持对话与推理，请关闭模型的工具配置。"
    }
    require(body.isNull("n") || body.opt("n") == 1) { "当前仅支持一次生成一个回复。" }
    val modalities = body.optJSONArray("modalities")
    require(body.isNull("audio") && (body.isNull("modalities") ||
        (modalities?.length() == 1 && modalities.optString(0) == "text"))) { "聊天回复仅支持文本输出。" }
    body.remove("input")
    body.remove("tools")
    body.remove("tool_choice")
    body.remove("background")
    val effort = reasoningEffort(options, deepSeek)
    if (deepSeek && effort != null) {
        val enabled = effort != "none"
        body.put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled"))
        if (enabled) body.put("reasoning_effort", effort) else body.remove("reasoning_effort")
    } else if (effort != null) {
        body.put("reasoning_effort", effort)
    }
    body.put("model", model.externalModelId).put("stream", true)
    val streamOptions = body.optJSONObject("stream_options") ?: JSONObject()
    body.put("stream_options", streamOptions.put("include_usage", true))
    body.put("messages", chatTranscript(history.responsesHistory(), model, provider, protocol, attachmentData, systemPrompt))
    return LocalModelRequest(model.name, headers, body, options, url)
}
