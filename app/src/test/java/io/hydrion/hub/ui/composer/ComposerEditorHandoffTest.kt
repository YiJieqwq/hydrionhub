package io.hydrion.hub.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerEditorHandoffTest {
    @Test fun enteringOrExitingWithTheKeyboardDownNeverTransfersFocus() {
        assertEquals(EditorHandoff.None, editorHandoff(false, false))
        assertEquals(EditorHandoff.ClearFocus, editorHandoff(true, false))
    }

    @Test fun onlyTheFocusedEditorHandsOverAnOpenKeyboard() {
        assertEquals(EditorHandoff.TransferFocus, editorHandoff(true, true))
        assertEquals(EditorHandoff.None, editorHandoff(false, true))
    }
}
