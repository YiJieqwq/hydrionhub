package io.hydrion.hub.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerMotionTest {
    private val motion = ComposerMotion()
    private val open = ComposerMorph.Tween.Open
    private val zero = ComposerMorph.At(0f)

    private fun frame(
        height: Int, source: Int, target: Int, rendered: Float = 0f,
        editing: Boolean = true, hardware: Boolean = false,
    ) = motion.update(height, source, target, editing, hardware, rendered)

    @Test fun firstAndRepeatedOpeningsStartOnActualTravelAndUseOneShortTween() {
        repeat(3) {
            assertEquals(zero, frame(0, 0, 0, editing = false))
            repeat(10) { assertEquals(zero, frame(0, 0, 800)) }
            assertEquals(open, frame(1, 0, 800))
            assertEquals(48, open.millis)
            assertEquals(1f, open.end, 0f)
            // Same goal even when Android stalls low for the whole tween or jumps straight to its end.
            repeat(5) { assertEquals(open, frame(12, 0, 800, .8f)) }
            assertEquals(open, frame(800, 800, 800, 1f))
            assertEquals(zero, frame(0, 0, 0, 1f))
        }
    }

    @Test fun lateBoundsMissingBoundsAndTransientZeroWithOpeningTargetDoNotRestartTheTween() {
        assertEquals(open, frame(200, 200, 200))
        assertEquals(open, frame(250, 0, 800, .3f))
        assertEquals(open, frame(300, 0, 0, .5f))
        repeat(2) { assertEquals(open, frame(0, 0, 800, .7f)) }
        assertEquals(open, frame(400, 0, 800, 1f))
        assertEquals(open, frame(800, 800, 800, 1f))
        assertEquals(open, frame(700, 800, 600, 1f))
    }

    @Test fun dismissalStillCompletesAtHalfHeightEvenWhenBoundsDisappearMidway() {
        frame(800, 800, 800, 1f)
        assertEquals(ComposerMorph.At(1f), frame(800, 800, 0, 1f))
        assertEquals(ComposerMorph.At(.5f), frame(600, 800, 0, 1f))
        assertEquals(ComposerMorph.At(.25f), frame(500, 500, 500, .5f))
        assertEquals(zero, frame(400, 0, 0, .25f))
        assertEquals(zero, frame(300, 800, 0))
        assertEquals(zero, frame(0, 0, 0))
    }

    @Test fun rapidReversalsUseTheVisibleShapeEvenFarFromTheImeTravelFraction() {
        assertEquals(open, frame(10, 0, 800))
        assertEquals(open, frame(160, 0, 800, .75f))
        assertEquals(ComposerMorph.At(.75f), frame(160, 800, 0, .75f))
        assertEquals(ComposerMorph.At(.375f), frame(120, 800, 0, .75f))
        assertEquals(open, frame(120, 120, 800, .375f))
        assertEquals(ComposerMorph.At(.625f), frame(120, 120, 0, .625f))
        assertEquals(ComposerMorph.At(.3125f), frame(90, 0, 0, .625f))
        assertEquals(open, frame(100, 0, 0, .3125f))
    }

    @Test fun keyboardRemovalCanJumpDirectlyToZero() {
        frame(0, 0, 0)
        assertEquals(open, frame(800, 800, 800))
        assertEquals(zero, frame(0, 0, 0, .75f))
        assertEquals(zero, frame(0, 0, 800))
    }

    @Test fun hardwareKeyboardEditingDoesNotNeedInsetsAndExplicitDefocusStillCollapses() {
        assertEquals(open, frame(0, 0, 0, hardware = true))
        assertEquals(open, frame(0, 0, 800, hardware = true))
        assertEquals(open, frame(300, 0, 800, 1f, hardware = true))
        assertEquals(ComposerMorph.Tween.LoseFocus, frame(300, 0, 800, 1f, editing = false, hardware = true))
        assertEquals(33, ComposerMorph.Tween.LoseFocus.millis)
        assertEquals(zero, frame(0, 0, 0, editing = false))
    }
}
