package io.hydrion.hub.ui.composer

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
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
    val imeTravel: Int,
)

internal class ModelEditorComposerBridge {
    internal var capture: (() -> ModelEditorComposerSnapshot)? = null
    internal var pending by mutableStateOf<ModelEditorComposerSnapshot?>(null)
        private set
    internal var returning by mutableStateOf<ModelEditorComposerSnapshot?>(null)
        private set
    internal var expectIme by mutableStateOf(false)
        private set
    var hasLayout by mutableStateOf(false)
        internal set
    var returnSettled by mutableStateOf(false)
        private set
    var returnProgress by mutableFloatStateOf(0f)
        private set

    fun captureAndDismiss(): ModelEditorComposerSnapshot? {
        pending = null
        returning = null
        returnSettled = false
        returnProgress = 0f
        return capture?.invoke()
    }

    fun restore(snapshot: ModelEditorComposerSnapshot) {
        returnSettled = false
        returnProgress = 0f
        expectIme = snapshot.imeVisible && snapshot.focusedInput != null
        returning = snapshot
        pending = snapshot
    }

    fun abandonImeRestore() {
        expectIme = false
        pending = null
    }

    fun finishReturn() {
        pending = null
        returning = null
    }

    internal fun updateProgress(snapshot: ModelEditorComposerSnapshot, progress: Float) {
        if (returning === snapshot) returnProgress = progress
    }

    internal fun settled(snapshot: ModelEditorComposerSnapshot) {
        if (returning !== snapshot) return
        returnProgress = 1f
        returnSettled = true
        returning = null
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
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    val imeVisible = WindowInsets.isImeVisible
    val capture by rememberUpdatedState {
        // Query the root insets at gesture time. Focus alone also occurs with a hidden or hardware keyboard.
        val visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) ?: imeVisible
        val travel = (ime.getBottom(density) - nav.getBottom(density)).coerceAtLeast(0)
        ModelEditorComposerSnapshot(readDraft().copy(composition = null), visible, focusedInput(), travel).also { dismiss() }
    }
    DisposableEffect(bridge) {
        bridge.capture = { capture() }
        onDispose { bridge.capture = null }
    }

    val pending = bridge.returning ?: return
    val current = readDraft()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focused = focusedInput()
    var focusRequested by remember(pending) { mutableStateOf(false) }
    LaunchedEffect(pending, current, visibleInput, placed, interactive, windowFocused, lifecycle, focused, focusRequested) {
        if (bridge.pending !== pending) return@LaunchedEffect
        if (current.text == pending.draft.text && current.selection != pending.draft.selection) {
            // Propagate the TextFieldValue before opening a new native input session.
            restoreSelection(pending.draft.selection)
            return@LaunchedEffect
        }
        if (!pending.imeVisible || visibleInput != pending.focusedInput || current.text != pending.draft.text) {
            bridge.abandonImeRestore()
            return@LaunchedEffect
        }
        if (interactive && placed && windowFocused && view.hasWindowFocus() && lifecycle == Lifecycle.State.RESUMED) {
            if (focused == visibleInput && bridge.consume(pending)) keyboard?.show()
            else if (!focusRequested) {
                focusRequested = true
                focus.requestFocus()
            }
        }
    }
}

@Composable
internal fun ObserveModelEditorReturn(bridge: ModelEditorComposerBridge, frame: ModelEditorReturnFrame?) {
    val snapshot = bridge.returning ?: return
    val expectIme = bridge.expectIme
    val waitingForInput = bridge.pending != null
    LaunchedEffect(snapshot, frame, expectIme, waitingForInput) {
        if (frame == null) return@LaunchedEffect
        bridge.updateProgress(snapshot, frame.progress(expectIme, snapshot.imeTravel))
        if (waitingForInput || !frame.atDestination(expectIme, snapshot.imeTravel)) return@LaunchedEffect
        // A legacy IME may report the current travel as both bounds and finish at a different
        // height than it had on entry. Require a longer run of identical measured frames there.
        val stableFrames = if (expectIme && frame.imeSource == frame.imeTarget) 4 else 2
        repeat(stableFrames) { withFrameNanos { } }
        bridge.settled(snapshot)
    }
}
