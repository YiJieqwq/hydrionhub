package io.hydrion.hub.model

import io.hydrion.hub.R
import io.hydrion.hub.provider.ChatProtocol
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.chatProtocol
import org.json.JSONArray
import org.json.JSONObject

internal fun ProviderModel.canAskUser(provider: Provider): Boolean =
    "tools" in profile.abilities && (profile.budget ?: 2) >= 2 &&
        provider.chatProtocol() in setOf(ChatProtocol.ChatCompletionsHttp, ChatProtocol.ResponsesHttp) &&
        (profile.requestBody.isBlank() || runCatching { JSONObject(profile.requestBody).optString("tool_choice") != "none" }.getOrDefault(false))

internal fun JSONObject.offerQuestions(completions: Boolean, encryptedReasoning: Boolean) {
    val question = JSONObject("""
        {"type":"object","properties":{
          "id":{"type":"string"},"question":{"type":"string"},
          "type":{"type":"string","enum":["single","multi"]},
          "options":{"type":"array","items":{"type":"string"}}
        },"required":["id","question"]}
    """)
    val list = JSONObject().put("type", "array").put("items", question).put("minItems", 1).put("maxItems", 4)
    val schema = JSONObject().put("type", "object").put("properties", JSONObject().put("questions", list))
        .put("required", JSONArray().put("questions"))
    val function = JSONObject().put("name", ASK_USER).put("strict", false).put("parameters", schema)
        .put("description", "Request a decision from the person in this chat. Give each question a distinct id. Omit options for a written answer.")
    val tool = if (completions) JSONObject().put("function", function) else function
    put("tools", JSONArray().put(tool.put("type", "function")))
    if (encryptedReasoning) {
        val include = optJSONArray("include") ?: JSONArray()
        if ((0 until include.length()).none { include.optString(it) == "reasoning.encrypted_content" }) include.put("reasoning.encrypted_content")
        put("include", include)
    }
}

internal class WireQuestion(val id: String, val slot: Int, val arguments: String) {
    init {
        if (id.isBlank() || id.length > 256 || id.any(Char::isISOControl) || slot < 0 || arguments.length > ToolRecordText.limit)
            throw AskUserFailure(R.string.ask_invalid)
    }
}

/** Transient protocol data for one complete response. Nothing here is written to chat history or diagnostics. */
internal class AskWireRound {
    var calls: List<WireQuestion> = emptyList()
        private set
    private var replay = JSONArray()

    fun responses(output: JSONArray?, slotFor: (String) -> Int?, expected: Int) {
        val items = output ?: throw AskUserFailure(R.string.ask_invalid)
        val requested = mutableListOf<WireQuestion>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: throw AskUserFailure(R.string.ask_invalid)
            when (item.optString("type")) {
                "message" -> if (item.opt("role") != "assistant") throw AskUserFailure(R.string.ask_invalid)
                "reasoning" -> Unit
                "function_call" -> {
                    val id = item.opt("call_id") as? String ?: throw AskUserFailure(R.string.ask_invalid)
                    if (item.opt("name") != ASK_USER || item.optString("status", "completed") != "completed")
                        throw AskUserFailure(R.string.ask_invalid)
                    requested += WireQuestion(id, slotFor(id) ?: throw AskUserFailure(R.string.ask_invalid),
                        item.opt("arguments") as? String ?: throw AskUserFailure(R.string.ask_invalid))
                }
                else -> throw AskUserFailure(R.string.ask_invalid)
            }
        }
        if (requested.size != expected) throw AskUserFailure(R.string.ask_invalid)
        record(requested, items)
    }

    fun completions(requested: List<WireQuestion>, message: Message) {
        val functions = JSONArray()
        for (call in requested) {
            functions.put(JSONObject().put("id", call.id).put("type", "function").put("function",
                JSONObject().put("name", ASK_USER).put("arguments", call.arguments)))
        }
        val assistant = JSONObject().put("role", "assistant").put("content", message.text.takeIf(String::isNotEmpty) ?: JSONObject.NULL)
            .put("tool_calls", functions)
        if (message.thought.isNotEmpty()) assistant.put("reasoning_content", message.thought)
        record(requested, JSONArray().put(assistant))
    }

    private fun record(requested: List<WireQuestion>, items: JSONArray) {
        if (calls.isNotEmpty() || requested.size !in 1..16 || requested.distinctBy { it.id }.size != requested.size ||
            requested.distinctBy { it.slot }.size != requested.size) throw AskUserFailure(R.string.ask_invalid)
        calls = requested
        replay = items
    }

    fun continueBody(previous: JSONObject, answers: Map<String, JSONObject>, completions: Boolean) =
        JSONObject(previous.toString()).apply {
            check(answers.keys == calls.map { it.id }.toSet())
            val input = getJSONArray(if (completions) "messages" else "input")
            for (i in 0 until replay.length()) input.put(replay.get(i))
            for (call in calls) {
                val content = answers.getValue(call.id).toString()
                input.put(if (completions) JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", content)
                    else JSONObject().put("type", "function_call_output").put("call_id", call.id).put("output", content))
            }
        }
}
