package io.hydrion.hub.ui.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFadeBandsTest {
    private fun checkBands(height: Float, header: Float, top: Float, bottom: Float, density: Float) {
        val bands = chatFadeBands(height, header, height - top + 20f, top, bottom, density)
        val start = height - bands.bottomLength
        assertEquals(header + 56f * density, bands.topLength, .01f)
        assertEquals(header, bands.headerStop * bands.topLength, .01f)
        assertEquals(top - 28f * density, start, .01f)
        assertEquals(top, start + bands.capsuleTopStop * bands.bottomLength, .01f)
        assertEquals(bottom, start + bands.capsuleBottomStop * bands.bottomLength, .01f)
    }

    @Test fun normalViewportFadesTrackTheVisibleEdgesAcrossDensities() {
        checkBands(820f, 83f, 724f, 785f, 1f)
        checkBands(1640f, 166f, 1448f, 1570f, 2f)
    }

    @Test fun keyboardAndMultilineCapsulesUseTheirOwnMeasuredEdges() {
        checkBands(520f, 82f, 402f, 508f, 1f)
        checkBands(520f, 82f, 352f, 508f, 1f)
    }

    @Test fun missingAndStaleFramesFallBackToTheScaffoldBar() {
        val fallback = chatFadeBands(600f, 80f, 120f, null, null, 1f)
        assertEquals(148f, fallback.bottomLength, 0f)
        assertEquals(1f, fallback.capsuleBottomStop, 0f)
        for ((top, bottom) in listOf(-1f to 80f, 590f to 638f, 480f to 480f)) {
            assertEquals(fallback, chatFadeBands(600f, 80f, 120f, top, bottom, 1f))
        }
        assertEquals(0f, chatFadeBands(600f, 0f, 0f, null, null, 1f).bottomLength, 0f)
    }

    @Test fun crampedAndEmptyViewportsKeepAnUnshadedReadingBand() {
        for (height in listOf(0f, 60f, 175f, 240f)) {
            val bands = chatFadeBands(height, 86f, 130f, null, null, 1f)
            assertTrue(height - bands.bottomLength - bands.topLength >= height / 3f - .01f)
            assertTrue(bands.headerStop in 0f..1f && bands.capsuleTopStop in 0f..bands.capsuleBottomStop)
        }
    }
}
