package io.hydrion.hub.ui.composer

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import io.hydrion.hub.R
import io.hydrion.hub.provider.ChatModelPickerState
import io.hydrion.hub.provider.chatPickerSearchInput

@Composable
internal fun ComposerEditorSlot(
    picker: ChatModelPickerState?,
    minHeight: Dp,
    textStyle: TextStyle,
    draftEditor: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        // Keep the draft editor and its measured slot. Only the placed editor receives touches,
        // and changing between a multiline draft and a single-line query cannot shift the menu.
        val draftPlacement = if (picker == null) Modifier else Modifier.clearAndSetSemantics { }
            .layout { measurable, constraints ->
                val draft = measurable.measure(constraints)
                layout(draft.width, draft.height) { }
            }
        Box(draftPlacement) { draftEditor() }
        if (picker != null) {
            Box(Modifier.matchParentSize().padding(start = 15.dp, end = 16.dp)) {
                ComposerModelSearchField(picker, minHeight, textStyle)
            }
        }
    }
}

@Composable
private fun ComposerModelSearchField(picker: ChatModelPickerState, minHeight: Dp, textStyle: TextStyle) {
    val colors = MaterialTheme.colorScheme
    val hint = stringResource(R.string.chat_picker_search_hint)
    var attached by remember(picker) { mutableStateOf(true) }
    DisposableEffect(picker) {
        onDispose {
            attached = false
            picker.searchFieldBounds = null
        }
    }
    fun searching() = attached && picker.page == ChatModelPickerState.Page.Search
    fun submit() { if (searching()) picker.submitSearch() }

    BasicTextField(
        value = picker.searchQuery,
        onValueChange = { if (searching()) picker.updateSearchQuery(it) },
        modifier = Modifier.fillMaxWidth().testTag("chat-model-search-field")
            .onGloballyPositioned {
                if (searching()) picker.searchFieldBounds = IntRect(it.positionInWindow().round(), it.size)
            }
            .semantics { contentDescription = hint }
            .chatPickerSearchInput(picker)
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.keyCode != KeyEvent.KEYCODE_ENTER && key.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) false
                else {
                    if (key.action == KeyEvent.ACTION_UP && !key.isCanceled) submit()
                    true
                }
            },
        singleLine = true,
        textStyle = textStyle.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
        decorationBox = { input ->
            Row(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(top = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (picker.searchQuery.text.isEmpty()) Text(hint, style = textStyle, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    input()
                }
            }
        },
    )
}
