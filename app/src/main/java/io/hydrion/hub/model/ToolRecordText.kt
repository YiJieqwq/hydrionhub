package io.hydrion.hub.model

import io.hydrion.hub.json.JsonSyntax
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

/** Only completed tool payloads cross this boundary into a message or a saved revision. */
internal class ToolRecordText(private val headers: Map<String, String> = emptyMap()) {
    fun name(value: String): String = if (value.length <= 128) plain(value) else ""

    fun arguments(value: Any?): String? {
        if (value !is String) return display(value)
        if (value.length > limit) return null
        return parseContainer(value.trim())?.let(::display)
    }

    fun display(value: Any?): String? {
        if (value == null || value == JSONObject.NULL) return null
        var remaining = limit
        fun consume(size: Int): Boolean { remaining -= size; return remaining >= 0 }
        val safe = clean(value, 0, ::consume)
        val text = when (safe) {
            is JSONObject -> safe.toString(2)
            is JSONArray -> safe.toString(2)
            else -> safe.toString().ifEmpty { "\"\"" }
        }
        return if (text.length <= limit) text else text.take(limit - 1) + "…"
    }

    private fun clean(value: Any, depth: Int, consume: (Int) -> Boolean): Any {
        if (!consume(1) || depth >= 12) return "…"
        return when (value) {
            is JSONObject -> JSONObject().apply {
                val namedSecret = listOf("name", "key").any { (value.opt(it) as? String)?.let(::sensitive) == true }
                for (key in value.keys()) {
                    if (!consume(key.length + 1)) { put("…", "…"); break }
                    put(plain(key), if (sensitive(key) || namedSecret && key.equals("value", true)) hidden
                        else clean(value.get(key), depth + 1, consume))
                }
            }
            is JSONArray -> JSONArray().apply {
                for (i in 0 until value.length()) {
                    if (!consume(1)) { put("…"); break }
                    put(clean(value.get(i), depth + 1, consume))
                }
            }
            is String -> {
                val trimmed = value.trim()
                if (!consume(value.length) || trimmed.startsWith("data:", ignoreCase = true)) return "…"
                if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
                    parseContainer(trimmed)?.let { clean(it, depth + 1, consume) } ?: hidden
                } else plain(value)
            }
            else -> value
        }
    }

    private fun plain(value: String): String {
        var text = redactTransportSecrets(value, headers)
        text = privateKey.replace(text, hidden)
        text = userInfo.replace(text) { it.groupValues[1] + hidden + "@" }
        text = authLine.replace(text) { it.groupValues[1] + ": " + hidden }
        text = basicCredential.replace(text, "Basic " + hidden)
        text = recognizableToken.replace(text, hidden)
        return assignment.replace(text) { it.groupValues[1] + "=" + hidden }
    }

    companion object {
        const val limit = 32_768
        private const val hidden = "[redacted]"
        private val basicCredential = Regex("""(?i)\bBasic\s+[a-z0-9+/]{8,}={0,2}""")
        private val recognizableToken = Regex("""\b(?:tvly-[A-Za-z0-9_-]+|gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|xox[baprs]-[\w-]+|hf_[A-Za-z0-9]+|eyJ[\w-]+\.[\w-]+\.[\w-]+)\b""")
        private val blockedKeys = setOf("headers", "environment", "env", "key", "auth", "sig", "image", "imageurl", "imagedata", "b64json", "base64", "pwd")
        private val secretKey = Regex("authorization|cookie|token|secret|password|passwd|credential|apikey|accesskey|privatekey|signature|sessionid|connectionstring|密码|密钥|凭证|令牌")
        private val privateKey = Regex("""(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?(?:-----END [^-]*PRIVATE KEY-----|\z)""")
        private val userInfo = Regex("""(?i)([a-z][a-z0-9+.-]*://)[^/\s@]+@""")
        private val authLine = Regex("""(?im)\b(authorization|proxy-authorization|cookie|set-cookie)\s*[:=]\s*[^\r\n]*""")
        private val assignment = Regex("""(?i)\b(key|sig|auth|pwd|[\w.-]*(?:api[_-]?key|access[_-]?key|token|secret|passw(?:or)?d|credential|signature|session[_-]?id)[\w.-]*)\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s&,;]+)""")

        private fun sensitive(key: String): Boolean {
            val name = key.lowercase().filter(Char::isLetterOrDigit)
            return name in blockedKeys || secretKey.containsMatchIn(name)
        }

        private fun parseContainer(text: String): Any? = try {
            JsonSyntax(text) { throw JSONException("Invalid tool document") }.check()
            when {
                text.startsWith('{') && text.endsWith('}') -> JSONObject(text)
                text.startsWith('[') && text.endsWith(']') -> JSONArray(text)
                else -> null
            }
        } catch (_: JSONException) { null }
    }
}

/** Raw fragments stay transient. An oversized or unfinished document never becomes a partial credential. */
internal class ToolArgumentFragments {
    private var buffer: StringBuilder? = StringBuilder()
    fun append(fragment: String) {
        val current = buffer ?: return
        if (fragment.length > ToolRecordText.limit - current.length) buffer = null else current.append(fragment)
    }
    fun finish(privacy: ToolRecordText): String? = buffer?.let { privacy.arguments(it.toString()) }
}
