package io.hydrion.hub.ui

import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.ImageInvocation
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.selectedTool
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.imageTarget
import io.hydrion.hub.provider.inputIssue
import io.hydrion.hub.provider.localRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Build and validate a snapshot of the configured request before starting the local simulation. */
internal suspend fun sendDraft(vm: HubViewModel, providers: ProviderStore): String? {
    val before = vm.state.value
    val mode = before.current.mode
    val catalog = providers.catalog.value
    val model = catalog.selectedModel(mode) ?: return "请在设置中启用供应商并添加模型。"
    model.inputIssue(before.draft)?.let { return it }
    val tool = selectedTool(mode, before.draft)
    val usesImages = mode == Mode.Image || tool?.startsWith("图片生成") == true
    val builtin = mode == Mode.Chat && before.draft.imageTool == "模型内置图片生成"

    if (mode == Mode.Chat && model.profile.type != null && tool != null && "tools" !in model.profile.abilities &&
        !(usesImages && builtin && model.profile.builtinImage)) return "当前模型未启用工具能力，请调整模型或工具箱设置。"

    val imageTarget = if (usesImages) catalog.imageTarget(builtin)
        ?: return if (builtin) "请启用内置图片生成并选择可用的图片模型。" else "请启用图片供应商并选择图片模型。"
    else null
    val prepared = withContext(Dispatchers.Default) {
        runCatching {
            val request = if (mode == Mode.Image && imageTarget != null)
                catalog.localRequest(model, before.draft.text, imageTarget.second.requestBody)
            else catalog.localRequest(model, before.draft.text,
                requests = if (imageTarget != null && !builtin) 2 else 1)
            val image = when {
                imageTarget == null -> null
                mode == Mode.Image -> ImageInvocation(request.model, request.parameters)
                else -> catalog.localRequest(imageTarget.first, before.draft.text, imageTarget.second.requestBody,
                    parentImageLimit = model.profile.imageBudget)
                    .let { ImageInvocation(it.model, it.parameters) }
            }
            request to image
        }
    }
    val (request, image) = prepared.getOrElse { return "本地请求未发送：${it.message.orEmpty()}" }
    val now = vm.state.value
    if (now.current.id == before.current.id && now.current.mode == mode && now.draft == before.draft && !now.isGenerating) {
        vm.send(request.model, request.parameters, image = image)
    }
    return null
}
