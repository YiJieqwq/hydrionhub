package io.hydrion.hub.ui

import android.app.Application
import io.hydrion.hub.R
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Draft
import io.hydrion.hub.model.HubState
import io.hydrion.hub.model.ImageInvocation
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.Role
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.CHAT_PROTOCOL_PARAMETER
import io.hydrion.hub.model.chatKeyIssue
import io.hydrion.hub.model.selectedTool
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.IMAGE_MODEL_REQUIRED
import io.hydrion.hub.provider.externalModelId
import io.hydrion.hub.provider.imageInvocation
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.ModelSelection
import io.hydrion.hub.provider.inputIssue
import io.hydrion.hub.provider.localRequest
import io.hydrion.hub.provider.chatProtocol
import io.hydrion.hub.provider.chatProtocolIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val STALE_SUBMISSION = "The chat or selected model has changed. Review your selection and try again."
private const val CHAT_MODEL_REQUIRED = "Chat model unavailable"

internal fun HubState.canSendDraft(submitted: HubState, liveCatalog: ModelCatalog, submittedCatalog: ModelCatalog): Boolean =
    !submitted.isGenerating && !isGenerating && current.id == submitted.current.id &&
        current.mode == submitted.current.mode && draft == submitted.draft && liveCatalog == submittedCatalog

/** Validate the selection shown in the composer, preserving the draft on configuration errors. */
internal suspend fun sendDraft(
    vm: HubViewModel, providers: ProviderStore, before: HubState, catalog: ModelCatalog, fromDictation: Boolean,
): String? = sendDraft(providers, before, catalog, vm.state,
    localSearchEnabled = { vm.webSearch.settings.value.localEnabled },
    validateImage = vm::validateImage,
) { model, parameters, image, remoteModel ->
    vm.send(model, parameters, image = image, remoteModel = remoteModel, restoreDraftOnError = fromDictation)
}.let { issue -> vm.localizedSendIssue(catalog, issue) }

internal suspend fun sendEditedBranch(
    vm: HubViewModel, before: HubState, catalog: ModelCatalog, messageId: String, text: String, title: String,
): String? = sendEditedBranch(vm.providers, before, catalog, vm.state, messageId, text,
    localSearchEnabled = { vm.webSearch.settings.value.localEnabled }, validateImage = vm::validateImage,
) { model, parameters, image, remoteModel ->
    vm.forkEditedMessage(before.current.id, messageId, text, title, model, parameters, image, remoteModel)
}.let { issue -> vm.localizedSendIssue(catalog, issue) }

private fun HubViewModel.localizedSendIssue(catalog: ModelCatalog, issue: String?): String? = when (issue) {
    CHAT_MODEL_REQUIRED -> {
        val saved = Mode.Chat in catalog.active || Mode.Chat in catalog.defaults
        getApplication<Application>().getString(if (saved) R.string.model_settings_unavailable else R.string.chat_picker_no_models)
    }
    STALE_SUBMISSION -> getApplication<Application>().getString(R.string.turn_branch_model_changed)
    else -> issue
}

internal suspend fun sendDraft(
    providers: ProviderStore,
    before: HubState,
    catalog: ModelCatalog,
    state: StateFlow<HubState>,
    localSearchEnabled: () -> Boolean,
    validateImage: (ImageInvocation, String, List<Attachment>) -> Unit,
    send: (String, Map<String, String>, ImageInvocation?, RemoteModel?) -> Unit,
): String? {
    return sendPreparedDraft(providers, before.current.mode, before.draft, catalog, localSearchEnabled, validateImage,
        stillCurrent = { state.value.canSendDraft(before, providers.catalog.value, catalog) },
    ) { model, parameters, image, remoteModel ->
        send(model, parameters, image, remoteModel)
        true
    }.takeUnless { it == STALE_SUBMISSION }
}

