package io.hydrion.hub.model

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RemoteHistoryTest {
    @Test fun routeAndUsageRoundTripWithLegacyMessages() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "history-test-${newId()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir(): File = directory }
        try {
            File(directory, "conversation-history-v1.json").writeText("""
                {"history":[{"id":"legacy","title":"Hello","mode":"Chat","updatedAt":1,
                "messages":[{"id":"1","role":"Assistant","text":"Hi","status":"Completed","createdAt":1}]}]}
            """.trimIndent())
            val history = HistoryFile(context)
            val legacy = history.load().history.single()
            assertNull(legacy.messages.single().remoteModel)
            assertNull(legacy.messages.single().totalTokens)
            val reply = Message(role = Role.Assistant, text = "response",
                remoteModel = RemoteModel("deepseek","deepseek-flash"), totalTokens = 123L)
            val saved = SavedWorkspace(listOf(legacy.copy(messages = legacy.messages + reply)))
            history.save(saved)
            assertEquals(saved, HistoryFile(context).load())
        } finally {
            directory.deleteRecursively()
        }
    }
}
