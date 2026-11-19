package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import java.net.URI

internal enum class ChatProtocol(val label: String) {
    ResponsesHttp("OpenAI Responses"),
    ChatCompletionsHttp("OpenAI Chat Completions"),
    ResponsesWebSocket("OpenAI Responses WebSocket")
}

internal fun Provider.chatProtocol(): ChatProtocol? {
    if (mode != Mode.Chat || id == "demo-chat") return null
    if (id == DEEPSEEK_PROVIDER_ID && (protocol.isBlank() || protocol.trim().equals("OpenAI Compatible", true))) {
        return ChatProtocol.ResponsesHttp
    }
    return ChatProtocol.entries.find { it.label.equals(protocol.trim(), true) }
}

internal fun Provider.usesDeepSeekDialect(): Boolean = id == DEEPSEEK_PROVIDER_ID ||
    runCatching { URI(baseUrl.trim()).host?.trimEnd('.') }.getOrNull().equals("api.deepseek.com", true)

internal fun Provider.chatProtocolIssue(): String? {
    if (mode != Mode.Chat) return null
    if (id == "demo-chat") {
        return if (ChatProtocol.entries.any { it.label.equals(protocol.trim(), true) }) {
            "本机供应商用于离线模拟，请单独添加在线聊天供应商。"
        } else null
    }
    if (chatProtocol() != null) return runCatching { chatEndpoint() }.exceptionOrNull()?.message
    if (baseUrl.isBlank() && id != DEEPSEEK_PROVIDER_ID) return null
    val legacyLocal = protocol.trim().equals("OpenAI Compatible", true) || protocol.trim().equals("local", true)
    val host = runCatching { URI(baseUrl.trim()).host?.trimEnd('.') }.getOrNull()
    return when {
        !legacyLocal -> "无法识别聊天协议，请在供应商设置中选择支持的 API 协议。"
        host.equals("api.openai.com", true) -> "请在供应商设置中选择在线聊天协议，再连接 OpenAI。"
        else -> null
    }
}

private const val officialWebSocket = "wss://api.openai.com/v1/responses"

internal fun Provider.chatEndpoint(): String {
    val route = requireNotNull(chatProtocol()) { "请选择在线聊天协议。" }
    val deepSeek = id == DEEPSEEK_PROVIDER_ID
    val base = baseUrl.trim().ifEmpty { if (deepSeek) DEEPSEEK_BASE_URL else "" }
    require(base.isNotEmpty()) { "请填写该供应商的 Base URL。" }
    val uri = runCatching { URI(base) }.getOrNull()
    if (route == ChatProtocol.ResponsesWebSocket) {
        val officialAuthority = uri?.rawAuthority.equals("api.openai.com", true) ||
            uri?.rawAuthority.equals("api.openai.com:443", true)
        val allowedPath = when {
            uri?.scheme.equals("https", true) -> uri?.rawPath in setOf("", "/", "/v1", "/v1/", "/v1/responses")
            uri?.scheme.equals("wss", true) -> uri?.rawPath == "/v1/responses"
            else -> false
        }
        require(!deepSeek && officialAuthority && allowedPath && uri?.rawQuery == null && uri?.rawFragment == null) {
            "Responses WebSocket 仅支持 OpenAI 官方服务，请使用 https://api.openai.com/v1 或 $officialWebSocket。"
        }
        return officialWebSocket
    }
    require(uri != null && uri.scheme.equals("https", true) && uri.host != null &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535) && uri.path.split('/').none { it == "." || it == ".." }) {
        "服务地址须是有效的 HTTPS 地址，且不含凭据、查询参数或片段。"
    }
    val host = uri.host.trimEnd('.')
    require(!deepSeek || !host.equals("api.openai.com", true)) {
        "请为 OpenAI 官方服务单独配置供应商和 API Key。"
    }
    val path = uri.rawPath.orEmpty().trimEnd('/').removeSuffix("/responses").removeSuffix("/chat/completions").trimEnd('/')
    val prefix = when {
        host.equals("api.deepseek.com", true) && path == "/v1" -> ""
        host.equals("api.openai.com", true) && path.isEmpty() -> "/v1"
        else -> path
    }
    val suffix = if (route == ChatProtocol.ChatCompletionsHttp) "/chat/completions" else "/responses"
    return "https://${uri.rawAuthority}$prefix$suffix"
}

internal fun Provider.deepSeekEndpoint(): String {
    require(id == DEEPSEEK_PROVIDER_ID) { "请选择 DeepSeek 聊天模型供应商。" }
    return chatEndpoint()
}
