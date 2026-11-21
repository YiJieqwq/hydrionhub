package io.hydrion.hub.provider

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffortLabelAnchorTest {
    private val density = Density(1f)
    private val viewport = IntSize(620, 800)
    private val bar = IntRect(18, 610, 602, 774)
    private val label = IntRect(320, 728, 424, 750)

    private fun panel(composer: IntRect, area: IntSize = viewport, direction: LayoutDirection = LayoutDirection.Ltr): Rect =
        density.pickerSpace(composer, area, direction, reasoning = true).panelRect(110)

    @Test fun modelRowUsesTheMeasuredTitleStartUntilItsChevronReachesTheEnd() {
        // The 0.1.19 emulator measured a 96px Hydrion, a 6px gap and a 28px chevron.
        val hydrionRow = 96 + 6 + 28
        val availableEnd = 599
        for (titleStart in listOf(349, 374)) { // Centered Medium and High on the same card.
            assertEquals(titleStart, effortModelRowStart(titleStart, hydrionRow, availableEnd))
            assertEquals(titleStart, effortModelRowStart(titleStart, 40, availableEnd))
        }
        assertEquals(availableEnd - 260, effortModelRowStart(374, 260, availableEnd))
    }

    @Test fun differentLabelWidthsAndTheMissingLabelChangeOnlyTheSourceOfTheBubble() {
        val card = panel(bar)
        assertEquals((bar.top - 12).toFloat(), card.bottom, 0f)
        assertEquals(bar.right.toFloat(), card.right, 0f)
        for (ink in listOf(label, label.copy(left = label.left - 100), null)) {
            val source = pickerBubbleLabel(ink, null, bar, viewport)
            if (ink != null) assertEquals(ink.toRect(), source)
            val positions = (0..8).map { step ->
                pickerBubbleBounds(source, card, Rect.Zero, Offset(step / 8f, 0f))
            }
            assertEquals(source, positions.first())
            assertEquals(card, positions.last())
            assertTrue(positions.zipWithNext().all { (a, b) -> b.bottom < a.bottom && b.width >= a.width })
            val reverse = (8 downTo 0).map { step ->
                pickerBubbleBounds(source, card, Rect.Zero, Offset(step / 8f, 0f))
            }
            assertEquals(positions.reversed(), reverse)
        }
    }

    @Test fun attachmentsLiftTheLandingWhileTheLabelCanStayInTheSameToolbarRow() {
        val tallBar = bar.copy(top = bar.top - 170)
        assertEquals(pickerBubbleLabel(label, null, bar, viewport), pickerBubbleLabel(label, null, tallBar, viewport))
        assertEquals(panel(bar).translate(0f, -170f), panel(tallBar))
        assertTrue(panel(tallBar).bottom < tallBar.top)
    }

    @Test fun aMovingKeyboardUpdatesTheWholeFlightAndItsLandingInTheSameFrame() {
        val original = panel(bar)
        for (rise in listOf(0, 14, 42, 91, 160, 88, 42, 0)) {
            val shift = IntOffset(0, -rise)
            val area = IntSize(viewport.width, viewport.height - rise)
            val movedBar = bar.translate(shift)
            val moved = panel(movedBar, area)
            val source = pickerBubbleLabel(label.translate(shift), null, movedBar, area)
            assertEquals(original.translate(0f, -rise.toFloat()), moved)
            for (coverage in listOf(0f, .25f, .5f, .75f, 1f)) {
                val weights = Offset(coverage, 0f)
                assertEquals(
                    pickerBubbleBounds(label.toRect(), original, Rect.Zero, weights).translate(0f, -rise.toFloat()),
                    pickerBubbleBounds(source, moved, Rect.Zero, weights),
                )
            }
        }
    }

    @Test fun slidingControlsStayOutsideTheEditorEvenWhenTheGapIsZero() {
        val touchingAbove = panel(bar).translate(0f, 12f)
        val below = Rect(314f, 782f, 602f, 892f)
        for (shift in listOf(-14f, -9f, 0f, 9f, 14f)) {
            val aboveShift = pickerContentShiftY(shift, touchingAbove, bar, above = true)
            val belowShift = pickerContentShiftY(shift, below, bar, above = false)
            assertEquals(minOf(0f, shift), aboveShift, 0f)
            assertEquals(maxOf(-8f, shift), belowShift, 0f)
        }
    }

    @Test fun tightWindowsKeepTheRestingPanelOutsideTheActualEditorInEitherDirection() {
        for (width in listOf(0, 9, 42, 170, 640)) for (height in listOf(0, 64, 110, 245))
            for (direction in LayoutDirection.entries) {
                val area = IntSize(width, height)
                val capsule = IntRect(0, height / 4, width, height)
                val card = panel(capsule, area, direction)
                assertTrue(card.left >= 0f && card.right <= width && card.top >= 0f && card.bottom <= height)
                assertTrue(card.bottom <= capsule.top || card.top >= capsule.bottom)
            }
    }
}
