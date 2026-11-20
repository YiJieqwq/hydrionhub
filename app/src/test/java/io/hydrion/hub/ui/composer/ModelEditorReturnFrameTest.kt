package io.hydrion.hub.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEditorReturnFrameTest {
    private val frame = ModelEditorReturnFrame(300, 90, 0, 0, 0, false, 0f, 0f, false, false)

    @Test fun restoreFollowsCurrentImeTravelUntilMeasuredAtTheNewTarget() {
        val opening = frame.copy(imeVisible = true, imeTravel = 120, imeTarget = 240, expansion = 1f)
        assertEquals(.5f, opening.progress(true, 300), 0f)
        assertFalse(opening.atDestination(true, 300))
        val arrived = opening.copy(imeTravel = 240)
        assertTrue(arrived.atDestination(true, 300))
        assertFalse(arrived.copy(imeVisible = false).atDestination(true, 300))
        assertFalse(arrived.copy(expansion = .99f).atDestination(true, 300))
        assertFalse(arrived.copy(forcedFolded = true).atDestination(true, 300))
        val resizing = arrived.copy(imeSource = 300, imeTravel = 270)
        assertEquals(.5f, resizing.progress(true, 300), 0f)
        assertFalse(resizing.atDestination(true, 300))
    }

    @Test fun unboundedFramesUseCapturedImeTravelAndDontAcceptAClosingKeyboard() {
        val partial = frame.copy(imeVisible = true, imeSource = 120, imeTarget = 120, imeTravel = 120, expansion = 1f)
        assertEquals(.5f, partial.progress(true, 240), 0f)
        assertTrue(partial.atDestination(true, 240))
        assertTrue(partial.copy(imeSource = 0, imeTarget = 0, imeTravel = 240).atDestination(true, 240))
        assertFalse(partial.copy(imeSource = 240, imeTarget = 0, imeTravel = 240).atDestination(true, 240))
    }

    @Test fun shorterLegacyKeyboardCanSettleWhileAnExplicitTallerTargetMustFinish() {
        val shorter = frame.copy(imeVisible = true, imeTravel = 180, expansion = 1f)
        assertEquals(.6f, shorter.progress(true, 300), 0f)
        assertTrue(shorter.atDestination(true, 300))
        assertTrue(shorter.copy(imeSource = 180, imeTarget = 180).atDestination(true, 300))
        assertFalse(shorter.copy(imeTarget = 250).atDestination(true, 300))
        assertFalse(shorter.copy(imeSource = 250, imeTarget = 250).atDestination(true, 300))
        assertFalse(shorter.copy(expansion = .9f).atDestination(true, 300))
        assertFalse(shorter.copy(hasRowContent = true, rowGate = .9f).atDestination(true, 300))
        assertFalse(shorter.copy(height = 0).atDestination(true, 300))
        assertFalse(shorter.copy(imeVisible = false).atDestination(true, 300))
    }

    @Test fun hiddenKeyboardWaitsForItsInsetsVisibilityAndParkedRow() {
        val parked = frame.copy(hasRowContent = true, rowGate = 1f)
        val closing = parked.copy(imeSource = 240, imeTravel = 120, imeVisible = true)
        assertEquals(.5f, closing.progress(false, 0), 0f)
        assertFalse(closing.atDestination(false, 0))
        assertFalse(parked.copy(imeVisible = true).atDestination(false, 0))
        assertFalse(parked.copy(imeTarget = 240).atDestination(false, 0))
        assertFalse(parked.copy(expansion = .5f).atDestination(false, 0))
        assertFalse(parked.copy(rowGate = .9f).atDestination(false, 0))
        assertTrue(parked.atDestination(false, 0))
        assertTrue(frame.atDestination(false, 0))
        assertFalse(frame.copy(rowGate = .1f).atDestination(false, 0))
        assertFalse(frame.copy(width = 0).atDestination(false, 0))
        assertFalse(frame.copy(height = 0).atDestination(false, 0))
    }

    @Test fun floatingKeyboardRequiresActualVisibilityWithoutRequiringDockedShape() {
        val floating = frame.copy(imeVisible = true, hasRowContent = true, rowGate = 1f)
        assertTrue(floating.atDestination(true, 0))
        assertFalse(floating.copy(imeVisible = false).atDestination(true, 0))
        assertFalse(floating.atDestination(true, 240))
        assertFalse(floating.copy(imeSource = 240).atDestination(true, 0))
        assertFalse(floating.copy(imeTarget = 240).atDestination(true, 0))
        assertFalse(floating.copy(rowGate = .95f).atDestination(true, 0))
    }
}
