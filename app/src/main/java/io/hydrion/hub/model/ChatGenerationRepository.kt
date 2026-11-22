package io.hydrion.hub.model

import io.hydrion.hub.provider.ChatProtocol
import io.hydrion.hub.provider.LocalModelRequest
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.externalModelId
import io.hydrion.hub.provider.chatCompletionsRequest
import io.hydrion.hub.provider.chatProtocolIssue
import io.hydrion.hub.provider.chatProtocol
import io.hydrion.hub.provider.responsesHistory
import io.hydrion.hub.provider.responsesRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal class ChatGenerationRepository(
    private val currentCatalog: () -> ModelCatalog,
    private val sessionKey: (Provider) -> String,
    private val attachmentSources: suspend (List<Attachment>, Provider) -> Map<Attachment, String>,
    private val stream: (ChatProtocol, LocalModelRequest, Message) -> Flow<Message> = ChatTransports::stream
) : GenerationRepository {
    private val local = FakeGenerationRepository()

    override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String): Flow<Message> {
        val route = request.remoteModel ?: return local.generate(mode, request, retry, history)
        return flow {
            require(mode == Mode.Chat) { "请选择聊天模型。" }
            val catalog = currentCatalog()
            val (model, provider) = catalog.entries(mode).find { (model, provider) ->
                provider.id == route.providerId && model.id == route.modelId
            } ?: error("请在供应商设置中恢复并启用此回复使用的聊天模型。")
            val protocol = requireNotNull(provider.chatProtocol()) { "此回复使用的供应商已不再配置在线聊天协议。" }
            if (retry) {
                val savedProtocol = request.parameters[CHAT_PROTOCOL_PARAMETER] ?: ChatProtocol.ResponsesHttp.name
                require(savedProtocol == protocol.name) { "此回复使用的 API 协议已更改，请恢复原协议后重试。" }
            }
            request.parameters["model"]?.let { originalModel ->
                require(model.externalModelId == originalModel) { "此回复使用的模型 ID 已更改，请恢复原模型 ID 后重试。" }
            }
            provider.chatProtocolIssue()?.let { error(it) }
            val key = sessionKey(provider)
            provider.chatKeyIssue(model, key)?.let { error(it) }
            val transcript = history.responsesHistory()
            val prepared = withContext(Dispatchers.IO) {
                val attachments = transcript.filter { it.role == Role.User }.flatMap { it.attachments }
                val sources = attachmentSources(attachments, provider)
                val options = catalog.parameters(model, request.parameters)
                when (protocol) {
                    ChatProtocol.ChatCompletionsHttp ->
                        catalog.chatCompletionsRequest(provider, model, transcript, key, options, systemPrompt, sources::getValue)
                    ChatProtocol.ResponsesHttp, ChatProtocol.ResponsesWebSocket ->
                        catalog.responsesRequest(provider, model, transcript, key, options, systemPrompt, sources::getValue)
                }
            }
            emitAll(stream(protocol, prepared, request))
        }
    }
}

internal const val CHAT_PROTOCOL_PARAMETER = "chat_protocol"

private object ChatTransports {
    private val http = ResponsesTransport()
    private val socket by lazy { ResponsesWebSocketTransport() }

    fun stream(protocol: ChatProtocol, request: LocalModelRequest, message: Message): Flow<Message> = when (protocol) {
        ChatProtocol.ChatCompletionsHttp -> http.streamChatCompletions(request, message)
        ChatProtocol.ResponsesHttp -> http.stream(request, message)
        ChatProtocol.ResponsesWebSocket -> socket.stream(request, message)
    }
}

internal fun Provider.chatKeyIssue(model: ProviderModel, key: String): String? {
    val hasHeader = model.profile.headers.any { (name, value) -> name.equals("Authorization", true) && value.isNotBlank() }
    return if (key.isBlank() && !hasHeader) "请在「$name」供应商设置中填写本次运行使用的 API Key 并保存。" else null
}
