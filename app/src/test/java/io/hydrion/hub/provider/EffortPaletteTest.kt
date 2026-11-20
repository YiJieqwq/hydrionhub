package io.hydrion.hub.provider

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffortPaletteTest {
    private val levels = listOf("Off", "Minimal", "Low", "Medium", "High", "Extra High", "Max")

    @Test fun ordinaryFastRailUsesTheExistingBlueInEachTheme() {
        for (dark in listOf(false, true)) {
            val blue = effortPalette(levels, "Low", dark)
            val fast = ordinaryFastRailColors(dark)
            assertEquals(blue.fill, fast.fill)
            assertEquals(blue.track, fast.track)
            assertFalse(fast.isPeak)
        }
    }

    @Test fun eachStrengthHasDistinctFillAndReadableInkInEachTheme() {
        fun contrast(a: Color, b: Color): Float {
            val aLuminance = a.luminance()
            val bLuminance = b.luminance()
            return (maxOf(aLuminance, bLuminance) + 0.05f) / (minOf(aLuminance, bLuminance) + 0.05f)
        }
        for (dark in listOf(false, true)) {
            val palettes = levels.map { effortPalette(levels, it, dark) }
            val card = if (dark) Color(0xFF353638) else Color(0xFFF8F8F8)
            assertEquals(levels.size, palettes.map { it.fill }.distinct().size)
            for (palette in palettes) {
                assertTrue(contrast(palette.ink, card) >= 4.5f)
                assertTrue(contrast(palette.thumb, palette.fill) >= 2.2f)
                val groove = if (dark) palette.track else Color(0xFFE8E7E8)
                assertTrue(contrast(palette.fill, groove) >= 2.5f)
            }
        }
    }

    @Test fun trackAndMotesUseTheSameSelectedFillInBothThemes() {
        val light = effortPalette(levels, "Medium", false)
        assertEquals(Color(0xFF4A72F2), light.fill)
        assertEquals(Color(0xFF3A55B8), light.ink)
        assertEquals(Color(0xFFC5CDEA), light.track)
        assertEquals(Color(0xFF96ADF7), light.mote)
        assertEquals(Color.White, light.thumb)
        assertEquals(Color.White.copy(alpha = 0.55f), light.passedTick)
        val dark = effortPalette(levels, "Medium", true)
        assertEquals(Color(0xFF6E90FF), dark.fill)
        assertEquals(Color(0xFF8BA7FF), dark.ink)
        assertEquals(Color(0xFF343B54), dark.track)
        assertEquals(Color(0xFF9AB1FF), dark.mote)
        assertEquals(Color(0xFFF9FAFB), dark.thumb)
        assertEquals(dark.thumb, dark.passedTick)
        assertEquals(Color(0xFFB6B6B8), dark.tick)
    }

    @Test fun aliasesMatchTheNativeEffortWithoutRenamingTheChoice() {
        for ((label, canonical) in listOf(" None " to "Off", "无" to "Off", "关闭" to "Off",
            "Mid" to "Medium", "xHigh" to "Extra High", "extra-high" to "Extra High", "Ultra" to "Max")) {
            for (dark in listOf(false, true)) {
                assertEquals(effortPalette(listOf(canonical), canonical, dark),
                    effortPalette(listOf(label), label, dark))
            }
        }
    }

    @Test fun visualPeakFollowsTheAvailableLadderAndPreservesTheRealColour() {
        val short = listOf("Low", "High")
        val high = effortPalette(short, "High", false)
        assertTrue(high.isPeak)
        assertEquals(effortPalette(levels, "High", false).ink, high.ink)
        assertFalse(effortPalette(levels, "High", false).isPeak)
        val custom = listOf("Quiet", "Deliberate")
        val last = effortPalette(custom, "Deliberate", true)
        assertEquals(effortPalette(levels, "Low", true).fill, last.fill)
        assertFalse(effortPalette(custom, "Quiet", true).isPeak)
        assertTrue(last.isPeak)
        assertFalse(effortPalette(short, "Max", false).isPeak)
        assertFalse(effortPalette(emptyList(), null, false).isPeak)
    }

    @Test fun singleStopNeedsExplicitPeakAndControlChoicesNeverActivateIt() {
        for (value in listOf("High", "Custom", "Off", "Auto", "Default")) {
            assertFalse(effortPalette(listOf(value), value, false).isPeak)
        }
        for (value in listOf("Max", "Ultra")) {
            assertTrue(effortPalette(listOf(value), value, false).isPeak)
        }
        assertFalse(effortPalette(listOf("Off", "Auto"), "Auto", false).isPeak)
        assertEquals(-1, peakEffortIndex(listOf("Off", "Auto", "High")))
        assertEquals(1, peakEffortIndex(listOf("Auto", "Ultra", "Off")))
        assertEquals(2, peakEffortIndex(listOf("Auto", "Low", "High", "Default")))
    }
}
