package io.hydrion.hub.model

import java.util.zip.ZipFile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssistantSessionTest {
    @get:Rule val files = TemporaryFolder()
    private val default = LocalAssistant.Default

    @Test fun legacyHistoryStaysWithDefaultAndGlobalOpenRestoresItsOwner() = runBlocking {
        val legacy = JSONObject("""{"history":[{"id":"old","title":"Older chat","mode":"Chat","updatedAt":7}],
            "drafts":{"old":{"text":"keep"}}}""")
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(decodeWorkspace(legacy))
        val state = session.state
        assertEquals(listOf(default), state.value.assistants)
        assertFalse(session.addAssistant("   "))
        assertTrue(session.addAssistant("Travel"))
        val travel = state.value.assistants.last()
        session.selectAssistant(travel.id)
        assertFalse(state.value.modeLocked)
        session.setDraft("packing")
        val scratch = state.value.current.id
        session.openConversation("old")
        assertEquals(default.id, state.value.current.assistantId)
        assertEquals("keep", state.value.draft.text)
        session.renameAssistant(travel.id, "Trips")
        repeat(3) {
            session.selectAssistant(travel.id)
            assertEquals(scratch, state.value.current.id)
            assertEquals("packing", state.value.draft.text)
            session.selectAssistant(default.id)
            assertEquals("old", state.value.current.id)
        }
        val cold = ConversationSession(this, FakeGenerationRepository {}, {})
        cold.restore(decodeWorkspace(encodeWorkspace(session.snapshot())))
        assertEquals("old", cold.state.value.current.id)
        assertEquals("Trips", cold.state.value.assistants.last().name)
        cold.selectAssistant(travel.id)
        assertEquals("packing", cold.state.value.draft.text)
        assertEquals("old", cold.state.value.history.single().id)
    }

    @Test fun twoParkedDraftsAndAnAttachmentSurviveRestartAndPortableBackup() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.setDraft("default unsent")
        val first = session.state.value.current.id
        session.addAssistant("美术")
        val art = session.state.value.assistants.last()
        session.selectAssistant(art.id)
        session.setMode(Mode.Image)
        session.setDraft("画一只鸟")
        session.addAttachment(Attachment("ref", "bird", "content://picked/ref"))
        val second = session.state.value.current.id
        val work = session.snapshot()
        val cold = ConversationSession(this, FakeGenerationRepository {}, {})
        cold.restore(decodeWorkspace(encodeWorkspace(work)))
        assertEquals(second, cold.state.value.current.id)
        assertEquals(Mode.Image, cold.state.value.current.mode)
        assertEquals("ref", cold.state.value.draft.attachments.single().id)
        cold.selectAssistant(default.id)
        assertEquals(first, cold.state.value.current.id)
        assertEquals("default unsent", cold.state.value.draft.text)
        assertTrue(cold.state.value.draft.attachments.isEmpty())
        val archive = ChatArchive.export(files.newFolder(), work,
            { "bird bytes".byteInputStream() }, { _, _ -> fail("unexpected image") })
        ZipFile(archive).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { JSONObject(it.readText()) }
            assertEquals(2, manifest.getInt("version"))
        }
        val imported = archive.inputStream().use { ChatArchive.inspect(files.newFolder(), it) }
            .restored({ "content://restored/$it" }, { it })
        val fresh = ConversationSession(this, FakeGenerationRepository {}, {})
        fresh.adoptBackup(mergeBackup(SavedWorkspace(), imported))
        assertEquals(second, fresh.state.value.current.id)
        assertTrue(fresh.state.value.draft.attachments.single().uri!!.startsWith("content://restored/"))
        fresh.selectAssistant(default.id)
        assertEquals(first, fresh.state.value.current.id)
        assertEquals("default unsent", fresh.state.value.draft.text)
    }

    @Test fun mergingNewHistoryCannotReplaceAnOpenScratchOrStealTheOtherAssistantsDraft() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.addAssistant("Science")
        val science = session.state.value.assistants.last()
        session.selectAssistant(science.id)
        session.setDraft("unpublished")
        val scratch = session.state.value.current.id
        val imported = Conversation(assistantId = science.id, title = "Imported")
        val other = SavedWorkspace(listOf(imported), assistants = session.state.value.assistants,
            selectedAssistant = science.id, lastThreads = mapOf(science.id to imported.id))
        val merged = mergeBackup(session.snapshot(true), other)
        session.adoptBackup(merged)
        assertEquals(merged, session.snapshot(includeUnsent = true))
        assertEquals(scratch, session.state.value.current.id)
        assertEquals("unpublished", session.state.value.draft.text)
        session.selectAssistant(default.id)
        assertFalse(session.restoreModelPickerDraft(imported.id, Draft("stale picker")))
        assertEquals(default.id, session.state.value.current.assistantId)
    }

    @Test fun backgroundGenerationNeverAppendsToTheOtherAssistant() = runBlocking {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val session = ConversationSession(this, FakeGenerationRepository { gate.receive() }, {})
        session.addAssistant("Editing")
        session.setDraft("first")
        session.send("local", emptyMap(), null, null)
        val source = session.state.value.current.id
        session.selectAssistant(session.state.value.assistants.last().id)
        session.setDraft("elsewhere")
        repeat(120) { gate.trySend(Unit) }
        yield()
        assertEquals("elsewhere", session.state.value.draft.text)
        assertTrue(session.state.value.current.messages.isEmpty())
        session.selectAssistant(default.id)
        assertEquals(source, session.state.value.current.id)
        assertEquals(Generation.Completed, session.state.value.current.messages.last().status)
        assertTrue(session.state.value.draft.text.isEmpty())
    }
}
