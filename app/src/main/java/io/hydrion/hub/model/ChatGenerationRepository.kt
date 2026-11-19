package io.hydrion.hub.model

import io.hydrion.hub.provider.LocalModelRequest
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.externalModelId
import io.hydrion.hub.provider.responsesEndpoint
import io.hydrion.hub.provider.responsesHistory
import io.hydrion.hub.provider.responsesRequest
import io.hydrion.hub.provider.usesResponsesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal class ChatGenerationRepository(
    private val currentCatalog: () -> ModelCatalog,
    private val sessionKey: (String) -> String,
    private val attachmentSources: suspend (List<Attachment>, Provider) -> Map<Attachment, String>,
    private val stream: (LocalModelRequest, Message) -> Flow<Message> = ResponsesTransport()::stream
) : GenerationRepository {
    private val local = FakeGenerationRepository()

    override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>): Flow<Message> {
        val route = request.remoteModel ?: return local.generate(mode, request, retry, history)
        return flow {
            require(mode == Mode.Chat) { "Responses API 只用于聊天模型。" }
            val catalog = currentCatalog()
            val (model, provider) = catalog.entries(mode).find { (model, provider) ->
                provider.id == route.providerId && model.id == route.modelId
            } ?: error("请在供应商设置中恢复并启用此回复使用的聊天模型。")
            require(provider.usesResponsesApi()) { "此回复使用的供应商已不再配置为 Responses API。" }
            request.parameters["model"]?.let { originalModel ->
                require(model.externalModelId == originalModel) { "此回复使用的模型 ID 已更改，请恢复原模型 ID 后重试。" }
            }
            val key = sessionKey(provider.id)
            provider.responsesConfigIssue(model, key)?.let { error(it) }
            val transcript = history.responsesHistory()
            val prepared = withContext(Dispatchers.IO) {
                val attachments = transcript.filter { it.role == Role.User }.flatMap { it.attachments }
                val sources = attachmentSources(attachments, provider)
                catalog.responsesRequest(provider, model, transcript, key, catalog.parameters(model, request.parameters),
                    attachmentData = sources::getValue)
            }
            emitAll(stream(prepared, request))
        }
    }
}

internal fun Provider.responsesConfigIssue(model: ProviderModel, key: String): String? {
    try {
        responsesEndpoint()
    } catch (invalid: IllegalArgumentException) {
        return invalid.message ?: "请检查 Responses 供应商的服务地址。"
    }
    val hasHeader = model.profile.headers.any { (name, value) -> name.equals("Authorization", true) && value.isNotBlank() }
    return if (key.isBlank() && !hasHeader) "请在「$name」供应商设置中填写本次运行使用的 API Key 并保存。" else null
}
