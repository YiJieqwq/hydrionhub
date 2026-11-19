package io.hydrion.hub.runtime

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** App-private configuration only. No external path, command, permission, or mount comes from the catalog. */
internal class ProotEnvironmentStore(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "proot-environments-v1.json")
    private val catalogFile = AtomicFile(file)
    private val files = ProotFiles(app.filesDir, app.applicationInfo.nativeLibraryDir?.let(::File))

    suspend fun getEnvironment(id: String): ProotEnvironment? = withContext(Dispatchers.IO) {
        if (!isRuntimeId(id)) return@withContext null
        synchronized(lock) { read().environments.find { it.id == id } }
    }

    suspend fun registerContainer(container: ProotContainer) = update { it.withContainer(container) }

    suspend fun registerWorkspace(workspace: ProotWorkspace) = update {
        files.requireWorkspace(workspace)
        it.withWorkspace(workspace)
    }

    suspend fun registerEnvironment(environment: ProotEnvironment) = update { it.withEnvironment(environment) }

    suspend fun prepare(environmentId: String): ProotPreparation = withContext(Dispatchers.IO) {
        val snapshot = synchronized(lock) { read() }
        files.prepare(snapshot, environmentId, trustedRootfsDigests())
    }

    suspend fun plan(environmentId: String, command: ProotGuestCommand): ProotPlan = withContext(Dispatchers.IO) {
        val snapshot = synchronized(lock) { read() }
        ProotPlan.Command.inspect(files, snapshot, environmentId, trustedRootfsDigests(), command)
    }

    private fun trustedRootfsDigests(): Set<String> = try {
        // Shipped by the APK publisher. The guest image cannot add digests to this list.
        app.assets.open("proot/trusted-rootfs-v1.txt").bufferedReader(Charsets.US_ASCII).useLines { lines ->
            lines.filter(String::isNotBlank).onEach { require(rootfsDigest.matches(it)) }.toSet()
        }
    } catch (_: FileNotFoundException) {
        emptySet()
    }

    private fun read(): ProotCatalog {
        // Android versions of AtomicFile may consult or replace one of these files during recovery.
        val backup = File(file.path + ".bak")
        for (candidate in listOf(app.filesDir, file, backup, File(file.path + ".new"))) {
            if (Files.isSymbolicLink(candidate.toPath())) throw IOException("运行环境目录不可经过符号链接。")
        }
        val input = try {
            catalogFile.openRead()
        } catch (e: FileNotFoundException) {
            if (!Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                !Files.notExists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) throw e
            return ProotCatalog()
        }
        val json = input.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        return ProotCatalog.fromJson(json)
    }

    private suspend fun update(transform: (ProotCatalog) -> ProotCatalog): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = read()
            val next = transform(current)
            if (next == current) return@synchronized
            val encoded = next.toJson().toString().toByteArray(Charsets.UTF_8)
            val output = catalogFile.startWrite()
            try {
                output.write(encoded)
                catalogFile.finishWrite(output)
            } catch (failure: Throwable) {
                catalogFile.failWrite(output)
                throw failure
            }
        }
    }

    private companion object {
        // Multiple stores in the app must serialize the entire read-modify-write cycle.
        val lock = Any()
    }
}
