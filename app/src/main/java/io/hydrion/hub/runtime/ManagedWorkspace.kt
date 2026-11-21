package io.hydrion.hub.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
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
