package io.hydrion.hub.provider

import io.hydrion.hub.model.ImageInvocation
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import java.net.URI
import org.json.JSONObject

private const val OFFICIAL_IMAGE_ENDPOINT = "https://api.openai.com/v1/images/generations"

internal fun Provider.imageEndpoint(): String {
    require(mode == Mode.Image && protocol.trim().equals("OpenAI Images", true) && id != "demo-image") {
        "请单独配置使用 OpenAI Images 协议的图像生成模型供应商。"
    }
    val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
    require(uri != null && uri.scheme.equals("https", true) &&
        (uri.rawAuthority.equals("api.openai.com", true) || uri.rawAuthority.equals("api.openai.com:443", true)) &&
        uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath in setOf("", "/", "/v1", "/v1/", "/v1/images/generations")) {
        "图像生成仅支持 OpenAI 官方 HTTPS 地址，请使用 https://api.openai.com/v1。"
    }
    return OFFICIAL_IMAGE_ENDPOINT
}

internal fun ModelCatalog.imageInvocation(prompt: String, parentImageLimit: Int? = null): ImageInvocation {
    val entry = requireNotNull(selectedEntry(Mode.Image)) { IMAGE_MODEL_REQUIRED }
    if (entry.second.baseUrl.isBlank()) {
        require(entry.second.id == "demo-image" || entry.second.protocol.trim().equals("local", true)) {
            "请选择本机图片模型，或为在线图像供应商配置受支持的协议与官方 HTTPS 地址。"
        }
        val local = imageRequest(prompt, parentImageLimit)
        return ImageInvocation(local.model, local.parameters)
    }
    val request = prepareImageGeneration(prompt, parentImageLimit)
    return ImageInvocation(request.model, request.parameters, remoteModel = entry.route)
}

/** Render the selected template once. Keep only the wire options safe to persist and replay. */
internal fun ModelCatalog.prepareImageGeneration(prompt: String, parentImageLimit: Int? = null): LocalModelRequest {
    val (model, provider) = requireNotNull(selectedEntry(Mode.Image)) { IMAGE_MODEL_REQUIRED }
    val endpoint = provider.imageEndpoint()
    val id = model.externalModelId
    require(id in supportedImageModels) { "请选择受支持的 OpenAI GPT 图像生成模型。" }
    val template = try {
        imageRequest(prompt, parentImageLimit).body
    } catch (_: Exception) {
        // Template parser errors can quote arbitrary configuration values, including credentials.
        throw IllegalArgumentException("图像请求模板无效或超出模型预算，请检查 JSON、模板变量和张数。")
    }
    val localFields = setOf("width", "height", "aspect", "input_modalities", "output_modalities", "abilities",
        "allow_remote_image_links", "code_interpreter")
    require(template.keys().asSequence().all { it == "prompt" || it in imageOptions || it in localFields }) {
        "当前图像请求仅支持 model、prompt、n、size 和 quality，请检查自定义字段。"
    }
    require(!template.has("model") || template.opt("model") == id) { "请求模板不可替换所选图像模型。" }
    require(template.opt("prompt") == prompt) { "在线生图模板须原样使用 {{prompt}}，以便编辑和重试。" }
    val options = mutableMapOf("model" to id, "n" to template.getInt("n").toString())
    imageSize(template)?.let { options["size"] = it }
    if (template.has("quality")) options["quality"] = imageQuality(model, template.opt("quality") as? String)
    return LocalModelRequest(model.name, model.profile.headers, imageBody(model, prompt, options), options, endpoint)
}

/** Prepare a request for the saved route, regardless of subsequent changes to the active image selection. */
internal fun ModelCatalog.prepareOfficialImageGeneration(
    route: RemoteModel, prompt: String, parameters: Map<String, String>, sessionKey: (Provider) -> String
): LocalModelRequest {
    val (model, provider) = requireNotNull(entries(Mode.Image).singleOrNull { it.route == route }) {
        "原图像生成模型或供应商已停用或移除，请恢复该配置后重试。"
    }
    val endpoint = provider.imageEndpoint()
    require(parameters["model"] == model.externalModelId) { "原图像生成模型 ID 已更改，请恢复原配置后重试。" }
    val body = imageBody(model, prompt, parameters)
    val custom = model.profile.headers.filterValues { it.isNotBlank() }
    val allowed = setOf("authorization", "openai-project", "openai-organization", "x-client-request-id", "accept", "content-type")
    require(custom.all { (name, value) -> name.lowercase() in allowed && value.all { it.code in 32..126 } }) {
        "图像服务的请求头无效或不受支持。"
    }
    val headers = chatHeaders(custom, sessionKey(provider), provider.name) + ("Accept" to "application/json")
    val authorization = headers.entries.single { it.key.equals("Authorization", true) }.value
    require(Regex("(?i)Bearer [!-~]+").matches(authorization)) { "OpenAI 图像服务需要 Bearer 授权。" }
    return LocalModelRequest(model.name, headers, body, parameters, endpoint)
}

