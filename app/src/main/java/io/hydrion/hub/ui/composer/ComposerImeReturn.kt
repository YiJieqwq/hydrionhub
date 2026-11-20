package io.hydrion.hub.ui.composer

internal data class ComposerEditorSelection(val text: String, val start: Int, val end: Int)

/** Remembers a visible editor only for a stop/return of the same composer instance. */
internal class ComposerImeReturn {
    enum class Action { None, RestoreEditor, ClearEditorFocus }

    private var editingWithIme = false
    private var pendingReturn = false
    private var awaitingKeyboard = false
    private var cancelledWhileOpen = false
    private var dismissalStarted = false
    private var lastSelection: ComposerEditorSelection? = null

    fun selectionFor(text: String) = lastSelection?.takeIf { it.text == text }

    fun cancel() {
        lastSelection = null
        editingWithIme = false
        pendingReturn = false
        awaitingKeyboard = false
        dismissalStarted = false
        // The picker, send and explicit dismiss can run before focus or insets change.
        cancelledWhileOpen = true
    }

    fun onStop() {
        pendingReturn = pendingReturn || editingWithIme
        if (!pendingReturn) lastSelection = null
        editingWithIme = false
        awaitingKeyboard = false
    }

    fun observe(
        enabled: Boolean, interactive: Boolean, editorFocused: Boolean, imeShown: Boolean,
        opening: Boolean, dismissing: Boolean, clearAfterDismissal: Boolean, selection: ComposerEditorSelection,
    ): Action {
        if (!enabled) cancel()
        // Window focus loss (including a system overlay) never initiates a return.
        // Its transient zero insets must not erase the last interactive sample either.
        if (!interactive) return Action.None
        if (pendingReturn) {
            pendingReturn = false
            // Android may already have kept both focus and the IME. No subsequent inset or
            // focus change is guaranteed before another stop (or a genuine Back dismissal).
            editingWithIme = editorFocused && imeShown && !dismissing
            awaitingKeyboard = !editingWithIme
            return Action.RestoreEditor
        }

        if (!editorFocused || !imeShown) cancelledWhileOpen = false
        // A disappearing IME may briefly omit its animation bounds at a positive height.
        if (dismissing) dismissalStarted = true else if (opening || !imeShown) dismissalStarted = false
        editingWithIme = enabled && editorFocused && imeShown && !dismissalStarted && !cancelledWhileOpen
        if (editingWithIme) {
            lastSelection = selection
            awaitingKeyboard = false
        }
        return if (editorFocused && clearAfterDismissal && !awaitingKeyboard) Action.ClearEditorFocus
        else Action.None
    }
}
