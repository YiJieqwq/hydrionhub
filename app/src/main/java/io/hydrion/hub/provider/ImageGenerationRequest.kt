package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import java.net.URI
import org.json.JSONObject

internal val Provider.isConfiguredImageEndpoint: Boolean
    get() = mode == Mode.Image && protocol.trim().equals("OpenAI Images", ignoreCase = true) && baseUrl.isNotBlank()

internal fun Provider.imageEndpoint(): String {
    require(isConfiguredImageEndpoint) { "请选择已配置的 OpenAI Images 供应商。" }
    val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
    require(uri != null && uri.scheme.equals("https", true) && uri.host != null && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535) &&
        uri.path.split('/').none { it == "." || it == ".." }) { "图像服务须使用有效的 HTTPS 地址。" }
    val path = uri.rawPath.trimEnd('/').removeSuffix("/images/generations")
    val prefix = if (path.isEmpty() && uri.host.equals("api.openai.com", true)) "/v1" else path
    return "https://${uri.rawAuthority}$prefix/images/generations"
}

internal fun ModelCatalog.prepareImageGeneration(prompt: String, parentImageLimit: Int? = null): LocalModelRequest {
    val (model, provider) = requireNotNull(selectedEntry(Mode.Image)) { IMAGE_MODEL_REQUIRED }
    val endpoint = provider.imageEndpoint()
    require(prompt.isNotBlank()) { "图像生成需要文本提示。" }
    val local = imageRequest(prompt, parentImageLimit)
    val template = local.body
    // Only the non-streaming generation options exposed by this client have wire representations.
    val localFields = setOf("width", "height", "aspect", "input_modalities", "output_modalities", "abilities",
        "allow_remote_image_links", "code_interpreter")
    val allowed = setOf("model", "prompt", "n", "size", "quality")
    require(template.keys().asSequence().all { it in allowed || it in localFields }) {
        "当前图像请求仅支持 model、prompt、n、size 和 quality；请检查自定义字段。"
    }
    val text = template.opt("prompt") as? String
    require(!text.isNullOrBlank() && text.codePointCount(0, text.length) <= 32_000) { "图像提示词必须是 1 到 32000 个字符的文本。" }
    val id = model.externalModelId
    require(id.isNotBlank()) { "图像生成模型 ID 不能为空。" }
    val count = template.getInt("n")
    require(count in 1..10 && (id != "dall-e-3" || count == 1)) { "图像数量超过当前模型支持的范围。" }
    val body = JSONObject().put("model", id).put("prompt", text).put("n", count)
    val options = local.parameters.toMutableMap()
    imageSize(template)?.let { size ->
        require(supportsSize(model, size)) { "当前模型不支持该图像尺寸。" }
        body.put("size", size)
        options["size"] = size
    }
    if (template.has("quality")) {
        val quality = imageQuality(model, template.opt("quality") as? String)
        body.put("quality", quality)
        options["quality"] = quality
    }
    return LocalModelRequest(local.model, local.headers, body, options, endpoint)
}

private val dimensions = Regex("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}")
private val extendedQualityModels = setOf("gpt-image-2.5-sunburst", "gpt-image-2.5-flare",
    "gpt-image-2.5-sunburst-2026-09-08", "gpt-image-2.5-flare-2026-09-08")
private val variableSizeModels = extendedQualityModels + setOf("gpt-image-2", "gpt-image-2-2026-04-21")
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
    if (id == "dall-e-2") return size in setOf("256x256", "512x512", "1024x1024")
    if (id == "dall-e-3") return size in setOf("1024x1024", "1792x1024", "1024x1792")
    if (size in commonSizes) return true
    if (!dimensions.matches(size)) return false
    if (id in variableSizeModels) {
        val (w, h) = size.split('x').map(String::toInt)
        return w % 16 == 0 && h % 16 == 0 && maxOf(w, h) <= 3840 && w.toLong() * h <= 3840L * 2160 &&
            maxOf(w, h) <= 3 * minOf(w, h)
    }
    if (id.startsWith("gpt-image-") || id == "chatgpt-image-latest") return false
    return model.configureCapabilities().any { it.key == "size" && size in it.choices }
}

private fun imageQuality(model: ProviderModel, raw: String?): String {
    val id = model.externalModelId.lowercase()
    val value = raw?.lowercase()
    val dallE = id == "dall-e-2" || id == "dall-e-3"
    val quality = when {
        value == "standard" && !dallE -> "auto"
        value == "high" && id == "dall-e-3" -> "hd"
        else -> value
    }
    val supported = when {
        id == "dall-e-2" -> setOf("standard")
        id == "dall-e-3" -> setOf("standard", "hd")
        id in extendedQualityModels ->
            setOf("auto", "low", "medium", "high", "xhigh", "max")
        else -> setOf("auto", "low", "medium", "high")
    }
    require(quality != null && quality in supported) { "当前模型不支持该图像质量。" }
    return quality
}
