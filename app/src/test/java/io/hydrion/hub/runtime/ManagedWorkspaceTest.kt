package io.hydrion.hub.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

class ManagedWorkspaceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun id() = UUID.randomUUID().toString()

    @Test fun pendingAndExistingImagesAndNamesRoundTripWithoutChangingBindings() {
        val container = ProotContainer(id(), null, "Debian")
        val workspace = ProotWorkspace(id())
        val env = ProotEnvironment(id(), container.id, workspace.id, name = "Notes")
        val fresh = ProotCatalog().withContainer(container).withWorkspace(workspace).withEnvironment(env)
        assertEquals(fresh, ProotCatalog.fromJson(JSONObject(fresh.toJson().toString())))
        assertThrows(IllegalArgumentException::class.java) { fresh.withContainer(container.copy(id = id())) }
        val digest = "f".repeat(64)
        val edited = fresh.editContainer(container.id, "Container", digest).renameEnvironment(env.id, "Work")
        assertEquals(env.copy(name = "Work"), edited.environments.single())
        val old = edited.toJson().apply {
            getJSONArray("containers").getJSONObject(0).remove("name")
            getJSONArray("environments").getJSONObject(0).remove("name")
        }
        val reopened = ProotCatalog.fromJson(old)
        assertEquals(digest, reopened.containers.single().rootfsSha256)
        assertEquals("", reopened.environments.single().name)
        assertThrows(IllegalArgumentException::class.java) { fresh.renameEnvironment(env.id, "  ") }
        assertThrows(IllegalArgumentException::class.java) { fresh.editContainer(container.id, "safe", "untrusted") }
    }

    @Test fun workspaceUsesFreshPrivateDirectoryAndRollsBackOnlyItsOwnAllocation() {
        val workspace = ProotWorkspace(id())
        val path = File(temp.root, "proot-v1/workspaces/${workspace.id}")
        assertThrows(IOException::class.java) {
            createManagedWorkspace(temp.root, workspace) { assertTrue(path.isDirectory); throw IOException("write failed") }
        }
        assertFalse(path.exists())
        createManagedWorkspace(temp.root, workspace) { assertTrue(path.isDirectory) }
        val marker = File(path, "notes").apply { writeText("preserve") }
        assertThrows(IOException::class.java) { createManagedWorkspace(temp.root, workspace) { fail() } }
        assertEquals("preserve", marker.readText())

        val other = temp.newFolder("other")
        Files.createSymbolicLink(File(other, "proot-v1").toPath(), temp.root.toPath())
        assertThrows(IOException::class.java) { createManagedWorkspace(other, ProotWorkspace(id())) { fail() } }
    }

    @Test fun panelListingIsReadOnlyAndScopedToTheRegisteredWorkspace() {
        val workspace = ProotWorkspace(id())
        createManagedWorkspace(temp.root, workspace) {}
        val files = ProotFiles(temp.root, null)
        val root = files.workspaceDirectory(workspace, "/workspace")
        Files.createDirectory(root.resolve("docs"))
        Files.write(root.resolve("notes.txt"), byteArrayOf(65, 66))
        Files.createSymbolicLink(root.resolve("other"), temp.newFolder("sibling").toPath())
        val entries = readWorkspaceDirectory(root)
        assertEquals(listOf("docs", "notes.txt"), entries.map { it.name })
        assertEquals(2L, entries.last().bytes)
        assertTrue(entries.first().directory)
        assertThrows(IOException::class.java) { files.workspaceDirectory(workspace, "/workspace/other") }
        assertThrows(IllegalArgumentException::class.java) { files.workspaceDirectory(workspace, "/workspace/../sibling") }
    }
}
