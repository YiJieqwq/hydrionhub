package io.hydrion.hub.ui.messages

import io.hydrion.hub.json.JsonSyntax
import io.hydrion.hub.model.ReplyToolCall
import io.hydrion.hub.model.ToolRecordText
import org.json.JSONException
import org.json.JSONObject

/** Present only typed fields from the already redacted transcript. The detail sheet keeps the full record. */
internal class ShellPreview(val exitCode: Int?, val stdout: String?)

internal fun ReplyToolCall.shellPreview(): ShellPreview? {
    if (name !in shellToolNames) return null
    val fields = recordedObject(result) ?: return null
    val rawCode = fields.opt("exitCode") ?: fields.opt("exit_code")
        ?: if (name == "bash_code_execution") fields.opt("return_code") else null
    val code = when (rawCode) {
        is Int -> rawCode
        is Long -> rawCode.toInt().takeIf { it.toLong() == rawCode }
        else -> null
    }
    val stdout = (fields.opt("stdout") as? String)?.takeIf { it.isNotEmpty() }
    return if (code != null || stdout != null) ShellPreview(code, stdout) else null
}

internal fun ReplyToolCall.titleArgument(): String? {
    val key = when (name) {
        in shellToolNames -> "command"
        "search_web", "web_search_call", "web_search", "web_search_preview" -> "query"
        else -> return null
    }
    return (recordedObject(arguments)?.opt(key) as? String)?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun recordedObject(text: String?): JSONObject? {
    if (text == null || text.length > ToolRecordText.limit || !text.trimStart().startsWith('{')) return null
    return try {
        JsonSyntax(text) { throw JSONException("Incomplete recorded object") }.check()
        JSONObject(text)
    } catch (_: JSONException) { null }
}
