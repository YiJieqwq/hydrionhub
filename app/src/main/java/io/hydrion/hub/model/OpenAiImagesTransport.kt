package io.hydrion.hub.model

import android.util.JsonReader
import android.util.JsonToken
import io.hydrion.hub.provider.LocalModelRequest
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import org.json.JSONObject

private const val IMAGE_ENDPOINT = "https://api.openai.com/v1/images/generations"
private const val MIB = 1024 * 1024
private val imageClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()
}

internal class OpenAiImagesTransport(
    private val calls: Call.Factory = imageClient,
    private val imageLimit: Int = minOf(12L * MIB, Runtime.getRuntime().maxMemory() / 32).toInt(),
    private val batchLimit: Int = minOf(32L * MIB, Runtime.getRuntime().maxMemory() / 12).toInt()
) {
    suspend fun generate(prepared: LocalModelRequest): List<ByteArray> {
        require(prepared.url == IMAGE_ENDPOINT) { "图像生成仅支持 OpenAI 官方图像服务地址。" }
        val body = prepared.body
        val count = when (val n = body.opt("n")) {
            null -> 1
            is Int -> n
            is Long -> if (n in 1L..10L) n.toInt() else 0
            else -> 0
        }
        require(count in 1..10) { "图像数量必须为 1 到 10 的整数。" }
        val model = body.opt("model") as? String
        val prompt = body.opt("prompt") as? String
        require(!model.isNullOrBlank() && model.length <= 256 && !prompt.isNullOrBlank() &&
            prompt.codePointCount(0, prompt.length) <= 32_000) {
            "图像模型或提示词为空或过长。"
        }
        val allowed = setOf("model", "prompt", "n", "size", "quality", "output_format")
        require(body.keys().asSequence().all { it in allowed } &&
            (!body.has("output_format") || body.opt("output_format") == "png") &&
            listOf("size", "quality").all { !body.has(it) || ((body.opt(it) as? String)?.length ?: 0) in 1..64 }) {
            "当前图像生成仅支持非流式 PNG。"
        }
        val wire = JSONObject()
        body.keys().forEach { key -> wire.put(key, body.get(key)) }
        wire.put("output_format", "png")
        val bytes = wire.toString().toByteArray(Charsets.UTF_8)
        val payload = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            // Includes the 503 / Retry-After: 0 follow-up path in OkHttp.
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        }
        val builder = Request.Builder().url(IMAGE_ENDPOINT).post(payload).header("Accept", "application/json")
        val seen = mutableSetOf<String>()
        val allowedHeaders = setOf("authorization", "openai-project", "openai-organization", "x-client-request-id")
        for ((name, value) in prepared.headers) {
            val key = name.lowercase()
            if (key == "accept" || key == "content-type") continue
            require(key in allowedHeaders && seen.add(key) && value.isNotBlank() && value.all { it.code in 32..126 }) {
                "图像服务的请求头无效或不受支持。"
            }
            if (key == "authorization") {
                require(Regex("(?i)Bearer [!-~]+").matches(value)) { "OpenAI 图像服务需要 Bearer 授权。" }
            }
            builder.header(name, value)
        }
        require("authorization" in seen) { "请在图像生成模型供应商中配置本次运行的 API Key。" }

        val call = calls.newCall(builder.build())
        return suspendCancellableCoroutine { waiting ->
            waiting.invokeOnCancellation { call.cancel() }
            if (!waiting.isActive) return@suspendCancellableCoroutine
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    waiting.resumeWith(Result.failure(IOException("OpenAI 图像服务连接中断。")))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result: Result<List<ByteArray>> = try {
                        response.use {
                            if (!waiting.isActive) return
                            if (!it.isSuccessful) throw ImageResponseError("OpenAI 图像生成失败（HTTP ${it.code}）。")
                            val content = it.body ?: throw ImageResponseError("图像服务返回了空响应。")
                            Result.success(readImages(content, count) { waiting.isActive })
                        }
                    } catch (e: ImageResponseError) {
                        Result.failure(e)
                    } catch (_: IOException) {
                        Result.failure(IOException("图像响应中断或 JSON 格式无效。"))
                    } catch (_: RuntimeException) {
                        // Android JsonReader may also reject malformed Unicode escapes with NumberFormatException.
                        Result.failure(IOException("图像服务返回了无效的 JSON 结构。"))
                    }
                    waiting.resumeWith(result)
                }
            })
        }
    }

    private fun readImages(body: ResponseBody, count: Int, active: () -> Boolean): List<ByteArray> {
        val media = body.contentType()
        if (media == null || media.type != "application" || media.subtype != "json") throw ImageResponseError("图像服务未返回 JSON。")
        val batch = minOf(batchLimit.toLong(), imageLimit.toLong() * count)
        val jsonLimit = ((batch + 2) / 3) * 4 + 128 * 1024
        if (body.contentLength() > jsonLimit) throw ImageResponseError("图像响应超过大小限制。")
        val maxString = maxOf(8192L, ((minOf(batch, imageLimit.toLong()) + 2) / 3) * 4)
        val limited = ImageJsonInput(body.byteStream(), jsonLimit, maxString, active)
        // The decoder rejects malformed UTF-8. JsonReader skips unknown metadata without creating a full JSON tree.
        val json = JsonReader(InputStreamReader(limited, Charsets.UTF_8.newDecoder()))
        return json.use {
            val result = mutableListOf<ByteArray>()
            var dataSeen = false
            var formatSeen = false
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "data" -> {
                        if (dataSeen) throw ImageResponseError("图像响应含重复的 data 字段。")
                        dataSeen = true
                        json.beginArray()
                        var remaining = batch
                        while (json.hasNext()) {
                            if (result.size >= count) throw ImageResponseError("图像服务返回的图片过多。")
                            val image = readImage(json, minOf(remaining, imageLimit.toLong()))
                            result.add(image)
                            remaining -= image.size
                        }
                        json.endArray()
                    }
                    "output_format" -> {
                        if (formatSeen || json.peek() != JsonToken.STRING || json.nextString() != "png") {
                            throw ImageResponseError("图像响应的输出格式无效。")
                        }
                        formatSeen = true
                    }
                    "error" -> throw ImageResponseError("OpenAI 图像服务返回错误。")
                    else -> json.skipValue()
                }
            }
            json.endObject()
            if (json.peek() != JsonToken.END_DOCUMENT || !dataSeen || result.size != count) {
                throw ImageResponseError("图像响应不完整或返回数量与请求不一致。")
            }
            result
        }
    }

    private fun readImage(json: JsonReader, remaining: Long): ByteArray {
        var image: ByteArray? = null
        var formatSeen = false
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "output_format" -> {
                    if (formatSeen || json.peek() != JsonToken.STRING || json.nextString() != "png") {
                        throw ImageResponseError("图像数据的输出格式无效。")
                    }
                    formatSeen = true
                }
                "url" -> throw ImageResponseError("图像服务返回了链接，需要内嵌 PNG。")
                "b64_json" -> {
                    if (image != null || json.peek() != JsonToken.STRING) throw ImageResponseError("图像数据字段无效。")
                    val encoded = json.nextString()
                    val padding = when {
                        encoded.endsWith("==") -> 2
                        encoded.endsWith("=") -> 1
                        else -> 0
                    }
                    val length = encoded.length / 4L * 3 - padding
                    if (encoded.length % 4 != 0 || length !in 1..remaining) {
                        throw ImageResponseError("图像大小或 Base64 长度无效。")
                    }
                    val decoded = try {
                        Base64.getDecoder().decode(encoded)
                    } catch (_: IllegalArgumentException) {
                        throw ImageResponseError("图像的 Base64 编码无效。")
                    }
                    if (!isPngEnvelope(decoded)) throw ImageResponseError("图像服务返回的内容不是有效的 PNG。")
                    image = decoded
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return image ?: throw ImageResponseError("图像响应缺少内嵌 PNG。")
    }
}

