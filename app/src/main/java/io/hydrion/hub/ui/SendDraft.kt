package io.hydrion.hub.ui

import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.CHAT_PROTOCOL_PARAMETER
import io.hydrion.hub.model.chatKeyIssue
import io.hydrion.hub.model.selectedTool
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.IMAGE_MODEL_REQUIRED
import io.hydrion.hub.provider.externalModelId
import io.hydrion.hub.provider.imageInvocation
import io.hydrion.hub.provider.ModelSelection
import io.hydrion.hub.provider.inputIssue
import io.hydrion.hub.provider.localRequest
import io.hydrion.hub.provider.chatProtocol
import io.hydrion.hub.provider.chatProtocolIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Validate the selection shown in the composer, preserving the draft on configuration errors. */
internal suspend fun sendDraft(vm: HubViewModel, providers: ProviderStore): String? {
    val before = vm.state.value
    val mode = before.current.mode
    val catalog = providers.catalog.value
    val (model, provider) = catalog.selectedEntry(mode)
        ?: return if (mode == Mode.Image) IMAGE_MODEL_REQUIRED else "请在设置中启用供应商并添加模型。"
    model.inputIssue(before.draft)?.let { return it }
    provider.chatProtocolIssue()?.let { return it }
    val protocol = provider.chatProtocol()
    if (mode == Mode.Chat && protocol != null) {
        provider.chatKeyIssue(model, providers.sessionKey(provider.id))?.let { return it }
        val selection = catalog.selection(mode)
        val parameters = selection.parameters + mapOf("model" to model.externalModelId, CHAT_PROTOCOL_PARAMETER to protocol.name)
        vm.send(selection.label, parameters,
            remoteModel = RemoteModel(provider.id, model.id))
        return null
    }
    val tool = selectedTool(mode, before.draft)
    val imageTool = tool?.startsWith("图片生成") == true
    if (imageTool && catalog.selectedEntry(Mode.Image) == null) return IMAGE_MODEL_REQUIRED
    if (tool != null && model.profile.type != null && "tools" !in model.profile.abilities)
        return "当前模型未启用工具能力，请调整模型或工具箱设置。"
    val prepared = withContext(Dispatchers.Default) {
        runCatching {
            val prompt = before.draft.text.trim()
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
    val (selection, image) = prepared.getOrElse { return it.message ?: "请求未发送，请检查模型设置。" }
    val now = vm.state.value
    if (now.current.id == before.current.id && now.current.mode == mode && now.draft == before.draft &&
        providers.catalog.value == catalog && !now.isGenerating) {
        if (image != null) {
            try {
                vm.validateImage(image, before.draft.text.trim(), before.draft.attachments)
            } catch (invalid: IllegalArgumentException) {
                return invalid.message ?: "请检查图像供应商设置。"
            }
        }
        vm.send(selection.label, selection.parameters, image = image)
    }
    return null
}
