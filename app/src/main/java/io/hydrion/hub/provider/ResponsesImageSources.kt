package io.hydrion.hub.provider

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import android.util.Base64OutputStream
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.ui.messages.writePng
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI

private val imageTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
private val inlineImageTypes = imageTypes.mapTo(mutableSetOf()) { "$it;base64" }
// The request, its encoded image strings and the UTF-8 upload can coexist in the app heap.
internal fun responsesImageBudget(): Int = minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()
private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
private const val MAX_INLINE_SIZE = ((MAX_IMAGE_BYTES + 2) / 3) * 4
internal const val MAX_DEEPSEEK_IMAGES = 600
internal val Provider.responsesImageLimit: Int
    get() = if (usesDeepSeekDialect()) MAX_DEEPSEEK_IMAGES else 1500

private fun Attachment.imageType(): String {
    if (imageKey != null) return "image/png"
    val declared = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (declared.isNotBlank() && declared != "application/octet-stream") return declared
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> ""
    }
}

internal fun validateResponsesImage(file: Attachment, source: String, imageLinks: Boolean) {
    if (!source.startsWith("data:")) {
        require(file.imageType() in imageTypes) { "附件「${file.name}」须是 PNG、JPEG、GIF 或 WebP 图片。" }
        require(imageLinks) { "当前模型未启用远程图像链接。" }
        val uri = runCatching { URI(source) }.getOrNull()
        require(source.length <= 8192 && uri?.scheme.equals("https", true) && uri?.host != null && uri.rawUserInfo == null) {
            "附件「${file.name}」需要本地图片或有效的 HTTPS 地址。"
        }
        return
    }
    val comma = source.indexOf(',')
    require(comma in 6..48 && source.substring(5, comma).lowercase() in inlineImageTypes) {
        "附件「${file.name}」须是 PNG、JPEG、GIF 或 WebP 图片。"
    }
    val start = comma + 1
    var end = source.length
    while (end > start && source[end - 1] == '=') end--
    val dataLength = end - start
    val padding = source.length - end
    require(dataLength * 6L / 8 <= MAX_IMAGE_BYTES && dataLength > 0 && dataLength % 4 != 1 && padding <= 2 &&
        (padding == 0 || (dataLength + padding) % 4 == 0)) { "附件「${file.name}」的图片数据无效或过大。" }
    for (i in start until end) {
        val c = source[i]
        require(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '/' || c == '+') { "附件「${file.name}」的图片数据无效。" }
    }
}

private class LimitedImageBuffer(private val limit: Int) : ByteArrayOutputStream() {
    override fun write(b: Int) {
        require(size() < limit) { "图片过大，请减少附件或缩小图片。" }
        super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        require(len <= limit - size()) { "图片过大，请减少附件或缩小图片。" }
        super.write(b, off, len)
    }
}

/** Resolve only explicitly selected content URIs or local generated references; network URLs stay references. Call on IO. */
internal suspend fun ContentResolver.responsesImageSources(
    attachments: List<Attachment>, provider: Provider
): Map<Attachment, String> {
    require(attachments.size <= provider.responsesImageLimit) { "每次请求最多可包含 ${provider.responsesImageLimit} 张图片。" }
    var remaining = responsesImageBudget()
    val result = mutableMapOf<Attachment, String>()
    for (file in attachments.distinct()) {
        currentCoroutineContext().ensureActive()
        val uri = file.uri?.let(Uri::parse)
        val source = if (file.imageKey != null || uri?.scheme == ContentResolver.SCHEME_CONTENT) {
            val mime = file.imageType().ifEmpty { getType(requireNotNull(uri))?.substringBefore(';')?.trim()?.lowercase().orEmpty() }
            require(mime in imageTypes) { "当前支持 PNG、JPEG、GIF 和 WebP 图片，无法加入附件「${file.name}」。" }
            val prefix = "data:$mime;base64,"
            val buffer = LimitedImageBuffer(minOf(remaining, MAX_INLINE_SIZE + prefix.length))
            buffer.write(prefix.toByteArray(Charsets.US_ASCII))
            Base64OutputStream(buffer, Base64.NO_WRAP).use { encoded ->
                if (file.imageKey != null) {
                    writePng(encoded, file.imageKey, file.width ?: 1024, file.height ?: 1024)
                } else {
                    val input = openInputStream(requireNotNull(uri)) ?: throw IOException("无法打开附件「${file.name}」。")
                    input.use {
                        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val length = it.read(chunk)
                            if (length < 0) break
                            encoded.write(chunk, 0, length)
                        }
                    }
                }
            }
            buffer.toString("US-ASCII")
        } else requireNotNull(file.uri) { "附件「${file.name}」缺少可读取的地址。" }
        require(source.length <= remaining) { "图片总量过大，请减少附件。" }
        remaining -= source.length
        result[file] = source
    }
    return result
}
