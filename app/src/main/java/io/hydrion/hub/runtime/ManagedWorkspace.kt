package io.hydrion.hub.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.LinkOption

/** Create one fresh app-private workspace, and publish its catalog record only after creation. */
internal fun createManagedWorkspace(filesDir: File, workspace: ProotWorkspace, publish: () -> Unit) {
    val root = filesDir.toPath()
    val noLinks = LinkOption.NOFOLLOW_LINKS
    if (!Files.isDirectory(root, noLinks)) throw IOException("Unavailable app directory")
    val parent = listOf("proot-v1", "workspaces").fold(root) { path, component ->
        path.resolve(component).also {
            if (Files.notExists(it, noLinks)) Files.createDirectory(it)
            if (!Files.isDirectory(it, noLinks)) throw IOException("Invalid workspace parent")
        }
    }
    // createDirectory fails if even an empty directory or symbolic link already occupies the UUID.
    val allocated = Files.createDirectory(parent.resolve(workspace.id))
    try {
        publish()
    } catch (error: Throwable) {
        try {
            Files.delete(allocated) // Never recurse into data on rollback.
        } catch (cleanup: IOException) {
            error.addSuppressed(cleanup)
        }
        throw error
    }
}

internal data class WorkspaceEntry(val name: String, val directory: Boolean, val bytes: Long)

/** List metadata only. Linked and special files never become navigation targets. */
internal fun readWorkspaceDirectory(path: Path): List<WorkspaceEntry> =
    Files.newDirectoryStream(path).use { entries ->
        entries.mapNotNull { child ->
            val name = child.fileName.toString()
            if (!isGuestPath("/workspace/$name")) return@mapNotNull null
            val attributes = try {
                Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: java.nio.file.NoSuchFileException) { return@mapNotNull null }
            if (!attributes.isDirectory && !attributes.isRegularFile) return@mapNotNull null
            WorkspaceEntry(name, attributes.isDirectory, attributes.size())
        }.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name })
    }
