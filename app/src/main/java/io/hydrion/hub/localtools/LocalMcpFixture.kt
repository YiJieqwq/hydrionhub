package io.hydrion.hub.localtools

import org.json.JSONArray
import org.json.JSONObject

private const val fixtureVersion = "2025-11-25"

/** In-process JSON-RPC peer. It has one compiled-in, side-effect-free tool and no I/O. */
internal class LocalMcpFixture {
    private enum class Phase { New, Negotiated, Ready }
    private var phase = Phase.New

    fun receive(message: JSONObject): JSONObject? {
        val id = message.opt("id")
        fun error(code: Int, text: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", text))
        val method = message.opt("method") as? String ?: return error(-32600, "Invalid request")
        if (message.opt("jsonrpc") != "2.0" || (id != null && id !is String && id !is Number))
            return error(-32600, "Invalid request")
        if (id == null) {
            if (method == "notifications/initialized" && phase == Phase.Negotiated) phase = Phase.Ready
            return null
        }

        val params = message.optJSONObject("params")
        val result = when (method) {
            "initialize" -> {
                if (phase != Phase.New) return error(-32600, "Already initialized")
                val client = params?.optJSONObject("clientInfo")
                if (params?.opt("protocolVersion") !is String || params.optJSONObject("capabilities") == null ||
                    client?.opt("name") !is String || client.opt("version") !is String)
                    return error(-32602, "Invalid parameters")
                phase = Phase.Negotiated
                JSONObject().put("protocolVersion", fixtureVersion)
                    .put("serverInfo", JSONObject().put("name", "hydrion-local-fixture").put("version", "1"))
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
            }
            "tools/list" -> {
                if (phase != Phase.Ready) return error(-32600, "Not initialized")
                val schema = JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false)
                JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "ping").put("inputSchema", schema)))
            }
            "tools/call" -> {
                if (phase != Phase.Ready) return error(-32600, "Not initialized")
                if (params?.opt("name") != "ping" ||
                    (params.has("arguments") && params.optJSONObject("arguments")?.length() != 0))
                    return error(-32602, "Unknown tool or arguments")
                JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "pong")))
            }
            else -> return error(-32601, "Method not found")
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }
}

/** Review one discovery, then authorize one call. Discard when leaving the assistant or changing the record. */
internal class McpFixtureReview private constructor(
    private val owner: String, private val server: McpServer, private val fixture: LocalMcpFixture, val tool: String,
) {
    private var consumed = false

    fun confirm(currentOwner: String, current: McpServer?): String {
        check(!consumed && currentOwner == owner && current == server && server.canTest)
        consumed = true
        val result = fixture.request(3, "tools/call", JSONObject().put("name", tool).put("arguments", JSONObject()))
        return result.getJSONArray("content").getJSONObject(0).getString("text")
    }

    companion object {
        fun discover(owner: String, server: McpServer): McpFixtureReview {
            require(owner.isNotBlank() && server.canTest)
            val fixture = LocalMcpFixture()
            val hello = fixture.request(1, "initialize", JSONObject().put("protocolVersion", fixtureVersion)
                .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "HydrionHub").put("version", "1")))
            check(hello.getString("protocolVersion") == fixtureVersion && hello.getJSONObject("capabilities").has("tools"))
            fixture.receive(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
            val tool = fixture.request(2, "tools/list", JSONObject()).getJSONArray("tools").getJSONObject(0).getString("name")
            check(tool == "ping")
            return McpFixtureReview(owner, server, fixture, tool)
        }
    }
}

private fun LocalMcpFixture.request(id: Int, method: String, params: JSONObject): JSONObject {
    val reply = requireNotNull(receive(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)))
    check(reply.getInt("id") == id && reply.getString("jsonrpc") == "2.0")
    return reply.getJSONObject("result")
}
