package io.hydrion.hub.ui.composer

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

internal enum class ModelEditorInput { Draft, Search }

internal data class ModelEditorComposerSnapshot(
    val draft: TextFieldValue,
    val imeVisible: Boolean,
    val focusedInput: ModelEditorInput?,
)

internal class ModelEditorComposerBridge {
    internal var capture: (() -> ModelEditorComposerSnapshot)? = null
    internal var pending by mutableStateOf<ModelEditorComposerSnapshot?>(null)
        private set
    fun captureAndDismiss(): ModelEditorComposerSnapshot? {
        pending = null
        return capture?.invoke()
    }

    fun restore(snapshot: ModelEditorComposerSnapshot) {
        pending = snapshot
    }

    internal fun consume(snapshot: ModelEditorComposerSnapshot): Boolean {
        if (pending !== snapshot) return false
        pending = null
        return true
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BindModelEditorComposer(
    bridge: ModelEditorComposerBridge,
    readDraft: () -> TextFieldValue,
    restoreSelection: (TextRange) -> Unit,
    focusedInput: () -> ModelEditorInput?,
    visibleInput: ModelEditorInput,
    focus: FocusRequester,
    placed: Boolean,
    interactive: Boolean,
    dismiss: () -> Unit,
) {
    val view = LocalView.current
    val imeVisible = WindowInsets.isImeVisible
    val capture by rememberUpdatedState {
        // Query the root insets at gesture time. Focus alone also occurs with a hidden or hardware keyboard.
        val visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) ?: imeVisible
        val source = focusedInput() ?: visibleInput.takeIf { visible }
        ModelEditorComposerSnapshot(readDraft().copy(composition = null), visible, source).also { dismiss() }
    }
    DisposableEffect(bridge) {
        bridge.capture = { capture() }
        onDispose { bridge.capture = null }
    }

    val pending = bridge.pending ?: return
    val current = readDraft()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focused = focusedInput()
    // Expire an unfulfilled show after this resumed source actually has a window.
    // A later gesture or a different visit must never revive it.
    LaunchedEffect(pending, interactive, placed, windowFocused, lifecycle) {
        if (interactive && placed && windowFocused && lifecycle == Lifecycle.State.RESUMED) {
            kotlinx.coroutines.delay(1_200)
            bridge.consume(pending)
        }
    }
    var focusRequested by remember(pending) { mutableStateOf(false) }
    LaunchedEffect(pending, current, visibleInput, placed, interactive, windowFocused, lifecycle, focused, focusRequested) {
        if (bridge.pending !== pending) return@LaunchedEffect
        if (current.text == pending.draft.text && current.selection != pending.draft.selection) {
            // Propagate the TextFieldValue before opening a new native input session.
            restoreSelection(pending.draft.selection)
            return@LaunchedEffect
        }
        if (!pending.imeVisible || pending.focusedInput == null || current.text != pending.draft.text) {
            bridge.consume(pending)
            return@LaunchedEffect
        }
        if (visibleInput == pending.focusedInput && interactive && placed && windowFocused && view.hasWindowFocus() && lifecycle == Lifecycle.State.RESUMED) {
            if (focused == visibleInput && bridge.consume(pending)) keyboard?.show()
            else if (!focusRequested) {
                focusRequested = true
                focus.requestFocus()
            }
        }
    }
}
