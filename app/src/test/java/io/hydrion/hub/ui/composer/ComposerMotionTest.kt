package io.hydrion.hub.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerMotionTest {
    @Test fun firstAndRepeatedOpeningsWaitForTravelEvenIfFocusArrivesManyFramesEarlier() {
        var frame: ComposerImeFrame? = null
        repeat(3) {
            repeat(10) {
                frame = nextComposerImeFrame(frame, 0, 0, 0)
                assertEquals(0f, frame!!.fraction, 0f)
            }
            for (height in listOf(0, 1, 200, 400, 800)) {
                frame = nextComposerImeFrame(frame, height, 0, 800)
                assertEquals(height / 800f, frame!!.fraction, .0001f)
            }
            frame = nextComposerImeFrame(frame, 800, 800, 800)
            assertEquals(1f, frame!!.fraction, 0f)
            frame = nextComposerImeFrame(frame, 0, 0, 0)
            assertEquals(0f, frame!!.fraction, 0f)
        }
    }

    @Test fun lateOrTemporarilyMissingBoundsDoNotRewindTheOpening() {
        val closed = nextComposerImeFrame(null, 0, 0, 0)
        val opening = nextComposerImeFrame(closed, 200, 0, 800)
        val missing = nextComposerImeFrame(opening, 400, 0, 0)
        assertEquals(.5f, missing.fraction, 0f)
        assertEquals(.75f, nextComposerImeFrame(missing, 600, 0, 800).fraction, 0f)

        // A legacy IME can deliver the height jump before the opening bounds.
        val jump = nextComposerImeFrame(closed, 400, 400, 400)
        val late = nextComposerImeFrame(jump, 400, 0, 800)
        assertEquals(1f, jump.fraction, 0f)
        assertEquals(1f, late.fraction, 0f)
    }

    @Test fun physicalKeyboardEditingNeedsNoInsetAndStillAcceptsASoftwareKeyboard() {
        val hardware = nextComposerImeFrame(null, 0, 0, 0, expandWithoutIme = true)
        assertEquals(1f, hardware.fraction, 0f)
        val opening = nextComposerImeFrame(hardware, 0, 0, 800, expandWithoutIme = true)
        assertEquals(1f, opening.fraction, 0f)
        val opened = nextComposerImeFrame(opening, 800, 800, 800, expandWithoutIme = true)
        assertEquals(1f, opened.fraction, 0f)
        val dismissed = nextComposerImeFrame(opened, 0, 0, 0, expandWithoutIme = false)
        assertEquals(0f, dismissed.fraction, 0f)
    }

    @Test fun dismissalBeginsImmediatelyAndReachesTheClosedShapeAtHalfHeight() {
        var frame: ComposerImeFrame? = null
        for ((height, fraction) in listOf(800 to 1f, 799 to .9975f, 600 to .5f, 400 to 0f, 230 to 0f, 0 to 0f)) {
            frame = nextComposerImeFrame(frame, height, 800, 0)
            assertEquals(fraction, frame.fraction, .0001f)
        }
    }

    @Test fun reversingAtANonZeroInsetRestartsAtExactlyTheVisibleHeightAndFraction() {
        val halfwayClosed = nextComposerImeFrame(null, 600, 800, 0)
        val reversing = nextComposerImeFrame(halfwayClosed, 600, 600, 800)
        assertEquals(.5f, reversing.fraction, .0001f)
        val opening = nextComposerImeFrame(reversing, 700, 600, 800)
        assertEquals(.75f, opening.fraction, .0001f)
        val closingAgain = nextComposerImeFrame(opening, 700, 700, 0)
        assertEquals(.75f, closingAgain.fraction, .0001f)
        assertEquals(.375f, nextComposerImeFrame(closingAgain, 525, 700, 0).fraction, .0001f)
        assertEquals(0f, nextComposerImeFrame(closingAgain, 350, 700, 0).fraction, .0001f)
    }

    @Test fun anOpenKeyboardResizeKeepsTheExpandedShape() {
        val open = nextComposerImeFrame(null, 800, 0, 800)
        val resizing = nextComposerImeFrame(open, 700, 800, 600)
        assertEquals(1f, resizing.fraction, 0f)
        assertEquals(1f, nextComposerImeFrame(resizing, 600, 600, 600).fraction, 0f)
    }
}
