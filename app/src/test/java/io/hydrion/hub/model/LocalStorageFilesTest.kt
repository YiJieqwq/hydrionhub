package io.hydrion.hub.model

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalStorageFilesTest {
    @get:Rule val root = TemporaryFolder()
    @Test fun clearsOnlyExpiredRegularCopiesAndReportsActualSizes() {
        val files = root.newFolder("files")
        val cache = root.newFolder("cache")
        val prefs = root.newFolder("prefs")
        val copies = File(cache, "copies").apply { mkdir() }
        val originals = File(files, "restored-attachments").apply { mkdir() }
        val original = File(originals, "one").apply { writeBytes(ByteArray(31)) }
        val old = File(copies, "HydrionHub-123.png").apply { writeBytes(ByteArray(17)); setLastModified(1) }
        val fresh = File(copies, "HydrionHub-456.png").apply { writeBytes(ByteArray(23)) }
        val unknown = File(copies, "other.png").apply { writeBytes(ByteArray(10)); setLastModified(1) }
        Files.createSymbolicLink(File(copies, "HydrionHub-link.png").toPath(), original.toPath())
        val storage = LocalStorageFiles(files, cache, prefs, root.newFolder("no-backup"))
        val usage = storage.usage()
        assertEquals(31L, usage.imported)
        assertEquals(40L, usage.cachedCopies)
        assertEquals(17L, usage.expiredCopies)
        val ref = "content://fixture.images/generated/${old.name}"
        val workspace = SavedWorkspace(drafts = mapOf("new-chat" to Draft(attachments = listOf(Attachment(ref, "copy", ref)))))
        val protected = workspace.clipboardOriginals("fixture.images")
        assertEquals(0L, storage.usage(protectedNames = protected).expiredCopies)
        storage.clearExpired(protectedNames = protected)
        assertTrue(old.exists())
        storage.clearExpired()
        assertFalse(old.exists())
        assertTrue(fresh.exists() && original.exists() && unknown.exists())
        assertEquals(0L, storage.usage().expiredCopies)
    }
}
