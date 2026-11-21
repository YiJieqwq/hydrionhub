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
            val snapshot = ModelEditorComposerSnapshot(selected, visible, target)
            bridge.capture = { snapshot }
            bridge.restore(requireNotNull(bridge.captureAndDismiss()))
            assertEquals(selected.selection, bridge.pending?.draft?.selection)
            assertEquals(visible, bridge.pending?.imeVisible)
            assertEquals(target, bridge.pending?.focusedInput)
            assertTrue(bridge.consume(snapshot))
            assertFalse(bridge.consume(snapshot))
            assertNull(bridge.pending)
        }
    }

    @Test fun oldFocusRequestsCannotCompleteANewerVisit() {
        val bridge = ModelEditorComposerBridge()
        val first = ModelEditorComposerSnapshot(selected, true, ModelEditorInput.Search)
        val next = first.copy(focusedInput = ModelEditorInput.Draft)
        bridge.restore(first)
        bridge.restore(next)
        assertFalse(bridge.consume(first))
        assertSame(next, bridge.pending)
        bridge.captureAndDismiss()
        assertFalse(bridge.consume(next))
        assertNull(bridge.pending)
    }
}
