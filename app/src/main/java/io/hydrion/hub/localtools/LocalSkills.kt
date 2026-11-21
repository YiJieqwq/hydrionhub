package io.hydrion.hub.localtools

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal const val SkillByteLimit = 64 * 1024

internal data class SkillDocument private constructor(
    val name: String, val description: String, val source: String, val digest: String,
) {
    companion object {
        private val namePattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

        fun read(input: InputStream): SkillDocument {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(count > 0 && bytes.size() + count <= SkillByteLimit)
                bytes.write(buffer, 0, count)
            }
            return parse(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString())
        }

        /** Only name and description are supported. The body is always plain text. */
        fun parse(raw: String): SkillDocument {
            require(raw.length <= SkillByteLimit)
            val source = raw.removePrefix("\uFEFF").replace("\r\n", "\n")
            val bytes = source.toByteArray()
            require(bytes.size <= SkillByteLimit && source.none { it.isISOControl() && it !in "\n\t" })
            val lines = source.split('\n')
            require(lines.first().trimEnd() == "---")
            val end = (1 until minOf(lines.size, 80)).firstOrNull { lines[it] == "---" }
            require(end != null && lines.drop(end + 1).any { it.isNotBlank() })
            val fields = mutableMapOf<String, String>()
            var index = 1
            while (index < end) {
                val line = lines[index++]
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                val split = line.indexOf(':')
                require(split > 0)
                val key = line.substring(0, split)
                require(key in setOf("name", "description") && key !in fields)
                var value = line.substring(split + 1).trim()
                if (key == "description" && value in setOf(">", "|", ">-", "|-")) {
                    val parts = mutableListOf<String>()
                    while (index < end && (lines[index].startsWith(" ") || lines[index].isBlank())) {
                        parts += lines[index++].trim()
                    }
                    value = parts.joinToString(if (value.startsWith(">")) " " else "\n").trim()
                } else if (value.length >= 2 && value.first() in "\"'" && value.last() == value.first()) {
                    value = value.substring(1, value.lastIndex)
                }
                fields[key] = value
            }
            val name = requireNotNull(fields["name"])
            val description = requireNotNull(fields["description"])
            require(name.length <= 64 && namePattern.matches(name))
            require(description.isNotBlank() && description.length <= 1024)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return SkillDocument(name, description, source, digest)
        }
    }
}

internal data class SkillEntry(val document: SkillDocument, val approvedDigest: String? = null, val enabled: Boolean = false) {
    val trusted get() = approvedDigest == document.digest
}

internal data class LocalSkills(val entries: List<SkillEntry> = emptyList()) {
    fun install(document: SkillDocument, expectedOldDigest: String?): LocalSkills {
        val old = entries.find { it.document.name == document.name }
        require(old?.document?.digest == expectedOldDigest)
        if (old?.document?.digest == document.digest) return this
        require(old != null || entries.size < 48)
        return if (old == null) copy(entries = entries + SkillEntry(document))
            else copy(entries = entries.map { if (it == old) SkillEntry(document) else it })
    }

    fun approve(name: String, reviewedDigest: String) = change(name, reviewedDigest) {
        it.copy(approvedDigest = reviewedDigest)
    }

    fun enable(name: String, digest: String, on: Boolean) = change(name, digest) {
        require(!on || it.trusted)
        it.copy(enabled = on)
    }

    fun revoke(name: String, digest: String) = change(name, digest) { SkillEntry(it.document) }

    fun remove(name: String, digest: String): LocalSkills {
        val existing = entries.single { it.document.name == name && it.document.digest == digest }
        return copy(entries = entries - existing)
    }

    fun instructions(name: String): String? = entries.find {
        it.document.name == name && it.enabled && it.trusted
    }?.document?.source

    private fun change(name: String, digest: String, edit: (SkillEntry) -> SkillEntry): LocalSkills {
        val current = entries.single { it.document.name == name && it.document.digest == digest }
        return copy(entries = entries.map { if (it == current) edit(it) else it })
    }
}

internal fun skillFile(root: File, owner: String) = AssistantFeatureFile(root, owner, "skills", { LocalSkills() }, { catalog ->
    JSONObject().put("entries", JSONArray(catalog.entries.map {
        JSONObject().put("source", it.document.source).put("approved", it.approvedDigest ?: JSONObject.NULL).put("on", it.enabled)
    }))
}, { json ->
    val rows = json.getJSONArray("entries")
    require(rows.length() <= 48)
    val entries = List(rows.length()) { i ->
        val row = rows.getJSONObject(i)
        val doc = SkillDocument.parse(row.getString("source"))
        val digest = if (row.isNull("approved")) null else row.getString("approved").takeIf { it == doc.digest }
        SkillEntry(doc, digest, digest != null && row.getBoolean("on"))
    }
    require(entries.distinctBy { it.document.name }.size == entries.size)
    LocalSkills(entries)
})
