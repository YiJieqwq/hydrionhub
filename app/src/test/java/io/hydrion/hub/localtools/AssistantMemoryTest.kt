package io.hydrion.hub.localtools

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssistantMemoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun separateOwnersRemainIsolatedAcrossReopeningAndDisable() = runBlocking {
        val first = memoryFile(temporary.root, "../owner")
        val second = memoryFile(temporary.root, "other")
        assertFalse(first.read().enabled)
        assertTrue(runCatching { first.update { it.save(null, "manual", NoteType.Workflow) } }.isFailure)
        first.update { it.copy(enabled = true).save(null, "Run manual CHECK", NoteType.Workflow) }
        second.update { it.copy(enabled = true) }
        assertTrue(second.read().search("").isEmpty())
        val reopened = memoryFile(temporary.root, "../owner")
        val id = reopened.read().search("Check run").single().id
        reopened.update { it.save(id, "amended", NoteType.Voice).archive(id, true) }
        assertTrue(first.read().search("amended").isEmpty())
        assertEquals(id, first.read().search("amended", archived = true).single().id)
        first.update { it.copy(enabled = false) }
        assertTrue(first.read().search("", archived = true).isEmpty())
        first.update { it.copy(enabled = true).archive(id, false) }
        assertEquals(NoteType.Voice, first.read().search("").single().type)
        first.update { it.delete(id) }
        assertTrue(reopened.read().notes.isEmpty())
    }

    @Test fun updatesUseLatestRevisionAndNeverOverwriteUnreadableData() = runBlocking {
        val root = temporary.root
        val file = memoryFile(root, "same")
        file.update { it.copy(enabled = true) }
        (1..12).map { index -> async {
            memoryFile(root, "same").update { it.save(null, "Note $index", NoteType.Identity) }
        } }.awaitAll()
        assertEquals(12, file.read().notes.distinctBy { it.id }.size)
        val disk = root.walkTopDown().single { it.name == "memory.json" }
        listOf("bad json", """{"schema":99,"data":{}}""").forEach { invalid ->
            disk.writeText(invalid)
            assertTrue(runCatching { file.update { it.copy(enabled = false) } }.isFailure)
            assertEquals(invalid, disk.readText())
        }
    }
}
