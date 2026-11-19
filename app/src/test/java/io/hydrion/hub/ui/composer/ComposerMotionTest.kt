package io.hydrion.hub.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerMotionTest {
    @Test fun dismissalBeginsImmediatelyAndReachesTheClosedShapeAtHalfHeight() {
        var frame: ComposerImeFrame? = null
        for ((height, fraction) in listOf(800 to 1f, 799 to .9975f, 600 to .5f, 400 to 0f, 230 to 0f, 0 to 0f)) {
            frame = nextComposerImeFrame(frame, height, 800, 0)
            assertEquals(fraction, frame.fraction!!, .0001f)
        }
    }

    @Test fun reversingAtANonZeroInsetRestartsAtExactlyTheVisibleHeightAndFraction() {
        val halfwayClosed = nextComposerImeFrame(null, 600, 800, 0)
        val reversing = nextComposerImeFrame(halfwayClosed, 600, 600, 800)
        assertEquals(.5f, reversing.fraction!!, .0001f)
        val opening = nextComposerImeFrame(reversing, 700, 600, 800)
        assertEquals(.75f, opening.fraction!!, .0001f)
        val closingAgain = nextComposerImeFrame(opening, 700, 700, 0)
        assertEquals(.75f, closingAgain.fraction!!, .0001f)
        assertEquals(.375f, nextComposerImeFrame(closingAgain, 525, 700, 0).fraction!!, .0001f)
        assertEquals(0f, nextComposerImeFrame(closingAgain, 350, 700, 0).fraction!!, .0001f)
    }

    @Test fun anOpenKeyboardResizeKeepsTheExpandedShapeAndNoImeLeavesFocusInControl() {
        assertEquals(.25f, nextComposerImeFrame(null, 200, 0, 800).fraction!!, 0f)
        val open = nextComposerImeFrame(null, 800, 0, 800)
        assertEquals(1f, nextComposerImeFrame(open, 700, 800, 600).fraction!!, 0f)
        assertNull(nextComposerImeFrame(open, 600, 600, 600).fraction)
    }
}
