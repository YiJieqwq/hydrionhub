package io.hydrion.hub.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun isRuntimeId(value: String): Boolean =
    value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

internal val rootfsDigest = Regex("[0-9a-f]{64}")

internal fun isGuestPath(value: String): Boolean =
    value.length in 2..4095 && value.startsWith('/') && Charsets.UTF_8.newEncoder().canEncode(value) &&
        value.toByteArray(Charsets.UTF_8).size < 4096 && value.substring(1).split('/').all { part ->
            part.isNotEmpty() && part != "." && part != ".." && part.none { it == '\\' || it.isISOControl() }
        }

internal fun validRuntimeName(name: String): Boolean =
    name.isNotBlank() && name.length <= 64 && name == name.trim() && name.none(Char::isISOControl)

internal data class ProotContainer(val id: String, val rootfsSha256: String?, val name: String = "") {
    init {
        require(isRuntimeId(id)) { "容器 ID 须为规范 UUID。" }
        require(name.isEmpty() || validRuntimeName(name))
        require(rootfsSha256 == null || rootfsDigest.matches(rootfsSha256)) { "容器镜像须有有效的 SHA-256 摘要。" }
    }
}

internal data class ProotWorkspace(val id: String) {
    init { require(isRuntimeId(id)) { "工作区 ID 须为规范 UUID。" } }
}

internal data class ProotEnvironment(
    val id: String,
    val containerId: String,
    val workspaceId: String = id,
    val guestCwd: String = "/workspace",
    val name: String = ""
) {
    init {
        require(name.isEmpty() || validRuntimeName(name))
        require(listOf(id, containerId, workspaceId).all(::isRuntimeId)) { "环境及其引用须为规范 UUID。" }
        require(isGuestPath(guestCwd) && (guestCwd == "/workspace" || guestCwd.startsWith("/workspace/"))) {
            "环境工作目录须位于 /workspace 中。"
        }
    }
}

internal data class ProotCatalog(
    val containers: List<ProotContainer> = emptyList(),
    val workspaces: List<ProotWorkspace> = emptyList(),
    val environments: List<ProotEnvironment> = emptyList()
) {
    init {
        val containerIds = containers.map { it.id }.toSet()
        val workspaceIds = workspaces.map { it.id }.toSet()
        require(containerIds.size == containers.size && workspaceIds.size == workspaces.size &&
            environments.map { it.id }.toSet().size == environments.size) { "运行环境目录含重复的 ID。" }
        require(environments.all { it.containerId in containerIds && it.workspaceId in workspaceIds }) {
            "运行环境引用了未登记的容器或工作区。"
        }
    }

    fun withContainer(container: ProotContainer): ProotCatalog {
        if (container in containers) return this
        require(containers.isEmpty()) { "当前版本最多可登记一个容器。" }
        return copy(containers = containers + container)
    }

    fun withWorkspace(workspace: ProotWorkspace) =
        if (workspace in workspaces) this else copy(workspaces = workspaces + workspace)

    fun withEnvironment(environment: ProotEnvironment) =
        if (environment in environments) this else copy(environments = environments + environment)

    fun editContainer(id: String, name: String, digest: String?): ProotCatalog {
        require(validRuntimeName(name) && containers.any { it.id == id })
        return copy(containers = containers.map { if (it.id == id) it.copy(name = name, rootfsSha256 = digest) else it })
    }

    fun renameEnvironment(id: String, name: String): ProotCatalog {
        require(validRuntimeName(name) && environments.any { it.id == id })
        return copy(environments = environments.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1)
        .put("containers", JSONArray().apply {
            containers.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).apply {
                    if (it.rootfsSha256 != null) put("rootfsSha256", it.rootfsSha256) else put("awaitingImage", true)
                })
            }
        })
        .put("workspaces", JSONArray().apply { workspaces.forEach { put(JSONObject().put("id", it.id)) } })
        .put("environments", JSONArray().apply {
            environments.forEach {
                put(JSONObject().put("id", it.id).put("containerId", it.containerId)
                    .put("workspaceId", it.workspaceId).put("guestCwd", it.guestCwd).put("name", it.name))
            }
        })

    companion object {
        fun fromJson(source: JSONObject): ProotCatalog {
            require(source.getInt("version") == 1) { "运行环境目录版本不受支持。" }
            fun <T> rows(name: String, decode: (JSONObject) -> T): List<T> {
                val records = source.getJSONArray(name)
                return List(records.length()) { decode(records.getJSONObject(it)) }
            }
            // Existing configurations may reference several containers. Only new registrations are limited.
            return ProotCatalog(
                rows("containers") {
                    val digest = if (!it.has("rootfsSha256") && it.optBoolean("awaitingImage")) null
                        else it.getString("rootfsSha256")
                    ProotContainer(it.getString("id"), digest, it.optString("name"))
                },
                rows("workspaces") { ProotWorkspace(it.getString("id")) },
                rows("environments") { ProotEnvironment(it.getString("id"), it.getString("containerId"),
                    it.getString("workspaceId"), it.getString("guestCwd"), it.optString("name")) }
            )
        }
    }
}
