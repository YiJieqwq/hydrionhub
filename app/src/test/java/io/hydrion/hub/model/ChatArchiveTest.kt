package io.hydrion.hub.model

import java.io.*
import java.util.Base64
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatArchiveTest {
    @get:Rule val root = TemporaryFolder()
    private val external = "content://picked.test/documents/example"
    private val picked = Attachment(external, "example.txt", external, mimeType = "text/plain")
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j0ZkAAAAASUVORK5CYII=")
    private val key = "generated:${newId()}:0:1x1"
    private val original = "picked bytes".toByteArray()
    private val chat = Conversation(messages = listOf(Message(role = Role.Assistant, text = "saved",
        attachments = listOf(picked), imageKeys = listOf(key), error = "private-error-sentinel",
        parameters = mapOf("Authorization" to "private-header-sentinel"))), environmentId = "old-device")

    @Test fun archivedMediaAndContextSurvivePortableBackupWithoutOldUris() = runBlocking {
        val old = chat.messages.single()
        val visible = old.copy(text = "new revision", imageKeys = emptyList(), attachments = emptyList())
        val thread = chat.appendVersion(old, visible).copy(contextStart = old.id)
        val zip = ChatArchive.export(root.newFolder(), SavedWorkspace(listOf(thread)),
            { uri -> assertEquals(external, uri); original.inputStream() }, { id, out -> assertEquals(key, id); out.write(png) })
        ZipFile(zip).use { file ->
            val manifest = file.getInputStream(file.getEntry("manifest.json")).bufferedReader().use { it.readText() }
            assertEquals(3, org.json.JSONObject(manifest).getInt("version"))
        }
        val archive = zip.inputStream().use { ChatArchive.inspect(root.newFolder(), it) }
        val revived = archive.restored({ hash ->
            assertArrayEquals(original, archive.blobs.getValue(hash).readBytes()); "content://fresh.test/picked"
        }, { id ->
            assertArrayEquals(png, archive.blobs.getValue(archive.generated.getValue(id)).readBytes()); "local-image"
        }).history.single()
        assertEquals(old.id, revived.contextStart)
        assertEquals("new revision", revived.messages.single().text)
        val previous = revived.withVersion(old.id, 0).messages.single()
        assertEquals(listOf("local-image"), previous.imageKeys)
        assertEquals("content://fresh.test/picked", previous.attachments.single().uri)
        assertTrue(previous.parameters.isEmpty())
        assertNull(previous.error)
    }

    @Test fun freshStoreReadsBothDraftsAndMediaWithoutOriginalGrant() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(SavedWorkspace(listOf(chat), mapOf(chat.id to Draft("old draft"))))
        session.setDraft("unsent draft")
        session.addAttachment(picked)
        val pending = session.state.value.current.id
        assertNull(session.snapshot().unsent)
        val source = session.snapshot(includeUnsent = true)
        assertEquals(pending, source.unsent?.id)
        val zip = ChatArchive.export(root.newFolder(), source, { uri ->
            assertEquals(external, uri); original.inputStream()
        }, { image, out -> assertEquals(key, image); out.write(png) })
        ZipFile(zip).use { z ->
            val metadata = z.getInputStream(z.getEntry("workspace.json")).bufferedReader().readText()
            for (excluded in listOf(external, "Authorization", "private-header-sentinel", "private-error-sentinel", "old-device")) {
                assertFalse(metadata.contains(excluded))
            }
        }
        val freshFiles = root.newFolder()
        val preview = zip.inputStream().use { ChatArchive.inspect(root.newFolder(), it) }
        assertEquals(2, preview.blobs.size)
        val portable = preview.restored({ hash ->
            val dest = File(freshFiles, hash)
            installBackupFile(preview.blobs.getValue(hash), dest)
            "content://fresh.test/restored/${dest.name}"
        }, { id ->
            assertArrayEquals(png, preview.blobs.getValue(preview.generated.getValue(id)).readBytes())
            id
        })
        val freshSession = ConversationSession(this, FakeGenerationRepository {}, {})
        freshSession.adoptBackup(mergeBackup(freshSession.snapshot(true), portable))
        val reopened = ConversationSession(this, FakeGenerationRepository {}, {})
        reopened.restore(decodeWorkspace(encodeWorkspace(freshSession.snapshot())))
        assertEquals(pending, reopened.state.value.current.id)
        assertFalse(reopened.state.value.modeLocked)
        assertEquals("unsent draft", reopened.state.value.draft.text)
        val file = reopened.state.value.draft.attachments.single()
        assertTrue(file.uri!!.startsWith("content://fresh.test/restored/"))
        assertArrayEquals(original, File(freshFiles, file.uri.substringAfterLast('/')).readBytes())
        reopened.openConversation(chat.id)
        assertEquals("old draft", reopened.state.value.draft.text)
        assertEquals(key, reopened.state.value.current.messages.single().imageKeys.single())
        assertEquals(file, reopened.state.value.current.messages.single().attachments.single())
    }

    @Test fun reportsUnreadableOriginalBeforeProducingAnyBackup() = runBlocking {
        val dir = root.newFolder()
        val error = assertThrows(UnreadableOriginals::class.java) { runBlocking {
            ChatArchive.export(dir, SavedWorkspace(listOf(chat)), { throw SecurityException() }, { _, out -> out.write(png) })
        } }
        assertEquals(setOf("example.txt"), error.names)
        assertFalse(File(dir, "chats.zip").exists())
    }

    @Test fun corruptedPayloadMissingEntryAndUnsupportedVersionNeverPublish() = runBlocking {
        val zip = ChatArchive.export(root.newFolder(), SavedWorkspace(listOf(chat)), { original.inputStream() }, { _, out -> out.write(png) })
        val originalState = SavedWorkspace(listOf(Conversation(title = "existing")))
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(originalState)
        for (variation in 1..5) {
            val bad = File(root.newFolder(), "bad.zip")
            ZipFile(zip).use { source ->
                ZipOutputStream(bad.outputStream()).use { out ->
                    for (entry in source.entries().toList()) {
                        if (variation == 2 && entry.name.startsWith("blobs/")) continue
                        val name = if (variation == 4 && entry.name.startsWith("blobs/")) "../" + entry.name else entry.name
                        out.putNextEntry(ZipEntry(name))
                        var bytes = source.getInputStream(entry).readBytes()
                        if (variation == 1 && entry.name.startsWith("blobs/")) bytes[0] = (bytes[0].toInt() xor 1).toByte()
                        if (variation == 3 && entry.name == "manifest.json") bytes = org.json.JSONObject(String(bytes))
                            .put("version", 999).toString().toByteArray()
                        if (variation == 5 && entry.name == "workspace.json") bytes = "{}".toByteArray()
                        out.write(bytes); out.closeEntry()
                    }
                }
            }
            assertThrows(Exception::class.java) { bad.inputStream().use { ChatArchive.inspect(root.newFolder(), it) } }
            assertEquals(originalState, session.snapshot(true))
        }
    }

    @Test fun overlappingIdsAndTwoUnsentDraftsCannotReplaceExistingData() {
        val pending = Conversation()
        val before = SavedWorkspace(listOf(chat), mapOf(pending.id to Draft("local")), pending)
        assertThrows(IllegalArgumentException::class.java) { mergeBackup(before, SavedWorkspace(listOf(chat))) }
        val other = Conversation()
        assertThrows(IllegalArgumentException::class.java) {
            mergeBackup(before, SavedWorkspace(drafts = mapOf(other.id to Draft("import")), unsent = other))
        }
        assertEquals("local", before.drafts.getValue(pending.id).text)
    }
}
