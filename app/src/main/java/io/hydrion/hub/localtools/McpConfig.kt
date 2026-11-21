package io.hydrion.hub.localtools

import java.io.File
import java.net.URI
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class McpDefinition(val name: String, val endpoint: String?) {
    init {
        require(name == name.trim() && name.length in 1..80 && name.none(Char::isISOControl))
        if (endpoint != null) {
            val url = URI(endpoint)
            require(endpoint.length <= 512 && url.scheme == "https" && !url.host.isNullOrBlank())
            require(url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null)
            require(url.port == -1 || url.port in 1..65535)
            // Imported endpoints can embed a bearer token in a path segment. Accept public routes only.
            require(url.rawPath.orEmpty().removeSuffix("/") in setOf("", "/mcp", "/v1/mcp", "/api/mcp"))
        }
    }
}

internal data class McpServer(
    val id: String, val definition: McpDefinition, val trusted: Boolean = false, val enabled: Boolean = false,
) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(!enabled || trusted)
    }
    val canTest: Boolean get() = trusted && enabled && definition.endpoint == null
}

internal data class McpCatalog(val servers: List<McpServer> = emptyList()) {
    init {
        require(servers.size <= 64 && servers.distinctBy { it.id }.size == servers.size)
        require(servers.count { it.definition.endpoint == null } <= 1)
    }

    fun add(definitions: List<McpDefinition>): McpCatalog {
        require(definitions.isNotEmpty())
        return McpCatalog(servers + definitions.map { McpServer(UUID.randomUUID().toString(), it) })
    }

    fun replace(id: String, definition: McpDefinition) = change(id) {
        require((it.definition.endpoint == null) == (definition.endpoint == null))
        if (it.definition == definition) it else McpServer(id, definition)
    }
    fun trust(id: String, trusted: Boolean) = change(id) { it.copy(trusted = trusted, enabled = it.enabled && trusted) }
    fun enable(id: String, enabled: Boolean) = change(id) { it.copy(enabled = enabled) }
    fun remove(id: String) = McpCatalog(servers.filterNot { it.id == id })

    private fun change(id: String, update: (McpServer) -> McpServer): McpCatalog {
        require(servers.any { it.id == id })
        return McpCatalog(servers.map { if (it.id == id) update(it) else it })
    }
}

/** Build an inert preview. Nothing is persisted, connected or enabled until the user acts. */
internal fun reviewMcpImport(input: String): List<McpDefinition> {
    require(input.length in 1..16_384 && input.count { it == '{' || it == '[' } <= 100)
    val reader = JSONTokener(input)
    val outer = reader.nextValue() as? JSONObject ?: throw IllegalArgumentException()
    require(reader.nextClean() == '\u0000')
    val configs = if (outer.has("mcpServers")) {
        require(outer.length() == 1)
        outer.getJSONObject("mcpServers")
    } else outer
    require(configs.length() in 1..32)
    return configs.keys().asSequence().map { name ->
        val config = configs.getJSONObject(name)
        require(config.keys().asSequence().all { it in setOf("type", "url", "baseUrl") })
        require(config.has("url") != config.has("baseUrl"))
        if (config.has("type")) require((config.get("type") as? String)?.lowercase() in
            setOf("http", "streamablehttp", "streamable-http"))
        val endpoint = config.get(if (config.has("url")) "url" else "baseUrl") as? String
        McpDefinition(name, requireNotNull(endpoint))
    }.toList()
}

internal fun mcpFile(root: File, owner: String) = AssistantFeatureFile(root, owner, "mcp", ::McpCatalog, { catalog ->
    JSONObject().put("servers", JSONArray(catalog.servers.map {
        JSONObject().put("id", it.id).put("name", it.definition.name)
            .put("transport", if (it.definition.endpoint == null) "fixture" else "http")
            .put("url", it.definition.endpoint).put("trusted", it.trusted).put("enabled", it.enabled)
    }))
}, { json ->
    val servers = json.getJSONArray("servers")
    require(servers.length() <= 64)
    McpCatalog(List(servers.length()) { i ->
        val row = servers.getJSONObject(i)
        val endpoint = when (row.getString("transport")) {
            "fixture" -> { require(!row.has("url")); null }
            "http" -> row.getString("url")
            else -> throw IllegalArgumentException()
        }
        McpServer(row.getString("id"), McpDefinition(row.getString("name"), endpoint),
            row.getBoolean("trusted"), row.getBoolean("enabled"))
    })
})
