package io.hydrion.hub.ui.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun ComposerImeReturnEffect(
    controller: ComposerImeReturn,
    enabled: Boolean,
    editorFocused: Boolean,
    windowFocused: Boolean,
    imeShown: Boolean,
    opening: Boolean,
    dismissing: Boolean,
    clearAfterDismissal: Boolean,
    selection: ComposerEditorSelection,
    onRestore: () -> Unit,
    onDismissed: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val restore by rememberUpdatedState(onRestore)
    val dismiss by rememberUpdatedState(onDismissed)
    var resumeCount by remember(lifecycle, controller) { mutableIntStateOf(0) }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.onStop()
                Lifecycle.Event.ON_RESUME -> resumeCount++
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Lifecycle and window focus can arrive in either order. No polling, delay or IME retry.
    LaunchedEffect(controller, resumeCount, enabled, windowFocused, editorFocused, imeShown, opening, dismissing,
        clearAfterDismissal, selection) {
        val interactive = lifecycle.currentState == Lifecycle.State.RESUMED && windowFocused && view.hasWindowFocus()
        val action = controller.observe(
            enabled = enabled, interactive = interactive, editorFocused = editorFocused, imeShown = imeShown,
            opening = opening, dismissing = dismissing, clearAfterDismissal = clearAfterDismissal, selection = selection,
        )
        when (action) {
            ComposerImeReturn.Action.RestoreEditor -> restore()
            ComposerImeReturn.Action.ClearEditorFocus -> dismiss()
            ComposerImeReturn.Action.None -> Unit
        }
    }
}
