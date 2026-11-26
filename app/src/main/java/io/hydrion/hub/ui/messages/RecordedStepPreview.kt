package io.hydrion.hub.ui.messages

import io.hydrion.hub.json.JsonSyntax
import io.hydrion.hub.model.ReplyToolCall
import io.hydrion.hub.model.ToolRecordText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Present only typed fields from the already redacted transcript. The detail sheet keeps the full record. */
internal class ShellPreview(val exitCode: Int?, val stdout: String?)

internal fun ReplyToolCall.shellPreview(): ShellPreview? {
    if (name !in shellToolNames) return null
    val fields = recordedObject(result) ?: return null
    val code = fields.recordedExitCode(name)
    val stdout = (fields.opt("stdout") as? String)?.takeIf { it.isNotEmpty() }
    return if (code != null || stdout != null) ShellPreview(code, stdout) else null
}

internal fun JSONObject.recordedExitCode(tool: String): Int? {
    val rawCode = opt("exitCode") ?: opt("exit_code")
        ?: if (tool == "bash_code_execution") opt("return_code") else null
    return when (rawCode) {
        is Int -> rawCode
        is Long -> rawCode.toInt().takeIf { it.toLong() == rawCode }
        else -> null
    }
}

internal fun ReplyToolCall.titleArgument(): String? {
    val key = when (name) {
        in shellToolNames -> "command"
        "search_web", "web_search_call", "web_search", "web_search_preview" -> "query"
        else -> return null
    }
    return (recordedObject(arguments)?.opt(key) as? String)?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun recordedObject(text: String?): JSONObject? = recordedContainer(text) as? JSONObject

internal fun recordedContainer(text: String?): Any? {
    if (text == null || text.length > ToolRecordText.limit) return null
    val first = text.firstOrNull { !it.isWhitespace() }
    if (first != '{' && first != '[') return null
    return try {
        JsonSyntax(text) { throw JSONException("Incomplete tool record") }.check()
        if (first == '{') JSONObject(text) else JSONArray(text)
    } catch (_: JSONException) { null }
}
