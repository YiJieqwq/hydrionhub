package io.hydrion.hub.provider

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.StringReader

internal object RequestTemplate {
    private const val delimiters = "{}[],:\" \t\r\n"
    private val number = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")
    private val literals = setOf("true", "false", "null")
    val contextVariables = setOf("prompt", "model", "width", "height")

    fun sample(
        model: ProviderModel?,
        models: List<ProviderModel> = listOfNotNull(model),
        parameters: Map<String, String> = emptyMap(),
        prompt: String = "夜色中的山脊"
    ): Map<String, Any> {
        val defaults = model?.configureCapabilities().orEmpty().mapNotNull { cap -> cap.choices.firstOrNull()?.let { cap.key to it } }.toMap() + parameters
        val size = defaults["size"] ?: "1024x1024"
        val dimensions = size.lowercase().split('x', '×')
        val extra = models.flatMap { it.configureCapabilities() }.mapNotNull { cap -> cap.choices.firstOrNull()?.let { cap.key to it } }.toMap() + defaults
        return extra + mapOf(
            "model" to (model?.externalModelId ?: "Hydrion Image"), "prompt" to prompt,
            "size" to size, "width" to (dimensions.firstOrNull()?.toIntOrNull() ?: 1024),
            "height" to (dimensions.getOrNull(1)?.toIntOrNull() ?: 1024),
            "quality" to (defaults["quality"] ?: "Standard"), "n" to (defaults["n"]?.toIntOrNull() ?: 1)
        )
    }

    fun preview(template: String, values: Map<String, Any>?): Result<String> = runCatching {
        val rendered = substitute(template, values)
        JsonReader(StringReader(rendered)).use { reader ->
            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "请求体顶层必须是 JSON 对象" }
            reader.skipValue()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "JSON 对象后还有其他内容" }
        }
        JSONObject(rendered).toString(2)
    }

    /** Inside JSON strings insert escaped character data. Elsewhere insert a typed JSON value. */
    internal fun substitute(template: String, values: Map<String, Any>?): String = buildString {
        var quoted = false
        var escaped = false
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (values != null && !escaped && c == '{' && template.getOrNull(i + 1) == '{') {
                val end = template.indexOf("}}", startIndex = i + 2)
                require(end >= 0) { "模板变量缺少 }}" }
                val key = template.substring(i + 2, end).trim()
                val value = requireNotNull(values[key]) { "未知模板变量：$key" }
                if (quoted || value !is Number) {
                    val json = quote(value.toString())
                    append(if (quoted) json.substring(1, json.length - 1) else json)
                } else append(value)
                i = end + 2
            } else if (!quoted && c !in delimiters) {
                // Android accepts case-insensitive booleans and null even in its strict reader.
                var end = i + 1
                while (end < template.length && template[end] !in delimiters) end++
                val token = template.substring(i, end)
                require(token in literals || number.matches(token)) { "无效的 JSON 值：$token" }
                append(token)
                i = end
            } else {
                if (quoted) {
                    require(c >= ' ') { "JSON 字符串中不能包含未转义的控制字符" }
                    if (escaped) {
                        require(c in "\"\\/bfnrtu") { "无效的 JSON 转义字符" }
                        if (c == 'u') {
                            val hex = template.substring(i + 1, (i + 5).coerceAtMost(template.length))
                            require(hex.length == 4 && hex.all { it in "0123456789abcdefABCDEF" }) { "无效的 Unicode 转义" }
                        }
                    }
                }
                append(c)
                if (escaped) escaped = false
                else if (quoted && c == '\\') escaped = true
                else if (c == '"') quoted = !quoted
                i++
            }
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
            }
        }
        append('"')
    }
}
