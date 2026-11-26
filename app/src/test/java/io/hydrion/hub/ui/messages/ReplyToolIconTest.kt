package io.hydrion.hub.ui.messages

import io.hydrion.hub.R
import io.hydrion.hub.model.ASK_USER
import io.hydrion.hub.model.ReplyToolCall
import io.hydrion.hub.model.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyToolIconTest {
    @Test fun protocolNamesAndSavedLocalLabelsResolveByExactName() {
        val cases = mapOf(
            R.drawable.lucide_terminal to listOf("local_shell_call", "shell", "bash_code_execution", "workspace_shell"),
            R.drawable.lucide_search to listOf("web_search_call", "web_search_preview", "chat_search", "网络搜索 · 模型内置搜索"),
            R.drawable.lucide_earth to listOf("search_web", "网络搜索 · 自定义搜索"),
            R.drawable.lucide_image to listOf("image_generation_call", "view_image", "图片生成 · 自定义图片生成", "图片生成 · 模型内置图片生成"),
            R.drawable.lucide_link to listOf("fetch_url", "web_fetch"),
            R.drawable.lucide_code to listOf("code_interpreter_call", "text_editor_code_execution"),
            R.drawable.lucide_file_search to listOf("glob", "file_search_call"),
            R.drawable.lucide_monitor to listOf("computer_call"),
            R.drawable.lucide_message_circle_question to listOf(ASK_USER),
            R.drawable.lucide_book_heart to listOf("memory_read", "create_memory"),
            R.drawable.lucide_book_dashed to listOf("memory_delete", "delete_memory"),
            R.drawable.lucide_wrench to listOf("", "catalog_lookup", "mcp__example__shell", "search_web_backup", "unknown_read_file")
        )
        for ((expected, names) in cases) for (name in names) assertEquals(name, expected, replyToolIcon(name))
    }

    @Test fun shellPreviewUsesOnlyActualTypedFieldsOnRecognizedCalls() {
        fun record(result: String?, name: String = "shell") = ReplyToolCall(name, ToolCallState.Completed, result = result).shellPreview()
        val ok = record("""{"exitCode":0,"stdout":"mint"}""")!!
        assertEquals(0, ok.exitCode)
        assertEquals("mint", ok.stdout)
        assertNull(record("""{"stderr":"sample"}"""))
        assertNull(record("""{"exitCode":"0","stdout":[]}"""))
        assertNull(record("""{"exitCode":4294967296}"""))
        assertNull(record("""{"exitCode":0}…"""))
        assertNull(record("exit 0"))
        assertNull(record("""{"exitCode":0,"stdout":"sample"}""", "catalog_lookup"))
        assertNull(record("""{"stdout":"done"}""")!!.exitCode)
        assertNull(record("""{"exit_code":2,"stdout":""}""")!!.stdout)
        assertEquals(2, record("""{"return_code":2}""", "bash_code_execution")!!.exitCode)
    }

    @Test fun clipboardDistinguishesOnlyRecordedTopLevelActions() {
        fun icon(json: String?) = replyToolIcon("clipboard_tool", json)
        assertEquals(R.drawable.lucide_clipboard_check, icon("""{"action":"read"}"""))
        assertEquals(R.drawable.lucide_clipboard_pen, icon("""{"text":"read","action":"write"}"""))
        for (arguments in listOf(null, "{}", """{"action":"other"}""", """{"nested":{"action":"read"}}""",
            """{"action":"read","text":""", """{"action":"write"}…""", """[{"action":"read"}]""")) {
            assertEquals(arguments, R.drawable.lucide_clipboard, icon(arguments))
        }
        assertEquals(R.drawable.lucide_wrench, replyToolIcon("other_tool", """{"action":"write"}"""))
    }
}
