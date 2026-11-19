package io.hydrion.hub.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.ArrayDeque

internal data class ProotRuntimeTools(val launcher: File, val loader: File)

internal sealed interface ProotPreparation {
    /**
     * Package and private provisioning metadata plus layout checks for this inspection.
     * The receipt does not re-verify the integrity of every file in a mutable guest tree.
     * There is no process start, PID ownership, or reclamation API at this stage.
     */
    data class Ready(
        val tools: ProotRuntimeTools,
        val rootfs: File,
        val workspace: File,
        val workspaceMountPoint: File,
        val guestCwd: String,
        val hostCwd: File
    ) : ProotPreparation

    data class Unavailable(val reason: Reason) : ProotPreparation

    enum class Reason {
        UnknownEnvironment, RuntimeNotPackaged, UntrustedRootfs, IncompleteRootfs,
        MissingWorkspace, MissingWorkingDirectory, UnsafeOrUnreadablePath
    }
}

/** Only Android-provided roots enter here. Catalog records carry IDs, never host paths or bind mounts. */
internal class ProotFiles(private val filesDir: File, private val nativeLibraryDir: File?) {
    private val noLinks = LinkOption.NOFOLLOW_LINKS

    private fun ownedRoot(directory: File): Path {
        val path = directory.toPath()
        if (Files.isSymbolicLink(path)) throw IOException("运行目录不可指向其他位置。")
        return path.toRealPath()
    }

    // Check each component, including a missing leaf's parents. A broken symlink is still a symlink.
    private fun below(root: Path, vararg components: String): Path {
        var path = root
        for (part in components) {
            require(part.isNotEmpty() && part != "." && part != ".." && '/' !in part && '\\' !in part)
            path = path.resolve(part)
            if (Files.isSymbolicLink(path)) throw IOException("路径不可经过符号链接。")
        }
        return path
    }

    private fun workspacePath(root: Path, workspace: ProotWorkspace) =
        below(root, "proot-v1", "workspaces", workspace.id)

    fun requireWorkspace(workspace: ProotWorkspace) {
        val path = workspacePath(ownedRoot(filesDir), workspace)
        require(Files.isDirectory(path, noLinks)) { "请先预置应用专属工作区目录。" }
    }

    private fun trustedShell(rootfs: Path): Boolean {
        // Guest absolute symlinks are rooted in the guest image, never the Android host.
        val remaining = ArrayDeque(listOf("bin", "sh"))
        var current = rootfs
        var links = 0
        while (remaining.isNotEmpty()) {
            if (!Files.isDirectory(current, noLinks)) return false
            when (val part = remaining.removeFirst()) {
                "." -> continue
                ".." -> {
                    if (current == rootfs) return false
                    current = current.parent
                }
                else -> {
                    val next = current.resolve(part)
                    if (Files.isSymbolicLink(next)) {
                        if (++links > 40) return false
                        val target = Files.readSymbolicLink(next)
                        if (target.isAbsolute) current = rootfs
                        target.map { it.toString() }.asReversed().forEach(remaining::addFirst)
                    } else {
                        current = next
                    }
                }
            }
        }
        return Files.isRegularFile(current, noLinks) && Files.isExecutable(current) && Files.size(current) > 0
    }

    private fun packagedElf(path: Path): Boolean {
        if (!Files.isRegularFile(path, noLinks) || !Files.isExecutable(path)) return false
        return Files.newInputStream(path, noLinks).buffered().use { input ->
            if (!listOf(0x7f, 0x45, 0x4c, 0x46).all { it == input.read() }) return@use false
            val minimumSize = when (input.read()) { 1 -> 52; 2 -> 64; else -> return@use false }
            input.read() in 1..2 && input.read() == 1 && Files.size(path) >= minimumSize
        }
    }

    private fun receiptMatches(path: Path, digest: String): Boolean {
        if (!Files.isRegularFile(path, noLinks)) return false
        return Files.newInputStream(path, noLinks).buffered().use { input ->
            digest.all { input.read() == it.code } && when (input.read()) {
                -1 -> true
                10 -> input.read() == -1
                else -> false
            }
        }
    }

    fun prepare(catalog: ProotCatalog, id: String, trustedDigests: Set<String>): ProotPreparation {
        val env = catalog.environments.find { it.id == id }
            ?: return ProotPreparation.Unavailable(ProotPreparation.Reason.UnknownEnvironment)
        val container = catalog.containers.first { it.id == env.containerId }
        val workspace = catalog.workspaces.first { it.id == env.workspaceId }
        fun absent(reason: ProotPreparation.Reason) = ProotPreparation.Unavailable(reason)
        return try {
            val nativeRoot = nativeLibraryDir?.takeIf { it.isDirectory }?.let(::ownedRoot)
                ?: return absent(ProotPreparation.Reason.RuntimeNotPackaged)
            val proot = below(nativeRoot, "libproot.so")
            val loader = below(nativeRoot, "libproot-loader.so")
            if (!packagedElf(proot) || !packagedElf(loader)) {
                return absent(ProotPreparation.Reason.RuntimeNotPackaged)
            }
            if (container.rootfsSha256 !in trustedDigests) return absent(ProotPreparation.Reason.UntrustedRootfs)
            val privateRoot = ownedRoot(filesDir)
            val instance = below(privateRoot, "proot-v1", "containers", container.id)
            val rootfs = below(instance, "rootfs")
            val receipt = below(instance, "installed.sha256")
            // This host-private receipt is reserved for a trusted provisioner after full extraction.
            if (!receiptMatches(receipt, container.rootfsSha256) ||
                !Files.isDirectory(rootfs, noLinks)) return absent(ProotPreparation.Reason.IncompleteRootfs)
            val mount = below(rootfs, "workspace")
            if (!Files.isDirectory(mount, noLinks) || !Files.isDirectory(below(rootfs, "etc"), noLinks) ||
                !Files.isDirectory(below(rootfs, "usr"), noLinks) || !trustedShell(rootfs)) {
                return absent(ProotPreparation.Reason.IncompleteRootfs)
            }
            val work = workspacePath(privateRoot, workspace)
            if (!Files.isDirectory(work, noLinks)) return absent(ProotPreparation.Reason.MissingWorkspace)
            val cwd = if (env.guestCwd == "/workspace") work else
                below(work, *env.guestCwd.removePrefix("/workspace/").split('/').toTypedArray())
            if (!Files.isDirectory(cwd, noLinks)) return absent(ProotPreparation.Reason.MissingWorkingDirectory)
            ProotPreparation.Ready(ProotRuntimeTools(proot.toFile(), loader.toFile()), rootfs.toFile(),
                work.toFile(), mount.toFile(), env.guestCwd, cwd.toFile())
        } catch (_: IOException) {
            absent(ProotPreparation.Reason.UnsafeOrUnreadablePath)
        } catch (_: SecurityException) {
            absent(ProotPreparation.Reason.UnsafeOrUnreadablePath)
        }
    }
}
