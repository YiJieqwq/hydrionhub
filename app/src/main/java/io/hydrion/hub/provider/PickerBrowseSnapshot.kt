package io.hydrion.hub.provider

import androidx.compose.ui.text.input.TextFieldValue

internal data class PickerScrollAnchor(val key: String?, val index: Int, val offset: Int) {
    fun resolveIndex(keys: List<String>): Int {
        val match = if (key == null) -1 else keys.indexOf(key)
        return if (match >= 0) match else index.coerceIn(0, keys.lastIndex.coerceAtLeast(0))
    }
}

// Each visit is a distinct restore request even when its recorded values match an earlier visit.
internal class PickerBrowseSnapshot(
    val page: ChatModelPickerState.Page,
    val query: TextFieldValue,
    val browsing: PickerScrollAnchor,
    val filtered: PickerScrollAnchor,
    val menuHeightDp: Float,
)