private class ImageResponseError(message: String) : IOException(message)

private val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82)
private val pngEnd = byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, 0xae.toByte(), 0x42, 0x60, 0x82.toByte())

// Storage validates dimensions and actual decode before committing. Here reject unrelated or truncated binary payloads.
private fun isPngEnvelope(bytes: ByteArray): Boolean = bytes.size >= 57 &&
    pngHeader.indices.all { bytes[it] == pngHeader[it] } &&
    pngEnd.indices.all { bytes[bytes.size - pngEnd.size + it] == pngEnd[it] }

/** Limits transparent gzip and chunked bodies as they are read, including ignored metadata.
 * Track nesting before JsonReader grows its stack for an unknown field.
 */
private class ImageJsonInput(
    input: InputStream,
    private val limit: Long,
    private val maxString: Long,
    private val active: () -> Boolean
) : FilterInputStream(input) {
    private var size = 0L
    private var depth = 0
    private var quoted = false
    private var escaped = false
    private var unicodeDigits = 0
    private var stringSize = 0L

    override fun read(): Int {
        val byte = super.read()
        if (byte != -1) {
            checkSize(1)
            scan(byte)
        }
        return byte
    }

    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
        val count = super.read(bytes, off, len)
        if (count > 0) {
            checkSize(count)
            for (i in off until off + count) scan(bytes[i].toInt())
        }
        return count
    }

    private fun checkSize(count: Int) {
        size += count
        if (!active() || size > limit) throw ImageResponseError("图像响应被取消或超过大小限制。")
    }

    private fun scan(byte: Int) {
        if (quoted) {
            when {
                unicodeDigits > 0 -> unicodeDigits--
                escaped -> {
                    escaped = false
                    if (byte == 'u'.code) unicodeDigits = 4
                }
                byte == '"'.code -> quoted = false
                else -> {
                    if (++stringSize > maxString) throw ImageResponseError("图像响应字段超过大小限制。")
                    if (byte == '\\'.code) escaped = true
                }
            }
        } else when (byte) {
            '"'.code -> {
                quoted = true
                stringSize = 0
            }
            '['.code, '{'.code -> if (++depth > 64) throw ImageResponseError("图像响应的 JSON 嵌套过深。")
            ']'.code, '}'.code -> depth--
        }
    }
}
