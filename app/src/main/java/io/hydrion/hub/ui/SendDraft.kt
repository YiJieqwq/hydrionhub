package io.hydrion.hub.ui

import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.ImageInvocation
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.deepSeekKeyIssue
import io.hydrion.hub.model.selectedTool
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.DEEPSEEK_PROVIDER_ID
import io.hydrion.hub.provider.IMAGE_MODEL_REQUIRED
import io.hydrion.hub.provider.imageRequest
import io.hydrion.hub.provider.inputIssue
import io.hydrion.hub.provider.localRequest
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
    if (mode == Mode.Chat && provider.id == DEEPSEEK_PROVIDER_ID) {
        deepSeekKeyIssue(model, providers.sessionKey(provider.id))?.let { return it }
        val selection = catalog.selection(mode)
        vm.send(selection.label, selection.parameters, remoteModel = RemoteModel(provider.id, model.id))
        return null
    }
    val tool = selectedTool(mode, before.draft)
    val imageTool = tool?.startsWith("图片生成") == true
    if (imageTool && catalog.selectedEntry(Mode.Image) == null) return IMAGE_MODEL_REQUIRED
    if (tool != null && model.profile.type != null && "tools" !in model.profile.abilities)
        return "当前模型未启用工具能力，请调整模型或工具箱设置。"
    val prepared = withContext(Dispatchers.Default) {
        runCatching {
            val prompt = before.draft.text
            val request = if (mode == Mode.Image) catalog.imageRequest(prompt)
                else catalog.localRequest(model, prompt, requests = if (imageTool) 2 else 1)
            val image = when {
                mode == Mode.Image -> request
                imageTool -> catalog.imageRequest(prompt, parentImageLimit = model.profile.imageBudget)
                else -> null
            }
            request to image?.let { ImageInvocation(it.model, it.parameters) }
        }
    }
    val (request, image) = prepared.getOrElse { return "本地请求未发送：${it.message.orEmpty()}" }
    val now = vm.state.value
    if (now.current.id == before.current.id && now.current.mode == mode && now.draft == before.draft &&
        providers.catalog.value == catalog && !now.isGenerating) {
        vm.send(request.model, request.parameters, image = image)
    }
    return null
}
