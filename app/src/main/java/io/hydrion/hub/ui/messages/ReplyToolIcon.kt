package io.hydrion.hub.ui.messages

import androidx.annotation.DrawableRes
import io.hydrion.hub.R
import io.hydrion.hub.json.JsonSyntax
import io.hydrion.hub.model.ASK_USER
import io.hydrion.hub.model.CUSTOM_IMAGE_TOOL
import io.hydrion.hub.model.ToolRecordText
import org.json.JSONException
import org.json.JSONObject

/** Presentation of recorded names. Older local replies store the selected tool's display label. */
@DrawableRes
internal fun replyToolIcon(name: String, arguments: String? = null): Int = when (name) {
    "shell", "local_shell_call", "bash_code_execution" -> R.drawable.lucide_terminal
    "code_interpreter_call", "code_interpreter", "code_execution", "text_editor_code_execution" -> R.drawable.lucide_code
    "computer_call" -> R.drawable.lucide_monitor

    "search_web", "网络搜索 · 自定义搜索" -> R.drawable.lucide_earth
    "web_search_call", "web_search", "web_search_preview", "builtin_search",
    "chat_search", "网络搜索 · 模型内置搜索" -> R.drawable.lucide_search
    "fetch_url", "web_fetch" -> R.drawable.lucide_link
    "browser" -> R.drawable.lucide_app_window

    "list_dir" -> R.drawable.lucide_folder_open
    "read_file" -> R.drawable.lucide_file_text
    "write_file" -> R.drawable.lucide_file_plus
    "edit_file" -> R.drawable.lucide_file_pen
    "file_search_call", "file_search", "glob" -> R.drawable.lucide_file_search
    "grep" -> R.drawable.lucide_text_search
    "image_generation_call", "generate_image", "view_image",
    "图片生成 · $CUSTOM_IMAGE_TOOL", "图片生成 · 模型内置图片生成" -> R.drawable.lucide_image

    ASK_USER -> R.drawable.lucide_message_circle_question
    "get_time_info" -> R.drawable.lucide_clock
    "calendar_create" -> R.drawable.lucide_calendar_plus
    "calendar_query" -> R.drawable.lucide_calendar
    "reminders_create" -> R.drawable.lucide_list_plus
    "reminders_query" -> R.drawable.lucide_list_todo
    "reminders_complete" -> R.drawable.lucide_circle_check
    "get_current_location" -> R.drawable.lucide_map_pin
    "get_weather" -> R.drawable.lucide_cloud_sun
    "get_health_summary" -> R.drawable.lucide_heart_pulse
    "get_screen_time", "phone_control" -> R.drawable.lucide_smartphone
    "calculate" -> R.drawable.lucide_calculator
    "text_to_speech" -> R.drawable.lucide_volume_2
    "clipboard_tool" -> clipboardIcon(arguments)

    "memory_delete", "delete_memory" -> R.drawable.lucide_book_dashed
    "memory_read", "memory_search_profile", "memory_update", "memory_edit",
    "update_user_profile", "create_memory", "edit_memory" -> R.drawable.lucide_book_heart
    else -> R.drawable.lucide_wrench
}

@DrawableRes
private fun clipboardIcon(arguments: String?): Int {
    if (arguments == null || arguments.length > ToolRecordText.limit) return R.drawable.lucide_clipboard
    // A saved record may be truncated. Only a complete top-level action distinguishes read and write.
    val action = try {
        JsonSyntax(arguments) { throw JSONException("Invalid clipboard arguments") }.check()
        JSONObject(arguments).opt("action")
    } catch (_: JSONException) { null }
    return when (action) {
        "read" -> R.drawable.lucide_clipboard_check
        "write" -> R.drawable.lucide_clipboard_pen
        else -> R.drawable.lucide_clipboard
    }
}
