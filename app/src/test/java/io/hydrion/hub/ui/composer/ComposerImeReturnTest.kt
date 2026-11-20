package io.hydrion.hub.ui.composer

import io.hydrion.hub.ui.composer.ComposerImeReturn.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerImeReturnTest {
    private val controller = ComposerImeReturn()
    private val range = ComposerEditorSelection("my draft", 6, 2)

    private fun sample(
        allowed: Boolean = true, interactive: Boolean = true, focused: Boolean = true,
        shown: Boolean = true, opening: Boolean = false, hiding: Boolean = false, clear: Boolean = false,
        selection: ComposerEditorSelection = range, editor: ComposerImeReturn = controller,
    ) = editor.observe(enabled = allowed, interactive = interactive, editorFocused = focused, imeShown = shown,
        opening = opening, dismissing = hiding, clearAfterDismissal = clear, selection = selection)

    @Test fun backgroundZeroInsetsAndDeselectionDoNotEraseTheOneReturn() {
        sample()
        assertEquals(Action.None, sample(interactive = false, focused = false, shown = false,
            clear = true, selection = range.copy(end = range.start)))
        controller.onStop()
        repeat(2) { assertEquals(Action.None, sample(interactive = false, shown = false, clear = true)) }
        assertEquals(Action.RestoreEditor, sample(focused = false, shown = false, clear = true))
        assertEquals(range, controller.selectionFor("my draft"))
        assertNull(controller.selectionFor("changed draft"))
        repeat(3) { assertEquals(Action.None, sample(shown = false, clear = true)) }
        assertEquals(Action.None, sample())
        assertEquals(Action.ClearEditorFocus, sample(shown = false, clear = true))
    }

    @Test fun windowOverlaysAndPauseResumeAloneNeverRequestAKeyboard() {
        sample()
        sample(interactive = false, shown = false)
        assertEquals(Action.None, sample())
        sample(interactive = false, shown = false)
        assertEquals(Action.ClearEditorFocus, sample(shown = false, clear = true))
    }

    @Test fun gestureOrBackDismissalAndDraftWithoutAnImeDoNotQualify() {
        sample()
        sample(hiding = true)
        sample() // Bounds vanished for one frame, but the height still looks open.
        controller.onStop()
        assertEquals(Action.None, sample(shown = false))
        sample()
        assertEquals(Action.ClearEditorFocus, sample(shown = false, clear = true))
        controller.onStop()
        assertEquals(Action.None, sample(focused = false, shown = false))
    }

    @Test fun aReversedHideCanArmANewReturnWithoutReachingZero() {
        sample()
        sample(hiding = true)
        sample(opening = true)
        controller.onStop()
        assertEquals(Action.RestoreEditor, sample(shown = false))
    }

    @Test fun explicitExitBeforeInsetsChangeDoesNotRearmFromStaleVisibleKeyboard() {
        sample()
        controller.cancel()
        sample()
        controller.onStop()
        assertEquals(Action.None, sample(shown = false))
        assertNull(controller.selectionFor("my draft"))
        // An intentional later editing session can arm its own return.
        sample()
        controller.onStop()
        assertEquals(Action.RestoreEditor, sample(shown = false))
    }

    @Test fun disabledDestinationOrNewComposerCannotUseAPendingReturn() {
        sample()
        controller.onStop()
        sample(allowed = false, interactive = false, shown = false)
        assertEquals(Action.None, sample(shown = false))
        sample(allowed = false, shown = true)
        controller.onStop()
        assertEquals(Action.None, sample(shown = false))
        val newConversation = ComposerImeReturn()
        assertEquals(Action.None, sample(focused = false, shown = false, editor = newConversation))
        assertNull(newConversation.selectionFor(range.text))
    }

    @Test fun restoreIsConsumedEvenWhenAndroidHasAlreadyKeptTheKeyboard() {
        sample()
        controller.onStop()
        assertEquals(Action.RestoreEditor, sample())
        // The IME can survive a round trip without any further UI event to re-arm the next one.
        controller.onStop()
        assertEquals(Action.RestoreEditor, sample())
        assertEquals(Action.ClearEditorFocus, sample(shown = false, clear = true))
    }
}
