package io.hydrion.hub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlidingPageBackdropTest {
    // Sample the two clamped gradient stops at a fixed screen pixel, independent of the page text.
    private fun coverage(backdrop: SlidingPageBackdrop, progress: Float, y: Float): Float =
        ((backdrop.solidBottomPx(progress) + backdrop.featherPx - y) / backdrop.featherPx)
            .coerceIn(0f, 1f)

    @Test fun theFeatherReachesDifferentScreenPixelsInOrderAndRetracesOnExit() {
        for (height in listOf(19f, 160f, 620f, 1664f)) {
            val backdrop = SlidingPageBackdrop(height, 108f)
            assertEquals(minOf(108f, height / 4f), backdrop.featherPx, .001f)
            val samples = listOf(height / 4f, 3f * height / 4f)
            fun frame(step: Int) = samples.map { coverage(backdrop, step / 240f, it) }
            val opening = (0..240).map(::frame)
            for (sample in samples.indices) {
                val values = opening.map { it[sample] }
                assertTrue(values.zipWithNext().all { (previous, next) -> next >= previous })
                assertTrue("Each pixel has partial coverage", values.any { it > 0f && it < 1f })
            }
            assertTrue("Top sample finishes before bottom sample starts",
                opening.indexOfFirst { it.first() >= 1f } < opening.indexOfFirst { it.last() > 0f })
            assertEquals(listOf(1f, 0f), opening[120])
            assertEquals(opening.take(181).asReversed(), (180 downTo 0).map(::frame))
        }
    }

    @Test fun theRestingSurfaceIsUniformAndTranslucentEvenAtTheLastPixel() {
        assertTrue(SlidingPageBackdrop.BodyOpacity > 0f && SlidingPageBackdrop.BodyOpacity < 1f)
        for (height in listOf(1, 37, 360, 1400)) {
            val backdrop = SlidingPageBackdrop(height.toFloat(), 108f)
            for (pixel in 0 until height) {
                assertEquals(0f, coverage(backdrop, -.1f, pixel + .5f), 0f)
                assertEquals(0f, coverage(backdrop, 0f, pixel + .5f), 0f)
                assertEquals(1f, coverage(backdrop, 1f, pixel + .5f), .0001f)
                assertEquals(1f, coverage(backdrop, 1.1f, pixel + .5f), .0001f)
            }
        }
        val empty = SlidingPageBackdrop(0f, 108f)
        assertEquals(0f, empty.featherPx, 0f)
        assertEquals(0f, empty.solidBottomPx(.5f), 0f)
    }
}
