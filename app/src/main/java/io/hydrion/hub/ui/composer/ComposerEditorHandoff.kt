package io.hydrion.hub.ui.composer

internal enum class EditorHandoff { None, ClearFocus, TransferFocus }

/** Only the editor that currently owns an open keyboard can hand it to the other document. */
internal fun editorHandoff(
    outgoingFocused: Boolean,
    keyboardAvailable: Boolean,
): EditorHandoff = when {
    !outgoingFocused -> EditorHandoff.None
    keyboardAvailable -> EditorHandoff.TransferFocus
    else -> EditorHandoff.ClearFocus
}
