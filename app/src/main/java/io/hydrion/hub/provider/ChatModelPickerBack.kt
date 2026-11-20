package io.hydrion.hub.provider

import android.os.Build
import android.view.KeyEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.TextFieldValue

@Composable
internal fun ChatModelPickerBack(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PickerGestureBack(onBack)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun PickerGestureBack(onBack: () -> Unit) {
    val view = LocalView.current
    val dismiss by rememberUpdatedState(onBack)
    DisposableEffect(view) {
        val dispatcher = view.findOnBackInvokedDispatcher()
        val callback = OnBackInvokedCallback { dismiss() }
        // The picker sits above an active IME. Only the highest-priority callback receives a gesture.
        dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.chatModelPickerPreImeBack(enabled: Boolean, onBack: () -> Unit): Modifier {
    if (!enabled) return this
    // Some platform versions/configurations deliver Back as a key. Consume the sequence before
    // the IME or the activity sees it and dismiss one level only on a committed release.
    return onPreInterceptKeyBeforeSoftKeyboard { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode != KeyEvent.KEYCODE_BACK) false else when (key.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> {
                if (!key.isCanceled) onBack()
                true
            }
            else -> false
        }
    }
}

internal data class PickerDraftFocus(
    val focused: Boolean,
    val softwareKeyboardShown: Boolean,
    val editingLayout: Boolean,
    val expansion: Float,
    val value: TextFieldValue,
)

/** Per-conversation state for the single explicit transfer of focus to model search. */
internal class ChatPickerSearchFocus {
    var readDraft: (() -> PickerDraftFocus)? = null
    var origin by mutableStateOf<PickerDraftFocus?>(null)
        private set
    private var searchWasFocused = false

    fun capture() {
        if (origin == null) origin = readDraft?.invoke()
    }

    fun onSearchFocused() { searchWasFocused = true }

    fun finish(draftFocused: Boolean = true): PickerDraftFocus? {
        // The draft can lose focus before a search field has a chance to take it.
        val saved = origin?.takeIf { searchWasFocused || (it.focused && !draftFocused) }
        origin = null
        searchWasFocused = false
        return saved
    }
}

internal fun Modifier.chatPickerSearchInput(picker: ChatModelPickerState): Modifier = composed {
    val requester = remember(picker) { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(picker, windowFocused) {
        if (windowFocused && picker.page == ChatModelPickerState.Page.Search) {
            picker.searchFocus.capture()
            requester.requestFocus()
        }
    }
    this.chatModelPickerPreImeBack(true, picker::back).focusRequester(requester).onFocusChanged {
        if (it.isFocused && picker.page == ChatModelPickerState.Page.Search) picker.searchFocus.onSearchFocused()
    }
}
