package io.hydrion.hub.model

import io.hydrion.hub.provider.LocalModelRequest
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageGenerationRepositoryTest {
    private val invocation = ImageInvocation("studio", mapOf("model" to "gpt-image-2", "n" to "1"), RemoteModel("openai-images", "studio"))
    private val sent = Message(role = Role.Assistant, status = Generation.Sending, prompt = "a picture",
        imageInvocation = invocation, tool = "图片生成 · $CUSTOM_IMAGE_TOOL")
    private val input = LocalModelRequest("studio", emptyMap(), JSONObject().put("n", 1), invocation.parameters)
    private val unexpectedChat = object : GenerationRepository {
        override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>): Flow<Message> =
            error("Remote image reached a chat or simulated transport")
    }

    @Test fun imageModeAndLocalChatStoreTheSameBytesBeforePublishingAnyResult() = runBlocking {
        val png = byteArrayOf(42)
        var posts = 0
        var saves = 0
        val repo = ImageGenerationRepository(unexpectedChat, { image, prompt, files ->
            assertEquals(invocation, image)
            assertEquals("a picture", prompt)
            assertTrue(files.isEmpty())
            input
        }, { request ->
            assertSame(input, request)
            posts++
            listOf(png)
        }, { images ->
            assertArrayEquals(png, images.single())
            saves++
            listOf("saved-key")
        }, { error("unexpected discard") })
        for (mode in Mode.entries) {
            val results = repo.generate(mode, if (mode == Mode.Image) sent.copy(tool = null) else sent, false, emptyList()).toList()
            assertEquals(listOf(Generation.Generating, Generation.Completed), results.map { it.status })
            assertTrue(results.first().imageKeys.isEmpty())
            assertEquals(listOf("saved-key"), results.last().imageKeys)
        }
        assertEquals(2, posts)
        assertEquals(2, saves)
    }

    @Test fun invalidInvocationOrReferenceIsRejectedBeforePreparationAndSend() = runBlocking {
        val repo = ImageGenerationRepository(unexpectedChat, { _, _, _ -> error("unexpected preparation") },
            { error("unexpected network") }, { error("unexpected write") }, { error("unexpected discard") })
        for (bad in listOf(sent.copy(attachments = listOf(Attachment("1", "ref"))), sent.copy(tool = "search"),
            sent.copy(remoteModel = RemoteModel("chat", "model")))) {
            val failure = runCatching { repo.generate(Mode.Chat, bad, false, emptyList()).toList() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Test fun anApiErrorIsPropagatedWithoutAResultOrLocalImage() = runBlocking {
        val received = mutableListOf<Message>()
        val repo = ImageGenerationRepository(unexpectedChat, { _, _, _ -> input }, { throw IOException("HTTP 429") },
            { error("unexpected write") }, { error("unexpected discard") })
        val failure = runCatching { repo.generate(Mode.Image, sent, false, emptyList()).toList(received) }.exceptionOrNull()
        assertEquals("HTTP 429", failure?.message)
        assertEquals(Generation.Generating, received.single().status)
        assertTrue(received.single().imageKeys.isEmpty())
    }

    @Test fun stoppingDuringTransferDoesNotPublishABatchOrReplayTheRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val store = ImageGenerationRepository(unexpectedChat, { _, _, _ -> input }, {
            calls++
            started.complete(Unit)
            awaitCancellation()
        }, { error("unexpected write") }, { error("unexpected discard") })
        val events = mutableListOf<Message>()
        val job = launch { store.generate(Mode.Image, sent, false, emptyList()).toList(events) }
        started.await()
        job.cancelAndJoin()
        assertEquals(1, calls)
        assertEquals(listOf(Generation.Generating), events.map { it.status })
    }

    @Test fun cancelledDeliveryReleasesUnacceptedStoredBatch() = runBlocking {
        val discarded = mutableListOf<String>()
        val store = ImageGenerationRepository(unexpectedChat, { _, _, _ -> input }, { listOf(byteArrayOf(42)) },
            { listOf("unaccepted") }, { discarded.addAll(it) })
        val job = launch {
            store.generate(Mode.Image, sent, false, emptyList()).collect { result ->
                if (result.status == Generation.Completed) throw kotlinx.coroutines.CancellationException("screen closed")
            }
        }
        job.join()
        assertEquals(listOf("unaccepted"), discarded)
    }
}
