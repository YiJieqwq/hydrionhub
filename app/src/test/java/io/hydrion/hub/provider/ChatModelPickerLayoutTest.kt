package io.hydrion.hub.provider

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

    @Test fun reasoningAndModelListClearTheSameMultilineComposerAtPhoneDensity() {
        val scale = Density(942f / 410f)
        val size = IntSize(942, 1220)
        for (top in listOf(974, 760, 530)) {
            val composer = IntRect(29, top, 913, 1192)
            val list = scale.pickerSpace(composer, size, LayoutDirection.Ltr)
            val panel = scale.pickerSpace(composer, size, LayoutDirection.Ltr, reasoning = true)
            assertTrue(panel.above && list.above)
            assertEquals(top - 26, panel.edgeY)
            assertEquals(list.edgeY, panel.edgeY)
            assertEquals(composer.right, panel.left + panel.width)
            assertEquals(list.left + list.width, panel.left + panel.width)
            assertEquals(662, panel.width)
        }
    }

    @Test fun reasoningCardFitsANarrowRtlViewport() {
        val result = Density(1f).pickerSpace(IntRect(8, 160, 178, 210), IntSize(186, 240),
            LayoutDirection.Rtl, reasoning = true)
        assertEquals(12, result.left)
        assertEquals(162, result.width)
        assertEquals(148, result.edgeY)
    }

    @Test fun shortWindowLimitsComposerHeightWhileThePickerIsOpen() {
        val scale = Density(1f)
        val limit = scale.pickerComposerMaxHeight(300, safeTop = 24, editorAndToolbar = 106)
        val viewport = IntSize(600, 276)
        val composer = IntRect(12, viewport.height - limit, 588, viewport.height - 12)
        val panel = scale.pickerSpace(composer, viewport, LayoutDirection.Ltr, reasoning = true)
        assertEquals(148, limit)
        assertEquals(104, panel.maxHeight)
        assertEquals(composer.top - 12, panel.edgeY)
        assertEquals(106, scale.pickerComposerMaxHeight(164, safeTop = 24, editorAndToolbar = 106))
    }

    @Test fun veryShortWindowUsesOnlyThePixelsOutsideTheComposer() {
        val scale = Density(1f)
        val viewport = IntSize(332, 170)
        for (reasoning in listOf(false, true)) {
            val composer = IntRect(12, 24, 320, 162)
            val panel = scale.pickerSpace(composer, viewport, LayoutDirection.Ltr, reasoning)
            assertTrue(panel.above)
            assertEquals(composer.top, panel.edgeY)
            assertEquals(24, panel.maxHeight)
            val high = IntRect(12, 12, 320, 70)
            val below = scale.pickerSpace(high, viewport, LayoutDirection.Ltr, reasoning)
            assertTrue(!below.above)
            assertEquals(high.bottom, below.edgeY)
            assertEquals(100, below.maxHeight)
        }
    }
}
