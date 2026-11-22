package io.hydrion.hub.model

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatBackupRollbackTest {
    @get:Rule val temp = TemporaryFolder()
    private val batch = "31415926-5358-4979-8323-846264338327"

    private fun key(index: Int) = "generated:$batch:$index:1x1"

    private fun imageFile(root: File, index: Int) = File(File(root, batch), "$index-1x1.png")

    private fun png(): File = temp.newFile("source.png").also {
        it.writeBytes(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAAD0lEQVR4AQEEAPv/AMjc8AUEApVF+VtoAAAAAElFTkSuQmCC"))
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun archive(png: File, generatedKeys: List<String>, attachments: List<File> = emptyList()): InspectedChatArchive {
        val pngDigest = digest(png)
        val picked = attachments.map { Attachment(it.name, it.name, uri = "blob:" + digest(it)) }
        val messages = listOf(Message(role = Role.User, attachments = picked),
            Message(role = Role.Assistant, imageKeys = generatedKeys))
        return InspectedChatArchive(
            workspace = SavedWorkspace(history = listOf(Conversation(messages = messages))),
            blobs = attachments.associateBy(::digest) + (pngDigest to png),
            generated = generatedKeys.associateWith { pngDigest },
            formatVersion = 1,
        )
    }

    @Test fun cancellationAfterLastGeneratedImageRollsBackBeforeCommit() = runBlocking {
        val source = png()
        val root = temp.newFolder("generated")
        val images = GeneratedImageStore(root)
        val dest = temp.newFolder("picked")
        var installed = false
        var committed = false
        val job = launch {
            val importJob = checkNotNull(coroutineContext[Job])
            restoreChatMedia(
                archive(source, listOf(key(0))), dest, pickedUri = { error("Unexpected attachment") },
                installGenerated = { imageKey, blob ->
                    installed = images.installArchivePng(imageKey, blob)
                    assertTrue(imageFile(root, 0).isFile)
                    importJob.cancel()
                    installed
                },
                discardGenerated = images::discard,
            ) { committed = true }
        }
        job.join()
        assertTrue(installed)
        assertTrue(job.isCancelled)
        assertFalse(committed)
        assertFalse(imageFile(root, 0).exists())
    }

    @Test fun commitFailureDiscardsOnlyNewAttachmentsAndGeneratedImages() = runBlocking {
        val source = png()
        val root = temp.newFolder("generated")
        val images = GeneratedImageStore(root)
        val dest = temp.newFolder("picked")
        val existing = temp.newFile("existing-source").apply { writeText("Existing attachment") }
        val incoming = temp.newFile("incoming-source").apply { writeText("Incoming attachment") }
        val existingDest = File(dest, digest(existing))
        val incomingDest = File(dest, digest(incoming))
        existing.copyTo(existingDest)
        val unrelated = File(dest, "unrelated").apply { writeText("Keep this file") }
        assertTrue(images.installArchivePng(key(0), source))
        assertTrue(images.installArchivePng(key(2), source))

        val rejected = IOException("Synthetic commit failure")
        val failure = runCatching {
            restoreChatMedia(
                archive(source, listOf(key(0), key(1)), listOf(existing, incoming)), dest,
                pickedUri = { it.toURI().toString() },
                installGenerated = images::installArchivePng,
                discardGenerated = images::discard,
            ) {
                assertArrayEquals(source.readBytes(), imageFile(root, 1).readBytes())
                assertArrayEquals(incoming.readBytes(), incomingDest.readBytes())
                throw rejected
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(rejected.message, failure?.message)
        assertFalse(incomingDest.exists())
        assertFalse(imageFile(root, 1).exists())
        assertArrayEquals(existing.readBytes(), existingDest.readBytes())
        assertArrayEquals(source.readBytes(), imageFile(root, 0).readBytes())
        assertArrayEquals(source.readBytes(), imageFile(root, 2).readBytes())
        assertEquals("Keep this file", unrelated.readText())
    }
}
