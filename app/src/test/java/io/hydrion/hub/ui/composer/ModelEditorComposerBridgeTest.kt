package io.hydrion.hub.ui.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class ModelEditorComposerBridgeTest {
    private val selected = TextFieldValue("first line\nsecond", TextRange(9, 3))

    @Test fun onlyAnActuallyVisibleImeAndItsFocusedEditorRequestRestoration() {
        val bridge = ModelEditorComposerBridge()
        for (visible in listOf(false, true)) for (target in listOf(null, ModelEditorInput.Draft, ModelEditorInput.Search)) {
            val snapshot = ModelEditorComposerSnapshot(selected, visible, target, if (visible) 410 else 0)
            bridge.capture = { snapshot }
            bridge.restore(requireNotNull(bridge.captureAndDismiss()))
            assertEquals(selected.selection, bridge.pending?.draft?.selection)
            assertEquals(visible && target != null, bridge.expectIme)
            assertTrue(bridge.consume(snapshot))
            assertFalse(bridge.consume(snapshot))
            assertFalse(bridge.returnSettled)
            bridge.settled(snapshot)
            assertTrue(bridge.returnSettled)
        }
    }

    @Test fun oldFramesAndAbandonedKeyboardRequestsCannotCompleteANewerVisit() {
        val bridge = ModelEditorComposerBridge()
        val first = ModelEditorComposerSnapshot(selected, true, ModelEditorInput.Search, 410)
        val next = first.copy(focusedInput = ModelEditorInput.Draft)
        bridge.restore(first)
        bridge.restore(next)
        bridge.settled(first)
        assertFalse(bridge.returnSettled)
        assertFalse(bridge.consume(first))
        bridge.abandonImeRestore()
        assertFalse(bridge.consume(next))
        assertFalse(bridge.expectIme)
        bridge.updateProgress(next, .4f)
        assertEquals(.4f, bridge.returnProgress, 0f)
        bridge.finishReturn()
        bridge.updateProgress(next, 1f)
        assertEquals(.4f, bridge.returnProgress, 0f)
        assertNull(bridge.returning)
    }
}
