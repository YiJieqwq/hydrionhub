package io.hydrion.hub.localtools

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Private, versioned file for a single assistant and feature. Never included in a chat export. */
internal class AssistantFeatureFile<T>(
    directory: File, assistantId: String, feature: String,
    private val empty: () -> T, private val encode: (T) -> JSONObject, private val decode: (JSONObject) -> T,
) {
    init { require(assistantId.isNotBlank() && feature in setOf("memory", "mcp", "skills")) }
    private val key = MessageDigest.getInstance("SHA-256").digest(assistantId.toByteArray()).joinToString("") { "%02x".format(it) }
    private val file = File(File(directory, key), "$feature.json")

    private fun load(): T {
        if (!file.exists()) return empty()
        require(file.isFile && file.length() in 1..Limit.toLong())
        val json = JSONObject(file.readText())
        require(json.getInt("schema") == 1)
        return decode(json.getJSONObject("data"))
    }

    suspend fun read(): T = withContext(Dispatchers.IO) { lock.withLock { load() } }

    suspend fun update(change: (T) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val latest = load()
            val next = change(latest)
            if (next == latest) return@withLock latest
            val bytes = JSONObject().put("schema", 1).put("data", encode(next)).toString().toByteArray()
            require(bytes.size <= Limit)
            val parent = requireNotNull(file.parentFile)
            Files.createDirectories(parent.toPath())
            val temp = File.createTempFile("save-", ".tmp", parent)
            try {
                FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            next
        }
    }

    private companion object {
        val lock = Mutex()
        const val Limit = 4 * 1024 * 1024
    }
}
