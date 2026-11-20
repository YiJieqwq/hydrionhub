package io.hydrion.hub.provider

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffortLabelAnchorTest {
    private val density = Density(1f)
    private val viewport = IntSize(620, 800)
    private val bar = IntRect(18, 610, 602, 774)
    private val label = IntRect(320, 728, 424, 750)

    private fun panel(text: IntRect?, barBounds: IntRect = bar, fraction: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr, area: IntSize = viewport) =
        density.pickerSpace(barBounds, area, direction, reasoning = true, modelLabel = text, modelRowFraction = fraction)

    @Test fun multilineAndAttachmentsCanExtendBehindTheEffortCardWithoutMovingItsModelAnchor() {
        val result = panel(label)
        assertTrue(result.above && result.edgeY > bar.top)
        assertEquals(label.top - 6, result.edgeY)
        assertEquals(label.center.x, result.left + result.width / 2)
        assertEquals(result, panel(label, bar.copy(top = 430)))
    }

    @Test fun keyboardAndModelNameChangesUseTheNewRectInTheSameSample() {
        val original = panel(label)
        for (rise in listOf(1, 14, 42, 91, 160, 88, 42, 0)) {
            val shift = IntOffset(0, -rise)
            val moved = panel(label.translate(shift), bar.translate(shift), area = IntSize(viewport.width, viewport.height - rise))
            assertEquals(original.left, moved.left)
            assertEquals(original.edgeY - rise, moved.edgeY)
        }
        val wider = panel(label.copy(left = label.left - 100))
        assertEquals(original.left - 50, wider.left)
    }

    @Test fun measuredTextMirrorsNaturallyAndOffscreenCentersClampToTheSafeMargin() {
        val ltr = panel(label)
        val mirror = IntRect(viewport.width - label.right, label.top, viewport.width - label.left, label.bottom)
        val rtl = panel(mirror, direction = LayoutDirection.Rtl,
            barBounds = IntRect(viewport.width - bar.right, bar.top, viewport.width - bar.left, bar.bottom))
        assertEquals(viewport.width - ltr.left - ltr.width, rtl.left)
        assertEquals(ltr.edgeY, rtl.edgeY)
        assertEquals(12, panel(label.copy(left = 19, right = 45), direction = LayoutDirection.Rtl).left)
        val right = panel(label.copy(left = 575, right = 593))
        assertEquals(viewport.width - 12, right.left + right.width)
    }

    @Test fun collapsingAndReversingMeetTheSameFoldedFallbackWhenTheLabelDisappears() {
        for (direction in LayoutDirection.entries) {
            val folded = panel(null, direction = direction)
            val expanded = panel(label, direction = direction)
            assertEquals(folded, panel(label, fraction = 0f, direction = direction))
            assertEquals(folded, panel(label.translate(IntOffset(-1000, 0)), direction = direction))
            for (sample in listOf(1f, .75f, .5f, .25f, 0f, .25f, .5f, 1f)) {
                val current = panel(label, fraction = sample, direction = direction)
                assertEquals(folded.left + (expanded.left - folded.left) * sample, current.left.toFloat(), 1f)
                assertEquals(folded.edgeY + (expanded.edgeY - folded.edgeY) * sample, current.edgeY.toFloat(), 1f)
            }
        }
    }

    @Test fun tightWindowsKeepTheCardAndItsHitAreaInsideTheSafeViewport() {
        for (width in listOf(80, 160, 280, 480))
            for (height in listOf(64, 110, 245))
                for (direction in LayoutDirection.entries) {
                    val area = IntSize(width, height)
                    val capsule = IntRect(3, height / 4, width - 3, height - 2)
                    val text = IntRect(width / 4, height / 2, width * 3 / 4, height * 3 / 4)
                    val result = panel(text, capsule, direction = direction, area = area)
                    val top = if (result.above) result.edgeY - result.maxHeight else result.edgeY
                    assertTrue(result.left >= 0 && result.left + result.width <= width)
                    assertTrue(top >= 0 && top + result.maxHeight <= height)
                    assertTrue(if (result.above) result.edgeY <= text.top else result.edgeY >= capsule.bottom)
                }
    }
}
