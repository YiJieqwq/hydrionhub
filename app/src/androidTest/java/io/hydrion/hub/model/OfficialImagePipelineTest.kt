package io.hydrion.hub.model

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.ModelType
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.imageInvocation
import io.hydrion.hub.provider.prepareOfficialImageGeneration
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficialImagePipelineTest {
    @Test fun bothImageEntryPointsPostToTheSelectedModelAndReloadTheOriginalPng() = runBlocking(Dispatchers.IO) {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAEUlEQVR4nGPg9P34/4tW2H8AEwYEunyvEL8AAAAASUVORK5CYII="
        val expected = Base64.getDecoder().decode(png)
        val posts = ConcurrentLinkedQueue<Pair<Request, String>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            posts.add(request to body)
            // This application interceptor answers before DNS, TLS or any network interceptor.
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[{"b64_json":"$png"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "official-image-pipeline-")
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory.toFile() }
        try {
            val model = ProviderModel("illustrator", "Studio", profile = ModelProfile(type = ModelType.Image, externalId = "gpt-image-1.5"))
            val provider = Provider("studio", Mode.Image, "Studio", "OpenAI Images", "https://api.openai.com/v1",
                listOf(model), requestBody = """{"model":"{{model}}","prompt":"{{prompt}}","n":1}""")
            val route = RemoteModel(provider.id, model.id)
            val catalog = ModelCatalog(listOf(provider), active = mapOf(Mode.Image to route))
            val store = GeneratedImageStore(context)
            val other = object : GenerationRepository {
                override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>): Flow<Message> =
                    error("Image generation was routed to the chat or demo backend")
            }
            val repository = ImageGenerationRepository(other, { image, prompt, _ ->
                catalog.prepareOfficialImageGeneration(requireNotNull(image.remoteModel), prompt, image.parameters) { "offline-fixture" }
            }, OpenAiImagesTransport(client)::generate, store::saveBatch, store::discard)

            val conversations = listOf(Mode.Image, Mode.Chat).map { mode ->
                val prompt = "Test image from ${mode.name}"
                val image = catalog.imageInvocation(prompt)
                assertEquals(route, image.remoteModel)
                val message = Message(role = Role.Assistant, status = Generation.Sending, prompt = prompt, imageInvocation = image,
                    model = if (mode == Mode.Chat) "Hydrion" else image.model,
                    tool = if (mode == Mode.Chat) "图片生成 · $CUSTOM_IMAGE_TOOL" else null)
                val events = repository.generate(mode, message, false, emptyList()).toList()
                assertEquals(listOf(Generation.Generating, Generation.Completed), events.map { it.status })
                assertTrue(events.first().imageKeys.isEmpty())
                assertTrue(GeneratedImageStore.isStored(events.last().imageKeys.single()))
                Conversation(mode = mode, messages = listOf(events.last()))
            }
            assertEquals(2, posts.size)
            val replies = conversations.map { it.messages.single() }
            assertNotEquals(replies[0].imageKeys.single(), replies[1].imageKeys.single())
            for ((post, message) in posts.zip(replies)) {
                val (request, payload) = post
                assertEquals("POST", request.method)
                assertEquals("https://api.openai.com/v1/images/generations", request.url.toString())
                assertEquals("Bearer offline-fixture", request.header("Authorization"))
                val wire = JSONObject(payload)
                assertEquals("gpt-image-1.5", wire.getString("model"))
                assertEquals(message.prompt, wire.getString("prompt"))
                assertEquals("png", wire.getString("output_format"))
                assertEquals(1, wire.getInt("n"))
            }

            HistoryFile(context).save(SavedWorkspace(history = conversations))
            val restored = HistoryFile(context).load()
            assertEquals(conversations, restored.history)
            val reopened = GeneratedImageStore(context)
            for (conversation in restored.history) {
                val key = conversation.messages.single().imageKeys.single()
                assertEquals(2 to 1, GeneratedImageStore.dimensions(key))
                val bytes = ByteArrayOutputStream()
                reopened.copyPng(key, bytes)
                assertArrayEquals(expected, bytes.toByteArray())
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
