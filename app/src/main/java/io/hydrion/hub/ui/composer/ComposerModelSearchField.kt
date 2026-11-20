package io.hydrion.hub.ui.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.round
import io.hydrion.hub.provider.ChatModelPickerState

internal fun Modifier.trackPickerEditor(picker: ChatModelPickerState?, textOnly: Boolean = false): Modifier =
    if (picker == null) this else onGloballyPositioned {
        val bounds = IntRect(it.positionInWindow().round(), it.size)
        if (textOnly) picker.editorTextBounds = bounds else picker.editorBounds = bounds
    }

internal fun TextFieldValue.singleLineQuery(): TextFieldValue =
    if ('\n' in text || '\r' in text) copy(text = text.replace('\r', ' ').replace('\n', ' '), composition = null) else this

/** A soft IME with the draft's multiline options can commit a newline in place of an action. */
internal fun isQuerySubmitEdit(previous: TextFieldValue, updated: TextFieldValue): Boolean {
    if ('\n' !in updated.text && '\r' !in updated.text) return false
    val range = previous.selection
    val entered = updated.text.length - (previous.text.length - range.length)
    return entered in 1..2 && updated.text.regionMatches(0, previous.text, 0, range.min) &&
        updated.text.regionMatches(range.min + entered, previous.text, range.max, previous.text.length - range.max) &&
        updated.text.substring(range.min, range.min + entered) in setOf("\n", "\r", "\r\n")
}

// All editing roles share the same native editor, requester and IME options. Changing the
// displayed TextFieldValue preserves keyboard visibility, even during a page reversal.
@Composable
internal fun ComposerEditorSlot(
    searching: Boolean,
    editorFocus: FocusRequester,
    modelEditorBridge: ModelEditorComposerBridge?,
    readDraft: () -> TextFieldValue,
    restoreSelection: (TextRange) -> Unit,
    interactive: Boolean,
    dismissForModelEditor: () -> Unit,
    onEditorFocusChanged: (Boolean) -> Unit,
    modifier: Modifier,
    editor: @Composable (Modifier) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var placed by remember(modelEditorBridge) { mutableStateOf(false) }
    val placement = if (modelEditorBridge == null) Modifier else Modifier.onGloballyPositioned {
        placed = it.isAttached && it.size.width > 0 && it.size.height > 0
    }
    if (modelEditorBridge != null) {
        val input = if (searching) ModelEditorInput.Search else ModelEditorInput.Draft
        BindModelEditorComposer(
            modelEditorBridge, readDraft, restoreSelection,
            focusedInput = { input.takeIf { focused } }, visibleInput = input,
            focus = editorFocus, placed = placed, interactive = interactive, dismiss = dismissForModelEditor,
        )
    }
    Box(modifier.fillMaxWidth()) {
        editor(Modifier.focusRequester(editorFocus).onFocusChanged {
            focused = it.isFocused
            onEditorFocusChanged(it.isFocused)
        }.then(placement))
    }
}
