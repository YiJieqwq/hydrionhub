package io.hydrion.hub.provider

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeepSeekImageSourcesTest {
    @Test fun reopensPickerAndGeneratedImagesWithoutReturningLocalUris() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "copies").apply { mkdirs() }
        val file = File.createTempFile("picked", ".png", directory)
        try {
            val bytes = byteArrayOf(0x01, 0x02, 0x03)
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".images", file)
            val picker = Attachment("photo", "photo.png", uri = uri.toString(), mimeType = "image/png")
            val generated = Attachment("mock", "scene.png", imageKey = "landscape", width = 32, height = 24)
            val resolved = context.responsesImageSources(listOf(picker, generated, picker), deepSeekProvider)
            assertEquals("data:image/png;base64,AQID", resolved.getValue(picker))
            val png = Base64.decode(resolved.getValue(generated).substringAfter(','), Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            assertEquals(32, bounds.outWidth)
            assertEquals(24, bounds.outHeight)
        } finally {
            file.delete()
        }
    }

    @Test fun requestBodyMergesCustomOptionsButProtectsStreamAndTranscript() {
        val provider = deepSeekProvider
        val base = provider.models.first()
        val model = base.copy(profile = base.profile.copy(sampling = "never/strip",
            requestBody = """{"instructions":"Brief","max_output_tokens":80,"temperature":0.2,"stream":false,"input":"old","model":"old"}"""))
        val catalog = ModelCatalog(listOf(provider))
        val prompt = listOf(Message(role = Role.User, text = "Hi"))
        val body = catalog.responsesRequest(provider, model, prompt, "key").body
        assertEquals("Brief", body.getString("instructions"))
        assertEquals(80, body.getInt("max_output_tokens"))
        assertFalse(body.has("temperature"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(base.externalModelId, body.getString("model"))
        assertEquals("Hi", body.getJSONArray("input").getJSONObject(0).getString("content"))
        val tools = base.copy(profile = base.profile.copy(requestBody = """{"tools":[{"type":"function"}]}"""))
        assertTrue(runCatching { catalog.responsesRequest(provider, tools, prompt, "key") }.isFailure)
    }

    @Test fun openAiCustomBodyCannotStartBackgroundToolsOrUseAStoredConversation() {
        val provider = deepSeekProvider.copy(id = "example-responses", baseUrl = "https://example.test/v1")
        val base = provider.models.first()
        val catalog = ModelCatalog(listOf(provider))
        val history = listOf(Message(role = Role.User, text = "local turn"))
        fun body(template: String) = catalog.responsesRequest(provider,
            base.copy(profile = base.profile.copy(requestBody = template)), history, "fixture").body

        val request = body("""{"store":true,"stream":false,"input":"old","max_output_tokens":64}""")
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getBoolean("stream"))
        assertEquals(64, request.getInt("max_output_tokens"))
        assertEquals("local turn", request.getJSONArray("input").getJSONObject(0).getString("content"))
        for (unsupported in listOf("""{"background":true}""", """{"tools":[{"type":"web_search"}]}""",
            """{"tool_choice":"required"}""", """{"previous_response_id":"resp-old"}""",
            """{"conversation":"conv-old"}""", """{"prompt":{"id":"pmpt-old"}}""")) {
            assertTrue(unsupported, runCatching { body(unsupported) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun chatCompletionsProtectsMessagesAndUsageFromCustomFields() {
        val provider = deepSeekProvider.copy(id = "configured-chat", baseUrl = "https://example.test/v1",
            protocol = ChatProtocol.ChatCompletionsHttp.label)
        val model = provider.models.first()
        val catalog = ModelCatalog(listOf(provider))
        val transcript = listOf(Message(role = Role.User, text = "current message"))
        fun body(template: String) = catalog.chatCompletionsRequest(provider,
            model.copy(profile = model.profile.copy(requestBody = template)), transcript, "fixture").body
        val result = body("""{"messages":[],"stream":false,"store":true,"stream_options":{"include_usage":false}}""")
        assertEquals("current message", result.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertTrue(result.getBoolean("stream"))
        assertFalse(result.getBoolean("store"))
        assertTrue(result.getJSONObject("stream_options").getBoolean("include_usage"))
        for (unsupported in listOf("""{"tools":[{"type":"function"}]}""", """{"web_search_options":{}}""",
            """{"functions":[{"name":"hidden"}]}""", """{"background":true}""", """{"modalities":["audio"]}""")) {
            assertTrue(unsupported, runCatching { body(unsupported) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
