package io.hydrion.hub.model

import io.hydrion.hub.provider.DEEPSEEK_PROVIDER_ID
import io.hydrion.hub.provider.LocalModelRequest
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.deepSeekHistory
import io.hydrion.hub.provider.deepSeekRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal class ChatGenerationRepository(
    private val currentCatalog: () -> ModelCatalog,
    private val sessionKey: (String) -> String,
    private val attachmentSources: suspend (List<Attachment>) -> Map<Attachment, String>,
    private val stream: (LocalModelRequest, Message) -> Flow<Message> = DeepSeekResponsesTransport()::stream
) : GenerationRepository {
    private val local = FakeGenerationRepository()

    override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>): Flow<Message> {
        val route = request.remoteModel ?: return local.generate(mode, request, retry, history)
        return flow {
            require(mode == Mode.Chat && route.providerId == DEEPSEEK_PROVIDER_ID) { "此回复的供应商当前不可用。" }
            val catalog = currentCatalog()
            val (model, provider) = catalog.entries(mode).find { (model, provider) ->
                provider.id == route.providerId && model.id == route.modelId
            } ?: error("请在供应商设置中恢复并启用此回复使用的 DeepSeek 模型。")
            val key = sessionKey(provider.id)
            deepSeekKeyIssue(model, key)?.let { error(it) }
            val transcript = history.deepSeekHistory()
            val prepared = withContext(Dispatchers.IO) {
                val attachments = transcript.filter { it.role == Role.User }.flatMap { it.attachments }
                val sources = attachmentSources(attachments)
                catalog.deepSeekRequest(provider, model, transcript, key, catalog.parameters(model, request.parameters),
                    attachmentData = sources::getValue)
            }
            emitAll(stream(prepared, request))
        }
    }
}

internal fun deepSeekKeyIssue(model: ProviderModel, key: String): String? {
    val hasHeader = model.profile.headers.any { (name, value) -> name.equals("Authorization", true) && value.isNotBlank() }
    return if (key.isBlank() && !hasHeader) "请在 DeepSeek 供应商设置中填写本次运行使用的 API Key 并保存。" else null
}
