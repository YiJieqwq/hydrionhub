package io.hydrion.hub.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** App-private configuration only. No external path, command, permission, or mount comes from the catalog. */
internal class ProotEnvironmentStore(context: Context, private val replaceFile: (Path, Path) -> Unit = { source, target ->
    Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
}) {
    private val app = context.applicationContext
    private val file = app.filesDir.toPath().resolve("proot-environments-v1.json")
    private val staged = file.resolveSibling(file.fileName.toString() + ".new")
    private val backup = file.resolveSibling(file.fileName.toString() + ".bak")
    private val changed = MutableStateFlow(0)
    val revision = changed.asStateFlow()
    private val files = ProotFiles(app.filesDir, app.applicationInfo.nativeLibraryDir?.let(::File))

    suspend fun catalog(): ProotCatalog = withContext(Dispatchers.IO) { synchronized(lock) { read() } }

    suspend fun saveContainer(id: String?, name: String, digest: String?) = update { catalog ->
        require(validRuntimeName(name))
        if (id == null) catalog.withContainer(ProotContainer(UUID.randomUUID().toString(), digest, name))
        else catalog.editContainer(id, name, digest)
    }

    suspend fun createEnvironment(containerId: String, name: String): Unit = withContext(Dispatchers.IO) {
        require(validRuntimeName(name))
        synchronized(lock) {
            val catalog = read()
            val workspace = ProotWorkspace(UUID.randomUUID().toString())
            val environment = ProotEnvironment(UUID.randomUUID().toString(), containerId, workspace.id, name = name)
            val next = catalog.withWorkspace(workspace).withEnvironment(environment)
            createManagedWorkspace(app.filesDir, workspace) { write(next) }
        }
    }

    suspend fun renameEnvironment(id: String, name: String) = update { it.renameEnvironment(id, name) }

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

    suspend fun directory(environmentId: String, guestPath: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val env = synchronized(lock) { read().environments.single { it.id == environmentId } }
        readWorkspaceDirectory(files.workspaceDirectory(ProotWorkspace(env.workspaceId), guestPath))
    }

    suspend fun setWorkingDirectory(environmentId: String, previous: String, guestPath: String) = update { catalog ->
        val env = catalog.environments.single { it.id == environmentId }
        require(env.guestCwd == previous)
        files.workspaceDirectory(ProotWorkspace(env.workspaceId), guestPath)
        catalog.copy(environments = catalog.environments.map { if (it.id == env.id) it.copy(guestCwd = guestPath) else it })
    }

    suspend fun prepare(environmentId: String): ProotPreparation = withContext(Dispatchers.IO) {
        val snapshot = synchronized(lock) { read() }
        files.prepare(snapshot, environmentId, trustedRootfsDigests())
    }

    suspend fun checkRuntime(environmentId: String): ProotCheck = withContext(Dispatchers.IO) {
        val catalog = synchronized(lock) { read() }
        val abis = if (android.os.Process.is64Bit()) android.os.Build.SUPPORTED_64_BIT_ABIS
            else android.os.Build.SUPPORTED_32_BIT_ABIS
        ProotLauncherCheck().inspect(files, catalog, environmentId, trustedRootfsDigests(), abis.toSet())
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
        for (path in listOf(file.parent, file, backup, staged)) {
            if (Files.isSymbolicLink(path)) throw IOException("运行环境目录不可经过符号链接。")
        }
        // Older Android AtomicFile writers kept the last committed catalog in .bak during a write.
        // A leftover .new has never been committed, even on the first save.
        if (!Files.notExists(backup, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(backup, NOFOLLOW_LINKS)) throw IOException("Invalid environment catalog backup")
            replaceFile(backup, file)
        }
        if (Files.notExists(file, NOFOLLOW_LINKS)) return ProotCatalog()
        val json = Files.newBufferedReader(file, Charsets.UTF_8).use { JSONObject(it.readText()) }
        return ProotCatalog.fromJson(json)
    }

    private suspend fun update(transform: (ProotCatalog) -> ProotCatalog): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = read()
            val next = transform(current)
            if (next != current) write(next)
        }
    }

    private fun write(next: ProotCatalog) {
        val encoded = next.toJson().toString().toByteArray(Charsets.UTF_8)
        if (Files.isRegularFile(staged, NOFOLLOW_LINKS)) Files.delete(staged)
        Files.createFile(staged)
        try {
            staged.toFile().outputStream().use { stream ->
                stream.write(encoded)
                stream.fd.sync()
            }
            // A failed replacement must reach the caller so workspace creation can roll back.
            replaceFile(staged, file)
        } catch (failure: Throwable) {
            try { Files.deleteIfExists(staged) } catch (cleanup: IOException) { failure.addSuppressed(cleanup) }
            throw failure
        }
        changed.value++
    }

    private companion object {
        // Multiple stores in the app must serialize the entire read-modify-write cycle.
        val lock = Any()
    }
}
