package io.hydrion.hub.model

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import org.json.JSONObject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun mergeBackup(current: SavedWorkspace, incoming: SavedWorkspace): SavedWorkspace {
    require(current.history.none { c -> c.messages.any { it.status.isActive } })
    val existing = current.history.map { it.id }.toSet() + listOfNotNull(current.unsent?.id)
    require(incoming.history.none { it.id in existing } && incoming.unsent?.id !in existing)
    require(incoming.unsent == null || current.unsent == null)
    return SavedWorkspace((current.history + incoming.history).sortedByDescending { it.updatedAt },
        current.drafts + incoming.drafts, current.unsent ?: incoming.unsent)
}

internal fun sameBackupBytes(a: File, b: File): Boolean {
    if (a.length() != b.length()) return false
    fun sha(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }
    return sha(a).contentEquals(sha(b))
}

internal fun installBackupFile(source: File, dest: File) {
    val temp = File.createTempFile(".incoming-", null, dest.parentFile)
    try {
        source.inputStream().use { input -> temp.outputStream().use { input.copyTo(it); it.fd.sync() } }
        Files.move(temp.toPath(), dest.toPath(), ATOMIC_MOVE)
    } finally { temp.delete() }
}

internal class LocalChatBackup(private val app: Context, private val images: GeneratedImageStore) {
    private val rollback = File(app.noBackupFilesDir, "chat-import-undo.json")
    private val importLock = Mutex()

    fun workDirectory(): File {
        val root = File(app.cacheDir, "chat-transfer")
        Files.createDirectories(root.toPath())
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        // Only clean temporary files owned by this feature, including abandoned document-picker sessions.
        val cutoff = System.currentTimeMillis() - 86_400_000L
        root.listFiles().orEmpty().filter { it.name.startsWith("stage-") &&
            Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) && it.lastModified() in 1 until cutoff }.forEach { dir ->
            dir.listFiles().orEmpty().filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }.forEach(File::delete)
            dir.delete()
        }
        return Files.createTempDirectory(root.toPath(), "stage-").toFile()
    }
    /** Reconnect a document picker result after Android recreates the settings screen. */
    fun preparedExport(name: String?): File? {
        if (name == null || !name.matches(Regex("stage-[A-Za-z0-9_-]+"))) return null
        val root = File(app.cacheDir, "chat-transfer")
        val dir = File(root, name)
        val file = File(dir, "chats.zip")
        return file.takeIf { Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) &&
            Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
    }

    fun hasRollback() = rollback.isFile

    suspend fun export(work: File, state: SavedWorkspace) = ChatArchive.export(work, state, { raw ->
        requireNotNull(app.contentResolver.openInputStream(Uri.parse(raw)))
    }, images::copyPng)

    suspend fun inspect(work: File, uri: Uri): InspectedChatArchive {
        require(uri.scheme == "content")
        val archive = requireNotNull(app.contentResolver.openInputStream(uri)).use { ChatArchive.inspect(work, it) }
        for ((key, sha) in archive.generated) images.checkArchivePng(key, archive.blobs.getValue(sha))
        return archive
    }

    /** Stage and verify media before the atomic history switch. Never overwrite existing originals. */
    suspend fun withMedia(
        archive: InspectedChatArchive,
        beforeWrite: (SavedWorkspace) -> Unit = {},
        commit: suspend (SavedWorkspace) -> Unit,
    ) = importLock.withLock {
        val dest = File(app.filesDir, "restored-attachments")
        val filesAdded = mutableListOf<File>()
        val imagesAdded = mutableListOf<String>()
        val picked = linkedMapOf<String, File>()
        val state = archive.restored({ sha ->
            val file = picked.getOrPut(sha) { File(dest, sha) }
            FileProvider.getUriForFile(app, app.packageName + ".images", file).toString()
        }, { it })
        beforeWrite(state)
        var committed = false
        try {
            Files.createDirectories(dest.toPath())
            require(Files.isDirectory(dest.toPath(), NOFOLLOW_LINKS))
            for ((key, sha) in archive.generated) {
                if (images.installArchivePng(key, archive.blobs.getValue(sha))) imagesAdded += key
            }
            for ((sha, file) in picked) {
                val source = archive.blobs.getValue(sha)
                if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && sameBackupBytes(source, file))
                } else {
                    installBackupFile(source, file)
                    filesAdded += file
                }
            }
            withContext(NonCancellable) { commit(state); committed = true }
        } finally {
            if (!committed) {
                images.discard(imagesAdded)
                filesAdded.forEach(File::delete)
            }
        }
    }

    fun stageHistory(target: File, state: SavedWorkspace): File =
        stage(target, encodeWorkspace(state))

    fun stageRollback(before: SavedWorkspace, after: SavedWorkspace): File =
        stage(rollback, JSONObject().put("before", encodeWorkspace(before)).put("after", encodeWorkspace(after)))

    private fun stage(target: File, json: JSONObject): File {
        val tmp = File.createTempFile(".chat-restore-", null, target.parentFile)
        try {
            tmp.outputStream().use { it.write(json.toString().toByteArray()); it.fd.sync() }
            return tmp
        } catch (failure: Throwable) { tmp.delete(); throw failure }
    }

    fun rollbackPoint(): Pair<SavedWorkspace, SavedWorkspace> {
        val json = JSONObject(rollback.readText())
        return decodeWorkspace(json.getJSONObject("before")) to decodeWorkspace(json.getJSONObject("after"))
    }

    fun publishRollback(file: File) = publishHistory(file, rollback)
    fun publishHistory(file: File, target: File) {
        Files.move(file.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
}