internal suspend fun sendEditedBranch(
    providers: ProviderStore,
    before: HubState,
    catalog: ModelCatalog,
    state: StateFlow<HubState>,
    messageId: String,
    text: String,
    localSearchEnabled: () -> Boolean,
    validateImage: (ImageInvocation, String, List<Attachment>) -> Unit,
    send: (String, Map<String, String>, ImageInvocation?, RemoteModel?) -> Boolean,
): String? {
    val chat = before.current
    if (chat.mode != Mode.Chat || chat.messages.lastOrNull()?.id == messageId) return STALE_SUBMISSION
    val message = chat.messages.find { it.id == messageId && it.role == Role.User } ?: return STALE_SUBMISSION
    if (text.isBlank() && message.attachments.isEmpty()) return STALE_SUBMISSION
    val draft = before.draft.copy(text = text, attachments = message.attachments)
    return sendPreparedDraft(providers, Mode.Chat, draft, catalog, localSearchEnabled, validateImage,
        stillCurrent = {
            val live = state.value
            live.current == chat && live.canSendDraft(before, providers.catalog.value, catalog)
        },
        send = send,
    )
}

private suspend fun sendPreparedDraft(
    providers: ProviderStore, mode: Mode, draft: Draft, catalog: ModelCatalog,
    localSearchEnabled: () -> Boolean,
    validateImage: (ImageInvocation, String, List<Attachment>) -> Unit,
    stillCurrent: () -> Boolean,
    send: (String, Map<String, String>, ImageInvocation?, RemoteModel?) -> Boolean,
): String? {
    suspend fun submit(model: String, parameters: Map<String, String>, image: ImageInvocation?, remote: RemoteModel?): String? {
        currentCoroutineContext().ensureActive()
        return if (stillCurrent() && send(model, parameters, image, remote)) null else STALE_SUBMISSION
    }

    if (!stillCurrent()) return STALE_SUBMISSION
    val (model, provider) = catalog.selectedEntry(mode)
        ?: return if (mode == Mode.Image) IMAGE_MODEL_REQUIRED else CHAT_MODEL_REQUIRED
    model.inputIssue(draft)?.let { return it }
    provider.chatProtocolIssue()?.let { return it }
    val protocol = provider.chatProtocol()
    if (mode == Mode.Chat && protocol != null) {
        provider.chatKeyIssue(model, providers.sessionKey(provider))?.let { return it }
        val selection = catalog.selection(mode)
        val parameters = selection.parameters + mapOf("model" to model.externalModelId, CHAT_PROTOCOL_PARAMETER to protocol.name)
        return submit(selection.label, parameters, null, RemoteModel(provider.id, model.id))
    }
    val tool = selectedTool(mode, draft, localSearchEnabled())
    val imageTool = tool?.startsWith("图片生成") == true
    if (imageTool && catalog.selectedEntry(Mode.Image) == null) return IMAGE_MODEL_REQUIRED
    if (tool != null && model.profile.type != null && "tools" !in model.profile.abilities)
        return "当前模型未启用工具能力，请调整模型或工具箱设置。"
    val prepared = withContext(Dispatchers.Default) {
        runCatching {
            val prompt = draft.text.trim()
            val image = when {
                mode == Mode.Image -> catalog.imageInvocation(prompt)
                imageTool -> catalog.imageInvocation(prompt, parentImageLimit = model.profile.imageBudget)
                else -> null
            }
            val selection = if (mode == Mode.Image) {
                val invocation = requireNotNull(image)
                ModelSelection(invocation.model, invocation.parameters)
            } else {
                val local = catalog.localRequest(model, prompt, requests = if (imageTool) 2 else 1, provider = provider)
                ModelSelection(local.model, local.parameters)
            }
            selection to image
        }
    }
    if (!stillCurrent()) return STALE_SUBMISSION
    val (selection, image) = prepared.getOrElse { return it.message ?: "请求未发送，请检查模型设置。" }
    if (image != null) {
        try {
            validateImage(image, draft.text.trim(), draft.attachments)
        } catch (invalid: IllegalArgumentException) {
            return invalid.message ?: "请检查图像供应商设置。"
        }
    }
    return submit(selection.label, selection.parameters, image, null)
}
