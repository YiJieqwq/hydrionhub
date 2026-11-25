package io.hydrion.hub.provider

import io.hydrion.hub.model.AskWireRound
import io.hydrion.hub.model.Mode
import org.json.JSONObject
import org.json.JSONArray

internal class LocalModelRequest(
    val model: String,
    val headers: Map<String, String>,
    val body: JSONObject,
    val parameters: Map<String, String>,
    val url: String = "",
    val questions: AskWireRound? = null
)

internal const val IMAGE_MODEL_REQUIRED = "所选图像生成模型不可用。请前往「设置 → 图像生成模型」启用供应商，或重新选择模型。"

internal fun ModelCatalog.imageRequest(prompt: String, parentImageLimit: Int? = null): LocalModelRequest {
    val (model, provider) = requireNotNull(selectedEntry(Mode.Image)) { IMAGE_MODEL_REQUIRED }
    return localRequest(model, prompt, provider.requestBody, parentImageLimit = parentImageLimit, provider = provider)
}

/** Assemble the same local input for preview and simulation. Only safe scalar fields reach history. */
internal fun ModelCatalog.localRequest(
    model: ProviderModel,
    prompt: String,
    imageTemplate: String? = null,
    requests: Int = 1,
    parentImageLimit: Int? = null,
    provider: Provider? = ownerOf(model)
): LocalModelRequest {
    require(requests > 0) { "请求次数必须是正整数。" }
    require(model.profile.budget?.let { requests <= it } != false) { "本次发送需要 $requests 次模拟请求，超过模型的请求预算。" }
    val mode = model.profile.type?.mode ?: provider?.mode
    val values = provider?.let { parameters(model, it) } ?: parameters(model, emptyMap())
    val options = model.requestParameters(values, mode)
    val sample = RequestTemplate.sample(model, parameters = options, prompt = prompt)
    val body = if (imageTemplate == null) {
        JSONObject(options - CONTEXT_WINDOW_PARAMETER - CONFIGURED_WINDOW_PARAMETER).put("model", model.externalModelId).put("prompt", prompt)
    } else JSONObject(RequestTemplate.preview(imageTemplate, sample).getOrThrow())
    if (model.profile.type != null) {
        body.put("input_modalities", JSONArray(model.profile.input))
        body.put("output_modalities", JSONArray(model.profile.output))
        body.put("abilities", JSONArray(model.profile.abilities))
    }
    if (model.profile.imageLinks) body.put("allow_remote_image_links", true)
    if (model.profile.codeInterpreter) body.put("code_interpreter", true)
    if (model.profile.requestBody.isNotBlank()) {
        val additions = JSONObject(RequestTemplate.preview(model.profile.requestBody, null).getOrThrow())
        for (key in additions.keys()) body.put(key, additions.get(key))
    }
    if (imageTemplate != null) {
        val limit = listOfNotNull(model.profile.imageBudget, parentImageLimit, 4).min()
        require(limit > 0) { "本次发送的图片预算为 0 张。" }
        val count = if (!body.has("n")) options["n"]?.toIntOrNull() ?: 1
            else body.opt("n")?.toString()?.toIntOrNull() ?: error("图片数量必须是正整数。")
        require(count > 0) { "图片数量必须是正整数。" }
        body.put("n", count.coerceAtMost(limit))
    }
    val supported = model.configureCapabilities().map { it.key } + if (imageTemplate == null) emptyList()
        else listOf("size", "aspect", "quality", "n")
    val simulated = options.toMutableMap()
    for (key in supported) {
        val value = body.opt(key)
        if (value is String || value is Number || value is Boolean) simulated[key] = value.toString()
    }
    if (!body.has("size") && body.optInt("width") > 0 && body.optInt("height") > 0) {
        simulated["size"] = "${body.optInt("width")}x${body.optInt("height")}"
    }
    if (imageTemplate != null && !body.has("aspect") && options["size"] != simulated["size"]) {
        simulated.remove("aspect")
        simulated["size"]?.let { size -> simulated.putAll(model.choose(simulated, "size", size)) }
    }
    if (simulated.containsKey("reasoning_enabled") && simulated["effort"] == "Off") simulated["reasoning_enabled"] = "false"
    return LocalModelRequest(model.name, model.profile.headers.filterValues { it.isNotEmpty() }, body, simulated)
}
