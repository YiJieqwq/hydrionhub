package io.hydrion.hub.provider

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerBubbleGeometryTest {
    private val source = Rect(220f, 720f, 310f, 740f)
    private val card = Rect(104f, 555f, 392f, 685f)
    private val list = Rect(152f, 340f, 392f, 685f)
    private fun at(weights: Offset) = pickerBubbleBounds(source, card, list, weights)

    @Test fun openingMovesTheLowerRightCornerUpAndRightAndGrowsUpAndLeft() {
        assertEquals(source, at(PickerBubbleFace.Label.weights))
        assertEquals(card, at(PickerBubbleFace.Effort.weights))
        val frame = at(Offset(.5f, 0f))
        assertEquals(Rect(162f, 637.5f, 351f, 712.5f), frame)
        assertTrue(frame.width > source.width && frame.height > source.height)
    }

    @Test fun switchingPanelsKeepsTheCornerWhileMovingLeftAndTopIndependently() {
        for (i in 0..16) {
            val listFraction = i / 16f
            val frame = at(Offset(1f - listFraction, listFraction))
            assertEquals(card.right, frame.right, 0f)
            assertEquals(card.bottom, frame.bottom, 0f)
            assertEquals(card.left + 48f * listFraction, frame.left, 0f)
            assertEquals(card.top - 215f * listFraction, frame.top, 0f)
        }
        assertEquals(list, at(PickerBubbleFace.List.weights))
    }

    @Test fun directModelExitHasNoEffortWaypoint() {
        val middle = at(Offset(0f, .5f))
        assertEquals(Rect(186f, 530f, 351f, 712.5f), middle)
        assertTrue(middle.left > list.left && middle.right < list.right)
        assertTrue(middle.top > list.top && middle.bottom > list.bottom)
    }

    @Test fun interruptingAMorphStartsTheReturnAtTheDrawnFrame() {
        val before = Offset(.75f, .25f)
        val start = at(before)
        val halfClosed = at(before * .5f)
        assertEquals((source.left + start.left) * .5f, halfClosed.left, 0f)
        assertEquals((source.top + start.top) * .5f, halfClosed.top, 0f)
        assertEquals((source.right + start.right) * .5f, halfClosed.right, 0f)
        assertEquals((source.bottom + start.bottom) * .5f, halfClosed.bottom, 0f)
        val reopening = at((before * .5f + PickerBubbleFace.Effort.weights) * .5f)
        assertEquals((halfClosed.left + card.left) * .5f, reopening.left, 0f)
        assertEquals((halfClosed.top + card.top) * .5f, reopening.top, 0f)
        assertEquals((halfClosed.right + card.right) * .5f, reopening.right, 0f)
        assertEquals((halfClosed.bottom + card.bottom) * .5f, reopening.bottom, 0f)
    }

    @Test fun sourceTracksTheVisibleTextAndNeverSubstitutesAnEntireTapTarget() {
        val viewport = IntSize(400, 760)
        val composer = IntRect(8, 695, 392, 786)
        val button = IntRect(140, 708, 327, 756)
        assertEquals(source, pickerBubbleLabel(IntRect(220, 720, 310, 740), button, composer, viewport))
        assertEquals(Rect(220f, 750f, 310f, 760f),
            pickerBubbleLabel(IntRect(220, 750, 310, 770), button, composer, viewport))

        val clippedOut = IntRect(10, 10, 100, 26)
        val fallback = pickerBubbleLabel(clippedOut, button, composer, viewport)
        assertEquals(Rect(233.5f, 732f, 233.5f, 732f), fallback)
        assertEquals(fallback, pickerBubbleLabel(null, button, composer, viewport))
        assertEquals(Rect(296f, 760f, 296f, 760f), pickerBubbleLabel(null, null, composer, viewport))
        assertEquals(Rect.Zero, pickerBubbleLabel(null, button, composer, IntSize.Zero))
    }
}
