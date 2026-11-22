package io.hydrion.hub.model

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyChatBackupTest {
    @get:Rule val stage = TemporaryFolder()
    private val picked = "synthetic notes".toByteArray()
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAAD0lEQVR4AQEEAPv/AMjc8AUEApVF+VtoAAAAAElFTkSuQmCC"
    )
    private val image = "generated:00000000-0000-4000-8000-000000000001:0:1x1"

    // Portable fields emitted by ChatArchive.export and HistoryFile.encodeWorkspace at af48fbb.
    private fun archiveV1(includePicked: Boolean = true): ByteArray {
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val pickedHash = sha(picked)
        val imageHash = sha(png)
        val workspace = """
            {
              "history": [{
                "id":"v1-chat","title":"Old notes","mode":"Chat","pinned":true,"updatedAt":30,
                "messages":[
                  {"id":"question","role":"User","text":"Old question","status":"Completed","createdAt":10,
                   "model":"Fixture","parameters":{},"attachments":[{"id":"note","name":"notes.txt",
                   "uri":"blob:$pickedHash","mimeType":"text/plain"}],"prompt":"","thought":"","imageKeys":[]},
                  {"id":"answer","role":"Assistant","text":"Old answer","status":"Completed","createdAt":20,
                   "model":"Fixture","parameters":{},"attachments":[],"prompt":"Old question","thought":"",
                   "imageKeys":["$image"],"replyTo":"question","totalTokens":5,
                   "usage":{"inputTokens":2,"outputTokens":3,"totalTokens":5}}
                ]
              }],
              "drafts":{"v1-unsent":{"text":"Unsent caption","search":"模型内置搜索","imageTool":"自定义图片生成",
                "attachments":[{"id":"draft-image","name":"pixel.png","imageKey":"$image","mimeType":"image/png","width":1,"height":1}]}},
              "unsent":{"id":"v1-unsent","title":"Unsent","mode":"Chat","pinned":false,"updatedAt":40,"messages":[]}
            }
        """.trimIndent().toByteArray()
        val manifest = """{"format":"hydrion.chat","version":1,"workspaceSha256":"${sha(workspace)}","generated":{"$image":"$imageHash"}}"""
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, contents: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents)
                zip.closeEntry()
            }
            entry("manifest.json", manifest.toByteArray())
            entry("workspace.json", workspace)
            if (includePicked) entry("blobs/$pickedHash", picked)
            entry("blobs/$imageHash", png)
        }
        return bytes.toByteArray()
    }

    @Test fun restoresV1HistoryUnsentDraftAndBothKindsOfOriginalMedia() = runBlocking {
        val preview = ChatArchive.inspect(stage.newFolder(), archiveV1().inputStream())
        assertEquals(1, preview.formatVersion)
        assertNull(preview.settings)
        assertEquals(2, preview.blobs.size)
        assertEquals(setOf(image), preview.generated.keys)
        val state = preview.restored({ hash ->
            assertArrayEquals(picked, preview.blobs.getValue(hash).readBytes())
            "restored-picked"
        }, { key ->
            assertEquals(image, key)
            assertArrayEquals(png, preview.blobs.getValue(preview.generated.getValue(key)).readBytes())
            "restored-image"
        })
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.adoptBackup(mergeBackup(session.snapshot(true), state))
        assertEquals("v1-unsent", session.state.value.current.id)
        val draft = session.state.value.draft
        assertEquals("Unsent caption", draft.text)
        assertEquals("模型内置搜索", draft.search)
        assertEquals("自定义图片生成", draft.imageTool)
        assertEquals(Attachment("draft-image", "pixel.png", imageKey = "restored-image",
            mimeType = "image/png", width = 1, height = 1), draft.attachments.single())

        session.openConversation("v1-chat")
        val chat = session.state.value.current
        assertEquals("Old notes", chat.title)
        assertTrue(chat.pinned)
        assertEquals(LocalAssistant.Default.id, chat.assistantId)
        assertTrue(chat.versions.isEmpty())
        assertNull(chat.contextStart)
        assertEquals(listOf(Role.User, Role.Assistant), chat.messages.map { it.role })
        val (question, answer) = chat.messages
        assertEquals("Old question", question.text)
        assertEquals(Attachment("note", "notes.txt", "restored-picked", mimeType = "text/plain"), question.attachments.single())
        assertEquals(question.id, answer.replyTo)
        assertEquals("Old answer", answer.text)
        assertEquals(Generation.Completed, answer.status)
        assertEquals(TokenUsage(inputTokens = 2, outputTokens = 3, totalTokens = 5), answer.usage)
        assertEquals(listOf("restored-image"), answer.imageKeys)
    }

    @Test fun missingV1AttachmentIsReportedBeforeChangingExistingChat() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(SavedWorkspace(history = listOf(Conversation(id = "existing", title = "Keep me"))))
        session.openConversation("existing")
        session.setDraft("Keep this draft")
        val before = session.snapshot(true)
        val error = assertThrows(UnreadableOriginals::class.java) {
            val preview = ChatArchive.inspect(stage.newFolder(), archiveV1(includePicked = false).inputStream())
            session.adoptBackup(mergeBackup(before, preview.restored({ it }, { it })))
        }
        assertEquals(setOf("notes.txt"), error.names)
        assertEquals(before, session.snapshot(true))
        assertEquals("existing", session.state.value.current.id)
    }
}
