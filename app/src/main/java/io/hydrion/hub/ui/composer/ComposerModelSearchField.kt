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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
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
import io.hydrion.hub.provider.chatModelPickerPreImeBack

internal fun Modifier.trackPickerEditor(picker: ChatModelPickerState?, search: Boolean): Modifier =
    if (picker == null) this else onGloballyPositioned {
        if ((picker.page == ChatModelPickerState.Page.Search) == search) {
            picker.editorBounds = IntRect(it.positionInWindow().round(), it.size)
        }
    }

// The inactive draft still reserves its multiline height. Its text, gestures and selection
// handles must not be exposed in model search.
private fun Modifier.withoutPlacement(): Modifier = clearAndSetSemantics { }.layout { child, constraints ->
    val measured = child.measure(constraints)
    layout(measured.width, measured.height) {}
}

@Composable
internal fun ComposerEditorSlot(
    picker: ChatModelPickerState?,
    minHeight: Dp,
    textStyle: TextStyle,
    draftFocus: FocusRequester,
    retainEditorFocus: () -> Boolean,
    onEditorFocusChanged: (Boolean) -> Unit,
    modifier: Modifier,
    draftEditor: @Composable (Modifier) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val searchFocus = remember(picker) { FocusRequester() }
    val searching = picker?.page == ChatModelPickerState.Page.Search
    var previousSearch by remember(picker) { mutableStateOf(false) }
    var draftFocused by remember { mutableStateOf(false) }
    var searchFocused by remember(picker) { mutableStateOf(false) }
    val editorFocused = draftFocused || searchFocused

    // Both native editors remain attached during a handoff. Transfer directly in the same
    // apply pass to avoid starting a new keyboard after disposing the active input session.
    SideEffect {
        if (previousSearch != searching) {
            when (editorHandoff(if (previousSearch) searchFocused else draftFocused, retainEditorFocus())) {
                EditorHandoff.TransferFocus -> (if (searching) searchFocus else draftFocus).requestFocus()
                EditorHandoff.ClearFocus -> focusManager.clearFocus()
                EditorHandoff.None -> Unit
            }
            previousSearch = searching
        }
        onEditorFocusChanged(editorFocused)
    }

    Box(modifier.fillMaxWidth()) {
        Box(if (searching) Modifier.withoutPlacement() else Modifier) {
            draftEditor(Modifier.focusRequester(draftFocus).focusProperties {
                canFocus = !searching || !previousSearch
            }.onFocusChanged { draftFocused = it.isFocused })
        }
        if (picker != null && (searching || previousSearch)) {
            // Keep the outgoing search field until focus transfers or the hidden keyboard is released.
            val visibility = if (searching) Modifier else Modifier.withoutPlacement()
            Box(Modifier.matchParentSize().then(visibility).padding(start = 15.dp, end = 16.dp)) {
                ComposerModelSearchField(picker, minHeight, textStyle,
                    Modifier.focusRequester(searchFocus).onFocusChanged { searchFocused = it.isFocused })
            }
        }
    }
}

@Composable
private fun ComposerModelSearchField(picker: ChatModelPickerState, minHeight: Dp, textStyle: TextStyle, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val hint = stringResource(R.string.chat_picker_search_hint)
    var attached by remember(picker) { mutableStateOf(true) }
    DisposableEffect(picker) { onDispose { attached = false } }
    fun submit() { if (attached) picker.submitSearch() }

    BasicTextField(
        value = picker.searchQuery,
        onValueChange = { if (attached) picker.updateSearchQuery(it) },
        modifier = Modifier.fillMaxWidth().testTag("chat-model-search-field").trackPickerEditor(picker, search = true)
            .semantics { contentDescription = hint }
            .chatModelPickerPreImeBack(true, picker::back).then(modifier)
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
