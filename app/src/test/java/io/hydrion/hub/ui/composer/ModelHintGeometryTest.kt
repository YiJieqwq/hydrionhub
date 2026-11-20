package io.hydrion.hub.ui.composer

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class ModelHintGeometryTest {
    @Test fun foldedAndExpandedHintsBothAdvanceWhileTheMagnifierStaysAtTheSameStart() {
        assertEquals(15.dp, OpenTextStart)
        assertEquals(18.dp, SearchIconSize)
        for ((draftStart, expectedStart) in listOf(48.dp to 55.dp, 15.dp to 40.dp)) {
            val gap = modelSearchIconGap(draftStart)
            assertEquals(expectedStart, OpenTextStart + SearchIconSize + gap)
            for (density in listOf(1f, 2.625f, 3f)) {
                val iconStart = (OpenTextStart.value * density).roundToInt()
                val iconEnd = iconStart + (SearchIconSize.value * density).roundToInt()
                val search = iconEnd + (gap.value * density).roundToInt()
                val draft = (draftStart.value * density).roundToInt()
                assertTrue(search > draft && search > iconEnd)
                for (rtl in listOf(false, true)) {
                    val direction = if (rtl) -1 else 1
                    // The active editor changes instantly, but both lines describe the same drawn path.
                    for (progress in listOf(0f, .5f, .25f, 1f, .75f, 0f)) {
                        val expected = direction * (draft + (search - draft) * progress)
                        for (native in listOf(draft, search)) {
                            val offset = modelHintOffsetX(draft, search, native, progress, rtl)
                            assertEquals(expected, direction * native + offset, .0001f)
                        }
                    }
                }
            }
        }
    }

    @Test fun progressLimitsAndCoincidentEmptyOriginsAreStable() {
        assertEquals(0f, modelHintOffsetX(15, 40, 15, -1f, rtl = false), 0f)
        assertEquals(0f, modelHintOffsetX(15, 40, 40, 2f, rtl = true), 0f)
        assertEquals(0f, modelHintOffsetX(0, 0, 0, .35f, rtl = false), 0f)
    }

    @Test fun standardLineMatchesTheFoldedAndExpandedPaddingIncludingHalfPixels() {
        // Folded (49.25dp, 5dp, 4dp) and expanded (46.5dp, 16dp, 4dp) at mdpi.
        assertEquals(13, modelHintTop(49, 5, 4, 24))
        assertEquals(18, modelHintTop(47, 16, 4, 24))
    }

    @Test fun shortTallAndEmptyLinesFollowComposeCenteringWithAnIconOrWithout() {
        for (density in listOf(1f, 2.625f, 3f)) {
            fun px(dp: Float) = (dp * density).roundToInt()
            for (line in listOf(0, px(9f), px(24f), px(44f), px(88f))) {
                for (icon in listOf(0, px(18f))) {
                    val min = px(46.5f)
                    val top = px(16f)
                    val bottom = px(4f)
                    val rowHeight = maxOf(min, top + maxOf(icon, line) + bottom)
                    val expected = top + Alignment.CenterVertically.align(line, rowHeight - top - bottom)
                    assertEquals(expected, modelHintTop(min, top, bottom, line, icon))
                }
            }
        }
    }
}
