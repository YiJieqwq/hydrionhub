package io.hydrion.hub.model

import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.ui.messages.MessageStyle
import io.hydrion.hub.ui.sidebar.globalDrawerHits
import io.hydrion.hub.ui.sidebar.historySections
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationArchiveFlowTest {
    @get:Rule val temp = TemporaryFolder()

    private val owner = LocalAssistant.Default
    private val writer = LocalAssistant("writer", owner.name)
    private val question = Message(id = "q", role = Role.User, text = "Orchid question", createdAt = 10)
    private val answer = Message(id = "a", role = Role.Assistant, text = "Previous answer", replyTo = question.id, createdAt = 20)
    private val thread = Conversation(id = "target", assistantId = writer.id, title = "Notes", pinned = true,
        updatedAt = 300, messages = listOf(question, answer), contextStart = question.id,
        modelRoutes = mapOf(Mode.Chat to RemoteModel("fixture", "chat")))
        .appendVersion(answer, answer.copy(text = "Revised answer"))
    private val sibling = Conversation(id = "sibling", assistantId = writer.id, title = "Planning", updatedAt = 100)
    private val other = Conversation(id = "other", assistantId = owner.id, title = thread.title, updatedAt = 200)
    private val attachment = Attachment("note", "outline.txt", uri = "content://fixture.invalid/outline", mimeType = "text/plain")
    private val draft = Draft("Continue this answer", listOf(attachment))
    private val work = SavedWorkspace(history = listOf(thread, other, sibling),
        drafts = mapOf(thread.id to draft, other.id to Draft("Another draft")), assistants = listOf(owner, writer),
        selectedAssistant = writer.id, lastThreads = mapOf(writer.id to thread.id, owner.id to other.id))

    @Test fun archiveCurrentThenFindByOwnerAndRestoreTheOriginalThreadAfterRestart() = runBlocking {
        val writes = mutableListOf<SavedWorkspace>()
        fun session() = ConversationSession(this, FakeGenerationRepository {}, { writes += it })
        val live = session()
        live.restore(work)
        assertEquals(thread, live.state.value.current)
        live.setArchived(thread.id, true)
        assertEquals(sibling.id, live.state.value.current.id)
        assertEquals(writer.id, live.state.value.current.assistantId)
        val saved = writes.single()
        assertEquals(thread.copy(archived = true), saved.history.single { it.id == thread.id })
        assertEquals(other, saved.history.single { it.id == other.id })
        assertEquals(work.drafts, saved.drafts)
        assertFalse(thread.id in saved.lastThreads.values)
        live.setArchived(thread.id, true)
        live.setArchived("vanished-id", false)
        assertEquals(1, writes.size)

        val cold = session()
        cold.restore(decodeWorkspace(JSONObject(encodeWorkspace(saved).toString())))
        val rows = cold.state.value.history
        fun find(ownerId: String?, archived: Boolean) =
            historySections(rows, "nOtEs", ownerId, archived = archived).flatMap { section -> section.rows.map { it.id } }
        assertEquals(listOf(other.id), historySections(rows, "Notes").flatMap { it.rows.map(Conversation::id) })
        assertTrue(globalDrawerHits(rows, "Orchid").isEmpty())
        assertEquals(listOf(thread.id), find(writer.id, true))
        assertEquals(emptyList<String>(), find(owner.id, true))
        assertEquals(listOf(thread.id), find(null, true))
        cold.openConversation(thread.id)
        assertEquals(sibling.id, cold.state.value.current.id)

        cold.setArchived(thread.id, false)
        cold.setArchived(thread.id, false)
        assertEquals(2, writes.size)
        assertEquals(sibling.id, cold.state.value.current.id)
        val restored = session()
        restored.restore(decodeWorkspace(encodeWorkspace(writes.last())))
        assertEquals(listOf(thread.id), globalDrawerHits(restored.state.value.history, "orchid").map { it.id })
        assertTrue(historySections(restored.state.value.history, "", archived = true).isEmpty())
        restored.openConversation(thread.id)
        assertEquals(thread, restored.state.value.current)
        assertEquals(draft, restored.state.value.draft)
        restored.selectAssistant(owner.id)
        assertEquals(other, restored.state.value.current)
        assertEquals(work.drafts[other.id], restored.state.value.draft)
    }

    @Test fun archivingAnotherOwnersOnlyThreadLeavesCurrentDraftAndNeverResumesAHiddenThread() = runBlocking {
        val live = ConversationSession(this, FakeGenerationRepository {}, {})
        live.restore(work)
        live.setArchived(other.id, true)
        assertEquals(thread, live.state.value.current)
        assertEquals(draft, live.state.value.draft)
        live.selectAssistant(owner.id)
        val scratch = live.state.value.current
        assertFalse(live.state.value.modeLocked)
        assertEquals(owner.id, scratch.assistantId)
        assertNotEquals(other.id, scratch.id)
        assertEquals(Draft(), live.state.value.draft)
        live.setDraft("Park this scratch")
        live.selectAssistant(writer.id)
        assertEquals(draft, live.state.value.draft)

        live.setArchived(other.id, false)
        live.selectAssistant(owner.id)
        assertEquals(scratch.id, live.state.value.current.id)
        assertEquals("Park this scratch", live.state.value.draft.text)
        live.openConversation(other.id)
        assertEquals(other, live.state.value.current)
        assertEquals(work.drafts[other.id], live.state.value.draft)
    }

    @Test fun lateModelPickerAndListActionsLeaveTheArchivedThreadAndCurrentDraftIntact() = runBlocking {
        val writes = mutableListOf<SavedWorkspace>()
        val live = ConversationSession(this, FakeGenerationRepository {}, { writes += it })
        live.restore(work)
        live.newConversation()
        live.setDraft("Parked for the writer")
        val scratch = live.state.value.current
        val scratchDraft = live.state.value.draft
        live.openConversation(thread.id)
        live.setArchived(sibling.id, true)
        live.setArchived(thread.id, true)
        assertEquals(scratch, live.state.value.current)
        assertEquals(scratchDraft, live.state.value.draft)

        val before = live.snapshot()
        val writeCount = writes.size
        live.moveConversations(setOf(thread.id), owner.id)
        live.togglePin(thread.id)
        live.setPinned(setOf(thread.id), false)
        live.rename(thread.id, "Stale title")
        live.duplicateConversation(thread.id, "Stale copy")
        live.deleteConversation(thread.id)
        assertFalse(live.restoreModelPickerDraft(thread.id, Draft("Stale editor text")))
        assertEquals(before, live.snapshot())
        assertEquals(writeCount, writes.size)
        assertEquals(scratch, live.state.value.current)
        assertEquals(scratchDraft, live.state.value.draft)

        // A batch may still contain an eligible row. Only that row and its draft may be removed.
        live.deleteConversations(setOf(thread.id, other.id))
        val after = live.snapshot()
        assertEquals(thread.copy(archived = true), after.history.single { it.id == thread.id })
        assertEquals(draft, after.drafts[thread.id])
        assertFalse(after.history.any { it.id == other.id })
        assertFalse(other.id in after.drafts)
        assertEquals(scratchDraft, live.state.value.draft)
    }

    @Test fun archivingFromAStaleActionCannotHideAnActiveGeneration() = runBlocking {
        val writes = mutableListOf<SavedWorkspace>()
        val live = ConversationSession(this, FakeGenerationRepository { awaitCancellation() }, { writes += it })
        live.restore(work)
        live.send("Local fixture", emptyMap(), null, null)
        try {
            assertTrue(live.state.value.isGenerating)
            live.selectAssistant(owner.id)
            val before = live.state.value
            val writeCount = writes.size
            live.setArchived(thread.id, true)
            assertEquals(before, live.state.value)
            assertEquals(writeCount, writes.size)
            live.selectAssistant(writer.id)
            assertTrue(live.state.value.isGenerating)
        } finally {
            live.openConversation(thread.id)
            live.stop()
        }
        live.setArchived(thread.id, true)
        assertTrue(live.state.value.history.single { it.id == thread.id }.archived)
    }

    @Test fun historyAndPortableBackupWithoutAnArchiveFieldStillOpenNormally() = runBlocking {
        val oldJson = JSONObject("""{"history":[{"id":"legacy","title":"Legacy","mode":"Chat","pinned":true,"updatedAt":42}],"drafts":{}}""")
        val saved = decodeWorkspace(oldJson)
        val thread = saved.history.single()
        assertFalse(thread.archived)
        assertEquals(listOf(thread), historySections(saved.history, "legacy").single().rows)
        // Build a v1 payload directly: exporting it with today's writer would not exercise the old field layout.
        val metadata = oldJson.toString().toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(metadata).joinToString("") { "%02x".format(it.toInt() and 255) }
        val manifest = JSONObject().put("format", "hydrion.chat").put("version", 1)
            .put("workspaceSha256", digest).put("generated", JSONObject())
        val zip = temp.newFile()
        ZipOutputStream(zip.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("workspace.json"))
            output.write(metadata)
            output.closeEntry()
            output.putNextEntry(ZipEntry("manifest.json"))
            output.write(manifest.toString().toByteArray())
            output.closeEntry()
        }
        val preview = zip.inputStream().use { ChatArchive.inspect(temp.newFolder(), it) }
        assertEquals(1, preview.formatVersion)
        val live = ConversationSession(this, FakeGenerationRepository {}, {})
        live.restore(preview.restored({ error("Unexpected attachment") }, { error("Unexpected image") }))
        live.openConversation(thread.id)
        assertEquals(thread, live.state.value.current)
    }

    @Test fun bothBackupScopesPreserveArchiveStateVersionHistoryAndDraftAttachment() = runBlocking {
        val live = ConversationSession(this, FakeGenerationRepository {}, {})
        live.restore(work)
        live.setArchived(thread.id, true)
        val bytes = "synthetic draft attachment".toByteArray()
        val settings = AppBackupSettings(ModelCatalog(emptyList()), MessageStyle())
        for (config in listOf(null, settings)) {
            val application = config != null
            val zip = ChatArchive.write(temp.newFolder(), live.snapshot(), config, { uri ->
                assertEquals(attachment.uri, uri)
                bytes.inputStream()
            }, { _, _ -> error("Unexpected generated image") })
            val preview = zip.inputStream().use { ChatArchive.inspect(temp.newFolder(), it, application) }
            assertEquals(if (application) 2 else 4, preview.formatVersion)
            assertEquals(thread.copy(archived = true), preview.workspace.history.single { it.id == thread.id })
            val restoredUri = "content://restored.invalid/outline"
            val recovered = preview.restored({ hash ->
                assertArrayEquals(bytes, preview.blobs.getValue(hash).readBytes())
                restoredUri
            }, { error("Unexpected image") })
            val reopened = ConversationSession(this, FakeGenerationRepository {}, {})
            reopened.adoptBackup(recovered)
            reopened.setArchived(thread.id, false)
            reopened.openConversation(thread.id)
            assertEquals(thread, reopened.state.value.current)
            assertEquals(draft.copy(attachments = listOf(attachment.copy(uri = restoredUri))), reopened.state.value.draft)

            val oldVersion = if (application) 1 else 3
            val downgraded = withManifestVersion(zip, oldVersion)
            assertThrows(IllegalArgumentException::class.java) {
                downgraded.inputStream().use { ChatArchive.inspect(temp.newFolder(), it, application) }
            }
        }
    }

    private fun withManifestVersion(file: File, version: Int): ByteArray {
        val output = ByteArrayOutputStream()
        ZipFile(file).use { input ->
            ZipOutputStream(output).use { zip ->
                for (entry in input.entries()) {
                    zip.putNextEntry(ZipEntry(entry.name))
                    input.getInputStream(entry).use { data ->
                        if (entry.name == "manifest.json") {
                            val manifest = JSONObject(data.bufferedReader().readText())
                            zip.write(manifest.put("version", version).toString().toByteArray())
                        } else data.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}
