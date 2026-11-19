package io.hydrion.hub.model

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RemoteHistoryTest {
    @Test fun legacyBuiltinImageDraftUsesImageModelAndPreservesRecordedInvocation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "history-test-${newId()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir(): File = directory }
        try {
            val file = File(directory, "conversation-history-v1.json")
            file.writeText("""
                {"drafts":{
                    "old":{"text":"继续画","attachments":[{"id":"ref","name":"参考图","imageKey":"ref-1"}],
                        "search":"自定义搜索","imageTool":"模型内置图片生成"},
                    "custom":{"imageTool":"自定义图片生成"},"off":{"imageTool":"禁用"},"unset":{}},
                 "history":[{"id":"image","title":"图像","mode":"Image","updatedAt":1,"messages":[
                    {"id":"1","role":"User","text":"风景","status":"Completed","createdAt":1,
                        "imageInvocation":{"model":"old-image-model","parameters":{"size":"1024x1024"}}},
                    {"id":"2","role":"Assistant","replyTo":"1","status":"Completed","createdAt":2,
                        "tool":"图片生成 · 模型内置图片生成","imageKeys":["old-result"]}]}]}
            """.trimIndent())
            val history = HistoryFile(context)
            val loaded = history.load()
            assertEquals(Draft("继续画", listOf(Attachment("ref", "参考图", imageKey = "ref-1")),
                "自定义搜索", "自定义图片生成"), loaded.drafts.getValue("old"))
            assertEquals("自定义图片生成", loaded.drafts.getValue("custom").imageTool)
            assertEquals("禁用", loaded.drafts.getValue("off").imageTool)
            assertEquals(Draft(), loaded.drafts.getValue("unset"))
            val image = loaded.history.single()
            assertEquals(Mode.Image, image.mode)
            assertEquals(ImageInvocation("old-image-model", mapOf("size" to "1024x1024")),
                image.messages.first().imageInvocation)
            assertEquals("图片生成 · 模型内置图片生成", image.messages.last().tool)
            assertEquals(listOf("old-result"), image.messages.last().imageKeys)
            history.save(loaded)
            assertEquals(loaded, HistoryFile(context).load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun routeAndUsageRoundTripWithLegacyMessages() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "history-test-${newId()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir(): File = directory }
        try {
            val file = File(directory, "conversation-history-v1.json")
            file.writeText("""
                {"history":[{"id":"legacy","title":"Hello","mode":"Chat","updatedAt":1,
                "messages":[{"id":"1","role":"Assistant","text":"Hi","status":"Completed","createdAt":1},
                    {"id":"2","role":"Assistant","text":"Hi","status":"Completed","createdAt":2,
                    "remoteModel":{"providerId":"deepseek","modelId":"deepseek-flash"},"totalTokens":123}]}]}
            """.trimIndent())
            val history = HistoryFile(context)
            val legacy = history.load().history.single()
            val unmetered = legacy.messages.first()
            assertNull(unmetered.remoteModel)
            assertNull(unmetered.totalTokens)
            assertNull(unmetered.reportedUsage)
            val old = legacy.messages.last()
            assertEquals(RemoteModel("deepseek", "deepseek-flash"), old.remoteModel)
            assertEquals(123L, old.totalTokens)
            assertEquals(TokenUsage(totalTokens = 123), old.reportedUsage)
            val full = TokenUsage(inputTokens = 60, outputTokens = 11, totalTokens = 71,
                cachedInputTokens = 18, reasoningTokens = 0)
            val parts = TokenUsage(inputTokens = 16, outputTokens = 4)
            val partial = TokenUsage(outputTokens = 9, reasoningTokens = 3)
            val newReplies = listOf(full, parts, partial).map { usage ->
                Message(role = Role.Assistant, remoteModel = old.remoteModel, usage = usage)
            }
            val saved = SavedWorkspace(listOf(legacy.copy(messages = legacy.messages + newReplies)))
            history.save(saved)
            val written = JSONObject(file.readText()).getJSONArray("history").getJSONObject(0).getJSONArray("messages")
            assertEquals(123L, written.getJSONObject(1).getLong("totalTokens"))
            assertEquals(71L, written.getJSONObject(2).getLong("totalTokens"))
            assertEquals(20L, written.getJSONObject(3).getLong("totalTokens"))
            val loaded = HistoryFile(context).load().history.single().messages
            assertEquals(legacy.messages.map { it.reportedUsage }, loaded.take(2).map { it.reportedUsage })
            assertEquals(listOf(full, parts, partial), loaded.drop(2).map { it.usage })
            assertEquals(listOf(full, parts, partial), loaded.drop(2).map { it.reportedUsage })
            assertNull(loaded[3].usage?.totalTokens)
            assertNull(loaded[4].totalTokens)
            assertEquals(old.remoteModel, loaded[4].remoteModel)
        } finally {
            directory.deleteRecursively()
        }
    }
}
