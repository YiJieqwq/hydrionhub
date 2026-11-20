package io.hydrion.hub.provider

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.LayoutDirection
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

    @Test fun modelPageUsesTheWholeToolbarsPhysicalTravelAtEveryQuarterFrameInBothDirections() {
        val entrance = BrowseEffortLeg(0f, 1f, 1f, 0f, 1f, 0f)
        for (dir in listOf(-1, 1)) {
            for (quarter in 0..4) {
                val p = quarter / 4f
                val dx = dir * 20 * quarter
                val y = (quarter * 9).toFloat()  // IME moves independently of the inward travel.
                val inputLabel = source.translate(0f, -y)
                val steadyCard = card.translate(0f, -y)
                val frame = translatedPickerBounds(inputLabel, steadyCard, list, Offset(entrance.coverage(p), 0f), dx)
                assertEquals(card.left + (source.left - card.left) * p + dx, frame.left, 0f)
                assertEquals(card.right + (source.right - card.right) * p + dx, frame.right, 0f)
                assertEquals(card.top + (source.top - card.top) * p - y, frame.top, 0f)
                assertEquals(card.bottom + (source.bottom - card.bottom) * p - y, frame.bottom, 0f)
                assertEquals(1f - p, entrance.alpha(p), 0f)
                if (p == .5f) {
                    val mistaken = pickerBubbleBounds(inputLabel.translate(dx.toFloat(), 0f), steadyCard, list, Offset(.5f, 0f))
                    assertEquals(dx * .5f, frame.left - mistaken.left, 0f)
                }
            }
        }
    }

    @Test fun reversingAnInterruptedHandoffKeepsItsBoundsAndOpacityWithTheSamePageClock() {
        val forward = BrowseEffortLeg(0f, 1f, .75f, 0f, 1f, 0f)
        val p = .5f
        val back = BrowseEffortLeg(p, 0f, forward.coverage(p), 1f, forward.alpha(p), 1f)
        val dx = -52
        fun at(leg: BrowseEffortLeg, progress: Float) =
            translatedPickerBounds(source, card, list, Offset(leg.coverage(progress), 0f), dx)
        assertEquals(at(forward, p), at(back, p))
        assertEquals(forward.alpha(p), back.alpha(p), 0f)
        assertTrue(back.coverage(.25f) > back.coverage(.5f))
        assertEquals(1f, back.alpha(0f), 0f)
        assertEquals(card.translate(dx.toFloat(), 0f), at(back, 0f))
        val interrupted = BrowseEffortLeg(.25f, 1f, back.coverage(.25f), 0f, back.alpha(.25f), 0f)
        assertEquals(at(back, .25f), at(interrupted, .25f))
        assertEquals(0f, interrupted.alpha(1f), 0f)
    }

    @Test fun independentCapabilityMenuAndEffortAnchorsInterpolateWithoutAFixedCorner() {
        val fullList = Rect(12f, 94f, 400f, 618f)
        for (i in 0..16) {
            val fraction = i / 16f
            val frame = pickerBubbleBounds(source, card, fullList, Offset(1f - fraction, fraction))
            assertEquals(card.left + (fullList.left - card.left) * fraction, frame.left, 0f)
            assertEquals(card.right + (fullList.right - card.right) * fraction, frame.right, 0f)
            assertEquals(card.top + (fullList.top - card.top) * fraction, frame.top, 0f)
            assertEquals(card.bottom + (fullList.bottom - card.bottom) * fraction, frame.bottom, 0f)
        }
    }

    @Test fun directCapabilityMenuExitHasNoEffortWaypoint() {
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
        assertEquals(Rect(104f, 760f, 104f, 760f),
            pickerBubbleLabel(null, null, composer, viewport, LayoutDirection.Rtl))
        assertEquals(Rect.Zero, pickerBubbleLabel(null, button, composer, IntSize.Zero))
    }
}
