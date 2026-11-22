package io.hydrion.hub.model

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** A process pins its stores for its whole lifetime. Only a cold start observes a confirmed replacement. */
internal class AppRestoreStore(private val root: File, private val switchPointer: (File, File) -> Unit = { from, to ->
    Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
}) {
    private val pointer = File(root, "current")
    val generation = readGeneration()
    val directory = generation?.let { File(root, it) }
    val previousBackup get() = directory?.let { File(it, "previous.zip") }?.takeIf { it.isFile }
    fun history(files: File) = File(directory ?: files, "conversation-history-v1.json")
    fun preference(name: String) = generation?.let { "${name}_$it" } ?: name
    fun pendingRestart() = readGeneration() != generation

    private fun readGeneration(): String? {
        if (Files.notExists(pointer.toPath(), NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(pointer.toPath(), NOFOLLOW_LINKS) && pointer.length() == 36L)
        return pointer.readText().also {
            require(UUID.fromString(it).toString() == it && Files.isDirectory(File(root, it).toPath(), NOFOLLOW_LINKS))
        }
    }

    fun stage(): File {
        Files.createDirectories(root.toPath())
        return Files.createTempDirectory(root.toPath(), "stage-").toFile()
    }

    fun prepare(stage: File, state: SavedWorkspace, settings: AppBackupSettings, rollback: File) {
        File(stage, "conversation-history-v1.json").writeSynced(encodeWorkspace(state).toString().toByteArray())
        File(stage, "settings.json").writeSynced(settings.encode().toString().toByteArray())
        rollback.inputStream().use { input -> File(stage, "previous.zip").outputStream().use { out ->
            input.copyTo(out); out.fd.sync()
        } }
    }

    fun initialSettings() = directory?.let {
        AppBackupSettings.decode(org.json.JSONObject(File(it, "settings.json").readText()))
    }

    /** Stage all fallible work first. Rename of the pointer is the sole commit point. */
    fun publish(stage: File) = synchronized(publicationLock) {
        check(!pendingRestart())
        require(stage.parentFile == root && stage.name.startsWith("stage-"))
        require(listOf("conversation-history-v1.json", "settings.json", "previous.zip").all {
            Files.isRegularFile(File(stage, it).toPath(), NOFOLLOW_LINKS)
        })
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        val next = File.createTempFile("pointer-", ".tmp", root)
        try {
            next.outputStream().use { it.write(id.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            Files.move(stage.toPath(), directory.toPath(), ATOMIC_MOVE)
            try {
                switchPointer(next, pointer)
            } catch (failure: Exception) {
                directory.deleteRecursively()
                throw failure
            }
        } finally { next.delete() }
    }

    fun cancelPending() = synchronized(publicationLock) {
        check(pendingRestart())
        if (generation == null) Files.delete(pointer.toPath()) else {
            val next = File.createTempFile("pointer-", ".tmp", root)
            try {
                next.outputStream().use { it.write(generation.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
                switchPointer(next, pointer)
            } finally { next.delete() }
        }
    }

    /** Old restores are owned by this feature. The current generation contains its own portable rollback ZIP. */
    fun reclaim(context: Context) = synchronized(publicationLock) {
        if (pendingRestart()) return@synchronized
        val expiry = System.currentTimeMillis() - 86_400_000L
        for (dir in root.listFiles().orEmpty()) {
            if (!Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS) || dir == directory) continue
            if (runCatching { UUID.fromString(dir.name).toString() == dir.name }.getOrDefault(false)) {
                dir.deleteRecursively()
                for (name in listOf("model_catalog_v1", "message_style")) context.deleteSharedPreferences("${name}_${dir.name}")
            } else if (dir.name.startsWith("stage-") && dir.lastModified() < expiry) dir.deleteRecursively()
        }
    }

    companion object {
        private val publicationLock = Any()
        private val processes = mutableMapOf<File, AppRestoreStore>()
        @Synchronized fun current(context: Context): AppRestoreStore {
            val root = File(context.applicationContext.noBackupFilesDir, "app-restore-v1")
            return processes.getOrPut(root) { AppRestoreStore(root) }
        }
    }
}

internal fun File.writeSynced(bytes: ByteArray) = outputStream().use { it.write(bytes); it.fd.sync() }
