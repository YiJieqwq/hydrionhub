package io.hydrion.hub.localtools

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class NoteType { Identity, Workflow, Voice, Instruction }
internal data class MemoryNote(val id: String, val text: String, val type: NoteType, val archived: Boolean, val updated: Long)

internal data class AssistantMemory(val enabled: Boolean = false, val notes: List<MemoryNote> = emptyList()) {
    fun search(query: String, archived: Boolean = false): List<MemoryNote> {
        if (!enabled) return emptyList()
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return notes.filter { note -> note.archived == archived && terms.all { note.text.contains(it, ignoreCase = true) } }
            .sortedByDescending { it.updated }
    }

    fun save(id: String?, text: String, type: NoteType): AssistantMemory {
        val content = text.trim()
        require(enabled && validNote(content))
        val now = System.currentTimeMillis()
        if (id == null) {
            require(notes.size < 256)
            return copy(notes = notes + MemoryNote(UUID.randomUUID().toString(), content, type, false, now))
        }
        require(notes.any { it.id == id })
        return copy(notes = notes.map { if (it.id == id) it.copy(text = content, type = type, updated = now) else it })
    }

    fun archive(id: String, archived: Boolean): AssistantMemory {
        require(enabled)
        return copy(notes = notes.map { if (it.id == id) it.copy(archived = archived, updated = System.currentTimeMillis()) else it })
    }

    fun delete(id: String): AssistantMemory {
        require(enabled)
        return copy(notes = notes.filterNot { it.id == id })
    }
}

internal fun validNote(text: String): Boolean = text.isNotBlank() && text.length <= 4000 &&
    text.none { it.isISOControl() && it !in "\n\r\t" }

internal fun memoryFile(root: File, owner: String) = AssistantFeatureFile(root, owner, "memory", ::AssistantMemory, { memory ->
    JSONObject().put("enabled", memory.enabled).put("notes", JSONArray(memory.notes.map {
        JSONObject().put("id", it.id).put("text", it.text).put("type", it.type.name)
            .put("archived", it.archived).put("updated", it.updated)
    }))
}, { json ->
    val rows = json.getJSONArray("notes")
    require(rows.length() <= 256)
    val notes = List(rows.length()) { i -> rows.getJSONObject(i).let {
        MemoryNote(it.getString("id"), it.getString("text"), NoteType.valueOf(it.getString("type")),
            it.getBoolean("archived"), it.getLong("updated"))
    } }
    require(notes.all { it.id.isNotEmpty() && validNote(it.text) } && notes.distinctBy { it.id }.size == notes.size)
    AssistantMemory(json.getBoolean("enabled"), notes)
})
