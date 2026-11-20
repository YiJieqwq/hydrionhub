package io.hydrion.hub.provider

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelPickerLayoutTest {
    @Test fun phoneListAlignsToTheComposerEndAndClearsItsTop() {
        val density = Density(942f / 410f)
        val composer = IntRect(29, 973, 913, 1192)
        val result = density.pickerSpace(composer, IntSize(942, 1220), LayoutDirection.Ltr)
        assertTrue(result.above)
        assertEquals(565, result.width)
        assertEquals(composer.right, result.left + result.width)
        assertEquals(947, result.edgeY)
    }

    @Test fun tallerDraftLiftsTheWholeCardAndLimitsLongListsAboveTheField() {
        val density = Density(1f)
        val viewport = IntSize(410, 340)
        val oneLine = IntRect(12, 235, 398, 328)
        val multiline = oneLine.copy(top = 150)
        val short = density.pickerSpace(oneLine, viewport, LayoutDirection.Ltr)
        val tall = density.pickerSpace(multiline, viewport, LayoutDirection.Ltr)
        assertTrue(short.above && tall.above)
        assertEquals(short.width, tall.width)
        assertEquals(short.left, tall.left)
        assertEquals(oneLine.top - multiline.top, short.edgeY - tall.edgeY)
        assertEquals(138, tall.edgeY)
        assertEquals(126, tall.maxHeight)
    }

    @Test fun narrowWindowClampsBothSidesAndBoundsTheScrollableList() {
        val composer = IntRect(6, 150, 166, 198)
        val result = Density(1f).pickerSpace(composer, IntSize(172, 204), LayoutDirection.Ltr)
        assertEquals(12, result.left)
        assertEquals(148, result.width)
        assertEquals(126, result.maxHeight)
        assertTrue(result.edgeY < composer.top)
    }

    @Test fun rtlAlignsToTheComposerLeftAndCanUseSpaceBelowNearTheTop() {
        val composer = IntRect(31, 20, 358, 68)
        val result = Density(1f).pickerSpace(composer, IntSize(410, 260), LayoutDirection.Rtl)
        assertEquals(31, result.left)
        assertEquals(false, result.above)
        assertEquals(80, result.edgeY)
        assertEquals(168, result.maxHeight)
    }

    @Test fun tabletListStaysCompact() {
        val composer = IntRect(200, 710, 1000, 805)
        val result = Density(1f).pickerSpace(composer, IntSize(1200, 840), LayoutDirection.Ltr)
        assertEquals(248, result.width)
        assertEquals(composer.right, result.left + result.width)
    }

    @Test fun attachmentFillingAShortWindowLeavesAModelRowReachableAboveTheKeyboard() {
        val composer = IntRect(12, 24, 320, 162)
        val viewport = IntSize(332, 170)
        val result = Density(1f).pickerSpace(composer, viewport, LayoutDirection.Ltr)
        assertTrue(result.above)
        assertTrue(result.maxHeight >= 88)
        assertTrue(result.edgeY - result.maxHeight >= 0)
        assertTrue(result.edgeY <= composer.bottom - 48)
    }

    @Test fun searchButtonAndOneVisibleModelFitTogetherAboveAnOpenKeyboard() {
        val viewport = IntSize(250, 146)
        val composer = IntRect(8, 35, 242, 137)
        val space = Density(1f).pickerSpace(composer, viewport, LayoutDirection.Ltr, minimumHeight = 104.dp)
        assertTrue(space.above)
        assertTrue(space.maxHeight >= 48 + 2 + 48)
        assertTrue(space.edgeY - space.maxHeight >= 0)
        assertTrue(space.edgeY <= viewport.height)
    }

    @Test fun reasoningCardCentersOnModelControlWithAnEightDpGap() {
        val scale = Density(2.5f)
        val trigger = IntRect(396, 763, 586, 873)
        val result = scale.reasoningPanelSpace(trigger, IntSize(1000, 1150))
        assertEquals(680, result.width)
        assertEquals(151, result.left)
        assertEquals(743, result.edgeY)
        assertEquals(713, result.maxHeight)
        assertTrue(result.above)
    }

    @Test fun reasoningCardClampsToEitherEdgeIndependentOfTextDirection() {
        val density = Density(1f)
        val size = IntSize(410, 730)
        assertEquals(12, density.reasoningPanelSpace(IntRect(8, 560, 32, 604), size).left)
        assertEquals(126, density.reasoningPanelSpace(IntRect(380, 560, 404, 604), size).left)
        val narrow = density.reasoningPanelSpace(IntRect(90, 560, 156, 604), IntSize(186, 730))
        assertEquals(162, narrow.width)
        assertEquals(12, narrow.left)
    }

    @Test fun constrainedReasoningCardScrollsAboveOrFlipsForOneTouchRow() {
        val density = Density(1f)
        val size = IntSize(320, 232)
        val short = density.reasoningPanelSpace(IntRect(170, 86, 286, 130), size)
        assertTrue(short.above)
        assertEquals(66, short.maxHeight)
        val flipped = density.reasoningPanelSpace(IntRect(170, 25, 286, 69), size)
        assertEquals(false, flipped.above)
        assertEquals(77, flipped.edgeY)
        assertEquals(143, flipped.maxHeight)
    }
}
