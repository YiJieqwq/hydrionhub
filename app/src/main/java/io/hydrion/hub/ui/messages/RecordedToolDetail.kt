package io.hydrion.hub.ui.messages

import io.hydrion.hub.model.ReplyToolCall
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface RecordedToolDetail {
    class Shell(val command: String?, val directory: String?, val exit: Int?, val stdout: String?, val stderr: String?) : RecordedToolDetail
    class Search(val queries: List<String>, val summary: String?, val sources: List<RecordedSource>,
        val recordedEmpty: Boolean, val parameters: Map<String, String>) : RecordedToolDetail
}

internal class RecordedSource(val title: String?, val url: String?, val snippet: String?, val date: String?, val score: Number?)

private val searchNames = setOf("search_web", "web_search_call", "web_search", "web_search_preview", "builtin_search",
    "chat_search", "网络搜索 · 自定义搜索", "网络搜索 · 模型内置搜索")

/** Interpret the bounded, redacted display record only. Opaque older payloads keep their generic sheet. */
internal fun ReplyToolCall.recordedDetail(): RecordedToolDetail? {
    if (name !in shellToolNames && name !in searchNames) return null
    val args = recordedObject(arguments)
    val output = recordedContainer(result)
    if ((arguments != null && args == null) || (result != null && output == null)) return null
    if (name in searchNames) return searchDetail(args, output)
    if (output != null && output !is JSONObject) return null
    val fields = output as? JSONObject
    val command = args?.text("command")
    val exit = fields?.recordedExitCode(name)
    val stdout = (fields?.opt("stdout") as? String)?.takeIf { it.isNotEmpty() }
    val stderr = (fields?.opt("stderr") as? String)?.takeIf { it.isNotEmpty() }
    if (fields != null && exit == null && fields.opt("stdout") !is String && fields.opt("stderr") !is String) return null
    if (command == null && exit == null && stdout == null && stderr == null) return null
    return RecordedToolDetail.Shell(command, args?.text("cwd"), exit, stdout, stderr)
}

private fun searchDetail(args: JSONObject?, output: Any?): RecordedToolDetail.Search? {
    val fields = output as? JSONObject
    val queries = buildList {
        (args?.text("query") ?: fields?.text("query"))?.let(::add)
        args?.optJSONArray("queries")?.let { array ->
            for (i in 0 until array.length()) (array.opt(i) as? String)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }.distinct()
    val summary = fields?.text("answer") ?: fields?.text("summary")
    // Hosted Responses saves the source array itself. Search tools usually wrap it in a result object.
    val array = output as? JSONArray ?: fields?.optJSONArray("items") ?: fields?.optJSONArray("results") ?: fields?.optJSONArray("sources")
    val sources = buildList {
        if (array != null) for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val title = item.text("title")
            val url = item.text("url")
            val snippet = item.text("snippet") ?: item.text("content") ?: item.text("text")
            if (title != null || url != null || snippet != null) add(RecordedSource(title, url, snippet,
                item.text("publishedDate") ?: item.text("published_date"), item.opt("score") as? Number))
        }
    }
    val recordedEmpty = array?.length() == 0
    if (summary == null && sources.isEmpty() && !recordedEmpty) {
        if (queries.isEmpty() || output != null && fields?.text("query") == null) return null
    }
    val parameters = buildMap {
        if (args != null) for (key in args.keys()) {
            if (key != "query" && key != "queries") parameterText(args.opt(key))?.let { put(key, it) }
        }
        for (key in listOf("provider", "engine", "response_time")) {
            parameterText(fields?.opt(key))?.let { putIfAbsent(key, it) }
        }
    }
    return RecordedToolDetail.Search(queries, summary, sources, recordedEmpty, parameters)
}

private fun JSONObject.text(key: String) = (opt(key) as? String)?.takeIf { it.isNotBlank() }

private fun parameterText(value: Any?): String? = when (value) {
    is String -> value.takeIf { it.isNotBlank() }
    is Number, is Boolean -> value.toString()
    is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) as? String }
        .filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null }
    else -> null
}
