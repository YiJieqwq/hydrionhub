package io.hydrion.hub.localtools

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpConfigTest {
    @get:Rule val dir = TemporaryFolder()
    private val remote = McpDefinition("Example", "https://example.invalid/mcp")
    private val fixture = McpDefinition("Local", null)

    @Test fun importIsAReviewOnlyAndAddsFreshDisabledRecordsWithoutOverwriting() {
        val old = McpCatalog().add(listOf(remote))
        val imported = reviewMcpImport("""{"mcpServers":{"Example":{"type":"streamable-http","url":"https://example.invalid/mcp"}}}""")
        assertEquals(listOf(remote), imported)
        val added = old.add(imported)
        assertEquals(old.servers.first(), added.servers.first())
        assertNotEquals(added.servers.first().id, added.servers.last().id)
        assertFalse(added.servers.last().trusted || added.servers.last().enabled)
        val direct = reviewMcpImport("""{"Example":{"baseUrl":"https://example.invalid/mcp"}}""")
        assertEquals(imported, direct)
    }

    @Test fun rejectsCommandsCredentialsAutoTrustAndUnsupportedTransportsAsOneInvalidImport() {
        val invalid = listOf(
            """{"type":"stdio","command":"unused"}""", """{"url":"https://x.invalid","headers":{"Authorization":"synthetic"}}""",
            """{"url":"https://x.invalid","env":{}}""", """{"type":"sse","url":"https://x.invalid"}""",
            """{"url":"https://x.invalid","enabled":true}""", """{"url":"https://user@x.invalid/mcp"}""",
            """{"url":"https://x.invalid/mcp?token=synthetic"}""", """{"url":"https://x.invalid/mcp#frag"}""",
            """{"url":"http://x.invalid/mcp"}""", """{"url":"https://x.invalid/tokens/synthetic"}""",
            """{"type":"fixture","url":"https://x.invalid"}""", """{"url":123}""")
        for (row in invalid) {
            val source = """{"valid":{"url":"https://example.invalid/mcp"},"other":$row}"""
            assertTrue(row, runCatching { reviewMcpImport(source) }.isFailure)
        }
        assertTrue(runCatching { reviewMcpImport("""{"x":{"url":"https://example.invalid/mcp"}} trailing""") }.isFailure)
        assertTrue(runCatching { reviewMcpImport("[".repeat(1024)) }.isFailure)
    }

    @Test fun assistantFileSurvivesRestartAndGrantsDoNotTransfer() = runBlocking {
        val root = dir.root
        val a = mcpFile(root, "assistant/a")
        val b = mcpFile(root, "assistant/b")
        val created = a.update { it.add(listOf(fixture, remote)) }
        val id = created.servers.first().id
        assertTrue(runCatching { a.update { it.enable(id, true) } }.isFailure)
        val active = a.update { it.trust(id, true).enable(id, true) }
        assertEquals(active, mcpFile(root, "assistant/a").read())
        assertTrue(b.read().servers.isEmpty())
        assertTrue(runCatching { a.update { it.add(listOf(fixture)) } }.isFailure)
        val renamed = a.update { it.replace(id, McpDefinition("Edited", null)) }
        assertFalse(renamed.servers.first().canTest)
        assertFalse(renamed.servers.first().trusted)
        val revoked = a.update { it.trust(id, true).enable(id, true).trust(id, false) }
        assertFalse(revoked.servers.first().enabled)
        assertEquals(listOf(remote), a.update { it.remove(id) }.servers.map { it.definition })
        assertTrue(b.read().servers.isEmpty())
    }

    @Test fun fixtureDiscoveryRequiresActiveLocalServerAndAnExplicitSingleCallInSameScope() {
        val catalog = McpCatalog().add(listOf(fixture, remote))
        val id = catalog.servers.first().id
        val local = catalog.trust(id, true).enable(id, true).servers.first()
        val remoteId = catalog.servers.last().id
        val enabledRemote = catalog.trust(remoteId, true).enable(remoteId, true).servers.last()
        for (server in listOf(catalog.servers.first(), local.copy(enabled = false), enabledRemote))
            assertTrue(runCatching { McpFixtureReview.discover("A", server) }.isFailure)
        val review = McpFixtureReview.discover("A", local)
        assertEquals("ping", review.tool)
        assertTrue(runCatching { review.confirm("B", local) }.isFailure)
        assertTrue(runCatching { review.confirm("A", null) }.isFailure)
        assertTrue(runCatching { review.confirm("A", local.copy(enabled = false)) }.isFailure)
        assertEquals("pong", review.confirm("A", local))
        assertTrue(runCatching { review.confirm("A", local) }.isFailure)
    }

    @Test fun jsonRpcNegotiatesAndListsOnlyAfterReadyAndRejectsUnknownCalls() {
        val server = LocalMcpFixture()
        fun request(method: String, params: JSONObject = JSONObject()) = server.receive(JSONObject().put("jsonrpc", "2.0")
            .put("id", "id-from-client").put("method", method).put("params", params))!!
        assertEquals(-32600, request("tools/list").getJSONObject("error").getInt("code"))
        val hello = request("initialize", JSONObject().put("protocolVersion", "other").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "local-client").put("version", "1")))
        assertEquals("2025-11-25", hello.getJSONObject("result").getString("protocolVersion"))
        assertEquals("id-from-client", hello.getString("id"))
        assertTrue(hello.getJSONObject("result").getJSONObject("capabilities").has("tools"))
        assertTrue(request("tools/list").has("error"))
        assertNull(server.receive(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")))
        val tool = request("tools/list").getJSONObject("result").getJSONArray("tools").getJSONObject(0)
        assertEquals("ping", tool.getString("name"))
        assertEquals("object", tool.getJSONObject("inputSchema").getString("type"))
        assertEquals(-32602, request("tools/call", JSONObject().put("name", "unknown")).getJSONObject("error").getInt("code"))
        assertEquals(-32602, request("tools/call", JSONObject().put("name", "ping").put("arguments", JSONObject().put("x", 1))).getJSONObject("error").getInt("code"))
        assertEquals(-32601, request("custom").getJSONObject("error").getInt("code"))
    }
}
