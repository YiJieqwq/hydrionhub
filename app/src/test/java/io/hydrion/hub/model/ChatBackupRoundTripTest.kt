package io.hydrion.hub.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatBackupRoundTripTest {
    @get:Rule val root = TemporaryFolder()

    // Two synthetic 1x1 RGB PNGs. Android's final bitmap decode is supplied by the device test.
    private val sourcePng = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQrjoAAAHxAU4a9qNiAAAAAElFTkSuQmCC")
    private val previousPng = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGOw3KQJAAI8ARX/pMlLAAAAAElFTkSuQmCC")

    private fun imageStore(dir: File) = GeneratedImageStore(dir) { bytes, sample ->
        sample == 1 && (bytes.contentEquals(sourcePng) || bytes.contentEquals(previousPng))
    }

    private suspend fun readPng(images: GeneratedImageStore, key: String) =
        ByteArrayOutputStream().use { out -> images.copyPng(key, out); out.toByteArray() }

    @Test fun previewThenInstallAndReopenV3ChatsDraftsAndSharedMedia() = runBlocking {
        val sourceImages = imageStore(root.newFolder("source-images"))
        val imageKey = sourceImages.saveBatch(listOf(sourcePng)).single()
        val picked = Attachment("source-note", "sample.txt", "content://fixture.invalid/note", mimeType = "text/plain")
        val pickedBytes = "source attachment".toByteArray()
        val question = Message(id = "q", role = Role.User, text = "Question", attachments = listOf(picked))
        val first = Message(id = "r", role = Role.Assistant, text = "First reply", imageKeys = listOf(imageKey), replyTo = question.id)
        val incoming = Conversation(id = "source-chat", title = "Source", pinned = true,
            messages = listOf(question, first), contextStart = question.id)
            .appendVersion(first, first.copy(text = "Revised reply", imageKeys = emptyList()))
        val pending = Conversation(id = "source-unsent")
        val snapshot = SavedWorkspace(listOf(incoming), mapOf(incoming.id to Draft("Follow up"),
            pending.id to Draft("Unsent draft", listOf(picked.copy(id = "draft-note")))), pending)

        val target = root.newFolder("target")
        val history = File(target, "conversation-history-v1.json")
        val attachments = File(target, "restored-attachments").apply { check(mkdir()) }
        val previousFile = File(attachments, "existing-file").apply { writeText("existing contents") }
        val imageDir = File(target, "generated-images")
        val targetImages = imageStore(imageDir)
        val previousImage = targetImages.saveBatch(listOf(previousPng)).single()
        val previous = Conversation(id = "existing-chat", title = "Existing", messages = listOf(
            Message(role = Role.Assistant, text = "Existing reply", imageKeys = listOf(previousImage))))
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(SavedWorkspace(listOf(previous), mapOf(previous.id to Draft("Existing draft"))))
        session.openConversation(previous.id)
        val before = session.snapshot(true)
        publishBackupFile(stageBackupHistory(history, before), history)
        val previousHistory = history.readBytes()
        val previousBatches = imageDir.list()!!.toSet()

        var timesOpened = 0
        val zip = ChatArchive.export(root.newFolder("export"), snapshot, { uri ->
            assertEquals(picked.uri, uri)
            timesOpened++
            pickedBytes.inputStream()
        }, sourceImages::copyPng)
        val preview = zip.inputStream().use { ChatArchive.inspect(root.newFolder("preview"), it) }
        assertEquals(3, preview.formatVersion)
        assertEquals(1, timesOpened)
        assertEquals(setOf(imageKey), preview.generated.keys)
        assertEquals(2, preview.blobs.size)
        targetImages.checkArchivePng(imageKey, preview.blobs.getValue(preview.generated.getValue(imageKey)))

        fun assertTargetUnchanged() {
            assertEquals(before, session.snapshot(true))
            assertArrayEquals(previousHistory, history.readBytes())
            assertEquals(setOf(previousFile.name), attachments.list()!!.toSet())
            assertEquals(previousBatches, imageDir.list()!!.toSet())
        }
        assertTargetUnchanged()
        val stagedNote = preview.workspace.history.single().messages.first().attachments.single()
        val hash = requireNotNull(stagedNote.uri).removePrefix("blob:")
        assertEquals("blob:$hash", preview.workspace.drafts.getValue(pending.id).attachments.single().uri)
        assertArrayEquals(pickedBytes, preview.blobs.getValue(hash).readBytes())

        restoreChatMedia(preview, attachments,
            pickedUri = { file -> "content://restored.invalid/${file.name}" },
            installGenerated = targetImages::installArchivePng, discardGenerated = targetImages::discard,
            beforeWrite = { assertTargetUnchanged() },
        ) { imported ->
            val merged = mergeBackup(session.snapshot(true), imported)
            val staged = stageBackupHistory(history, merged)
            assertArrayEquals(previousHistory, history.readBytes())
            publishBackupFile(staged, history)
            session.adoptBackup(merged)
        }

        val reopened = ConversationSession(this, FakeGenerationRepository {}, {})
        reopened.restore(decodeWorkspace(JSONObject(history.readText())))
        assertEquals(setOf(previous.id, incoming.id), reopened.state.value.history.map { it.id }.toSet())
        reopened.openConversation(previous.id)
        assertEquals(previous, reopened.state.value.current)
        assertEquals("Existing draft", reopened.state.value.draft.text)
        assertEquals("existing contents", previousFile.readText())
        val reopenedImages = imageStore(imageDir)
        assertArrayEquals(previousPng, readPng(reopenedImages, previousImage))

        reopened.openConversation(incoming.id)
        val imported = reopened.state.value.current
        assertTrue(imported.pinned)
        assertEquals(question.id, imported.contextStart)
        assertEquals("Follow up", reopened.state.value.draft.text)
        assertEquals("Revised reply", imported.messages.last().text)
        val oldReply = imported.withVersion(first.id, 0).messages.last()
        assertEquals("First reply", oldReply.text)
        assertEquals(first.replyTo, oldReply.replyTo)
        assertEquals(listOf(imageKey), oldReply.imageKeys)
        assertArrayEquals(sourcePng, readPng(reopenedImages, imageKey))
        val restoredUri = "content://restored.invalid/$hash"
        assertEquals(picked.copy(uri = restoredUri), imported.messages.first().attachments.single())
        reopened.newConversation()
        assertEquals(pending.id, reopened.state.value.current.id)
        assertEquals(Draft("Unsent draft", listOf(picked.copy(id = "draft-note", uri = restoredUri))), reopened.state.value.draft)
        assertEquals(setOf(previousFile.name, hash), attachments.list()!!.toSet())
        assertArrayEquals(pickedBytes, File(attachments, hash).readBytes())
    }
}
