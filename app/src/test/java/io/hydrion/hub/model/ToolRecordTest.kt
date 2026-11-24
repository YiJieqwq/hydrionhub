package io.hydrion.hub.model

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolRecordTest {
    private val marker = "fixture-header-value"
    private val privacy = ToolRecordText(mapOf("Authorization" to "Bearer $marker"))
    private val request = Message(role = Role.Assistant)

    @Test fun interleavedFunctionOutputsJoinOnlyTheirCallAndSecretsStayOutOfSnapshotsAndHistory() {
        val turn = ResponsesEventAccumulator(request, privacy)
        val snapshots = mutableListOf<Message>()
        fun event(type: String, json: JSONObject) {
            turn.accept(json.toString(), type).snapshot?.let { snapshots += it }
        }
        fun call(index: Int, id: String, data: JSONObject) = event("response.output_item.done",
            JSONObject().put("output_index", index).put("item", data.put("id", id)))
        fun fn(id: String) = JSONObject().put("type", "function_call").put("call_id", id).put("name", "lookup")
        fun output(id: String, data: String) = JSONObject().put("type", "function_call_output").put("call_id", id).put("output", data)
        call(0, "a", fn("first"))
        call(3, "out-b", output("second", "two"))
        call(1, "b", fn("second").put("arguments", """{"item":"lamp"}"""))
        event("response.function_call_arguments.delta", JSONObject().put("item_id", "a").put("delta", """{"key":"fixture-"""))
        event("response.function_call_arguments.delta", JSONObject().put("item_id", "a").put("delta", """secret","item":"book"}"""))
        assertNull(snapshots.last().toolCalls.first().arguments)
        call(0, "a", fn("first")) // Some streams finish the item without a separate arguments.done.
        call(2, "out-a", output("first", """{"label":"one","nested":[{"name":"Authorization","value":"private-header"}],"echo":"$marker"}"""))
        call(4, "orphan", output("not-a-call", "orphan"))
        event("response.completed", JSONObject().put("response", JSONObject().put("output",
            JSONArray().put(fn("second").put("id", "b")).put(fn("first").put("id", "a")))))
        val calls = snapshots.last().toolCalls
        assertEquals(listOf(0, 1), calls.map { it.slot })
        assertEquals(listOf(ToolCallState.Completed, ToolCallState.Completed), calls.map { it.state })
        assertEquals("book", JSONObject(calls[0].arguments!!).getString("item"))
        assertEquals("one", JSONObject(calls[0].result!!).getString("label"))
        assertEquals("lamp", JSONObject(calls[1].arguments!!).getString("item"))
        assertEquals("two", calls[1].result)
        for (msg in snapshots) {
            val stored = encodeWorkspace(SavedWorkspace(listOf(Conversation(messages = listOf(msg))))).toString()
            for (secret in listOf(marker, "fixture-secret", "private-header", "orphan")) assertFalse(stored.contains(secret))
            assertFalse(msg.toString().contains("lamp"))
        }
    }

    @Test fun parallelChatArgumentsAreCommittedOnlyAfterAssemblyEvenWhenTheLastFrameUsesAnId() = runBlocking {
        fun part(index: Int?, id: String, name: String?, args: String) =
            JSONObject().put("index", index).put("id", id).put("function", JSONObject().put("name", name).put("arguments", args))
        fun frame(calls: List<JSONObject>, finish: String? = null): String {
            val choice = JSONObject().put("index", 0).put("delta", JSONObject().put("tool_calls", JSONArray(calls)))
                .put("finish_reason", finish)
            return "data: " + JSONObject().put("choices", JSONArray().put(choice)).toString() + "\n\n"
        }
        val wire = frame(listOf(part(1, "b", "lookup", "{\"item\":\"la"), part(0, "a", "lookup", "{\"token\":\""))) +
            frame(listOf(part(null, "a", null, "fixture-secret\"}"), part(null, "b", null, "mp\"}")), "tool_calls") + "data: [DONE]\n\n"
        val seen = mutableListOf<Message>()
        try { readChatCompletionsEvents(wire.reader().buffered(), request, privacy) { seen += it }; fail() }
        catch (e: IOException) { assertTrue(e.message.orEmpty().contains("tool_calls")) }
        val calls = seen.last().toolCalls
        assertTrue(seen.dropLast(1).all { msg -> msg.toolCalls.all { it.arguments == null } })
        assertEquals(listOf("[redacted]", "lamp"), listOf(JSONObject(calls[0].arguments!!).getString("token"),
            JSONObject(calls[1].arguments!!).getString("item")))
        assertTrue(calls.all { it.name == "lookup" && it.state == ToolCallState.Requested && it.result == null })
    }

    @Test fun credentialFieldsUrlsAndOversizeFragmentsCannotBecomeStoredText() {
        val text = privacy.display(JSONObject().put("url", "https://alice:test@example.invalid/a?api_key=private-v&sig=fixture-signed&ok=1")
            .put("inner", """{"password":"private-p","note":"Bearer fixture-private"}""")
            .put("value", "ghp_fixtureKey").put("headers", JSONObject().put("X-Custom", "private-c"))
            .put("密码", "fixture-pwd").put("authEcho", "Basic c2FtcGxlMTIz").put("empty", JSONArray()))!!
        for (secret in listOf("private-v", "alice:test", "private-p", "fixture-private", "fixtureKey", "private-c", "fixture-pwd", "fixture-signed", "c2FtcGxlMTIz"))
            assertFalse(text, text.contains(secret))
        assertEquals(0, JSONObject(text).getJSONArray("empty").length())
        val fragments = ToolArgumentFragments()
        fragments.append("x".repeat(ToolRecordText.limit + 1))
        fragments.append("{}")
        assertNull(fragments.finish(privacy))
        assertNull(privacy.arguments("[] []"))
        assertNull(privacy.arguments("[".repeat(2000) + "]".repeat(2000)))
        assertNull(privacy.arguments("""{"unfinished":"private"""))
    }
}