private val imageOptions = setOf("model", "size", "quality", "n")
private fun imageBody(model: ProviderModel, prompt: String, parameters: Map<String, String>): JSONObject {
    require(model.externalModelId in supportedImageModels) { "请选择受支持的 OpenAI GPT 图像生成模型。" }
    require(prompt.isNotBlank() && prompt.codePointCount(0, prompt.length) <= 32_000) {
        "图像提示词必须是 1 到 32000 个字符的文本。"
    }
    require(parameters.keys.all { it in imageOptions }) { "原图像请求包含不支持的参数。" }
    require((model.profile.budget ?: 1) > 0) { "当前模型的请求预算不足。" }
    val count = parameters["n"]?.toIntOrNull()
    require(count != null && count in 1..minOf(4, model.profile.imageBudget ?: 4)) { "图像数量超过当前模型的预算。" }
    val body = JSONObject().put("model", model.externalModelId).put("prompt", prompt).put("n", count)
    parameters["size"]?.let { size ->
        require(supportsSize(model, size)) { "当前模型不支持该图像尺寸。" }
        body.put("size", size)
    }
    parameters["quality"]?.let { body.put("quality", imageQuality(model, it)) }
    return body
}

private val dimensions = Regex("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}")
private val extendedQualityModels = setOf("gpt-image-2.6-sunburst", "gpt-image-2.6-flare",
    "gpt-image-2.5-sunburst", "gpt-image-2.5-flare",
    "gpt-image-2.5-sunburst-2026-09-08", "gpt-image-2.5-flare-2026-09-08")
private val variableSizeModels = extendedQualityModels + setOf("gpt-image-2", "gpt-image-2-2026-04-21")
private val supportedImageModels = variableSizeModels + setOf("gpt-image-1-mini", "gpt-image-1.5", "chatgpt-image-latest")
private val commonSizes = setOf("auto", "1024x1024", "1536x1024", "1024x1536")

private fun imageSize(body: JSONObject): String? {
    val dimensionFields = body.has("width") || body.has("height")
    val fromDimensions = if (dimensionFields) {
        val w = body.opt("width")?.toString()?.toIntOrNull()
        val h = body.opt("height")?.toString()?.toIntOrNull()
        require(w != null && h != null && w > 0 && h > 0) { "图像宽高均须为正整数。" }
        "${w}x${h}"
    } else null
    if (!body.has("size")) return fromDimensions
    val size = (body.opt("size") as? String)?.lowercase()
    require(size != null && (size == "auto" || dimensions.matches(size)) && (fromDimensions == null || fromDimensions == size)) {
        "size 必须为 auto 或 宽x高，并须与单独设置的宽高一致。"
    }
    return size
}

private fun supportsSize(model: ProviderModel, size: String): Boolean {
    val id = model.externalModelId.lowercase()
    if (size in commonSizes) return true
    if (!dimensions.matches(size)) return false
    if (id in variableSizeModels) {
        val (w, h) = size.split('x').map(String::toInt)
        val pixels = w.toLong() * h
        return w % 16 == 0 && h % 16 == 0 && maxOf(w, h) <= 3840 && pixels in 655_360L..8_294_400L &&
            maxOf(w, h) <= 3 * minOf(w, h)
    }
    return false
}

private fun imageQuality(model: ProviderModel, raw: String?): String {
    val id = model.externalModelId.lowercase()
    val quality = raw?.lowercase()?.let { if (it == "standard") "auto" else it }
    val supported = if (id in extendedQualityModels) setOf("auto", "low", "medium", "high", "xhigh", "max")
        else setOf("auto", "low", "medium", "high")
    require(quality != null && quality in supported) { "当前模型不支持该图像质量。" }
    return quality
}
