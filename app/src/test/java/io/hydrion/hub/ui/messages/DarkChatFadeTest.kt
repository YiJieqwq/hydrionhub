package io.hydrion.hub.ui.messages

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkChatFadeTest {
    private val chat = darkChatFade(
        height = 2608f, headerHeight = 335f, bottomBarHeight = 253f,
        capsuleTop = 2355f, capsuleBottom = 2503f, safeTop = 108f, safeBottom = 72f, density = 3f,
    )

    // Composite real antialiased glyphs using the same linear alpha stops as the drawing modifier.
    // Sampling pixel centers also catches guards that are a fraction of a pixel short.
    private fun brightestInk(fade: DarkChatFade, firstRow: Int, height: Int, reveal: Float): Int {
        val glyphs = BufferedImage(125, height, BufferedImage.TYPE_INT_RGB)
        val graphics = glyphs.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.color = Color.WHITE
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
            graphics.drawString("Hydrion 42", 2, 14)
        } finally {
            graphics.dispose()
        }
        var brightest = 0
        var originalMax = 0
        for (row in 0 until height) {
            val y = firstRow + row + .5f
            val transmission = (1f - alpha(fade.top, y, reveal, true)) * (1f - alpha(fade.bottom, y, reveal, false))
            for (column in 0 until glyphs.width) {
                val original = glyphs.getRGB(column, row) and 0xff
                originalMax = maxOf(originalMax, original)
                brightest = maxOf(brightest, (original * transmission).roundToInt())
            }
        }
        assertEquals("Expected actual opaque glyph ink in the sample", 255, originalMax)
        return brightest
    }

    private fun alpha(edge: DarkChatFadeEdge, y: Float, reveal: Float, top: Boolean): Float {
        if (if (top) y <= edge.guardY else y >= edge.guardY) return 1f
        val end = edge.endY(reveal)
        if (if (top) y >= end else y <= end) return 0f
        val t = (y - edge.guardY) / (end - edge.guardY)
        val (left, right) = edge.stops.zipWithNext().first { (_, right) -> t <= right.first }
        val fraction = (t - left.first) / (right.first - left.first)
        return left.second + fraction * (right.second - left.second)
    }

    @Test fun glyphsAtBothSafeEdgesAreBlackEvenWhenScrollRevealIsPartial() {
        assertEquals(108f, chat.top.guardY, .01f)
        assertEquals(2503f, chat.bottom.guardY, .01f)
        for (reveal in listOf(0f, .25f, 1f)) {
            assertEquals(0, brightestInk(chat, 50, 20, reveal))
            assertEquals(0, brightestInk(chat, 2503, 12, reveal))
            assertEquals(0, brightestInk(chat, 2520, 10, reveal))
            assertEquals(255, brightestInk(chat, 1280, 20, reveal))
        }
    }

    @Test fun shouldersRevealGraduallyAndKeepTheirSeparateReaches() {
        assertEquals(335f + 56f * 3f, chat.top.innerY, .01f)
        assertEquals(2355f - 28f * 3f, chat.bottom.innerY, .01f)
        for ((edge, top) in listOf(chat.top to true, chat.bottom to false)) {
            val sample = edge.guardY + (edge.innerY - edge.guardY) * .4f
            assertEquals(0f, alpha(edge, sample, .25f, top), .0001f)
            val intermediate = alpha(edge, sample, .6f, top)
            assertTrue(intermediate > 0f && intermediate < alpha(edge, sample, 1f, top))
            assertEquals(1f, edge.stops.first().second, .0001f)
            assertEquals(0f, edge.stops.last().second, .0001f)
            assertTrue(edge.stops.zipWithNext().all { (a, b) -> a.first < b.first && a.second >= b.second })
        }
    }

    @Test fun missedComposerFrameUsesBottomSafeInsetUntilCapsuleIsMeasured() {
        fun frame(top: Float?, bottom: Float?) = darkChatFade(
            640f, 90f, 154f, top, bottom, 24f, 32f, 1f,
        )
        val fallback = frame(null, null)
        for (stale in listOf(frame(610f, 680f), frame(500f, 500f))) {
            assertEquals(fallback.bottom, stale.bottom)
        }
        assertEquals(608f, fallback.bottom.guardY, .01f)
        assertEquals(0, brightestInk(fallback, 614, 20, .2f))
        val next = frame(480f, 590f)
        assertEquals(590f, next.bottom.guardY, .01f)
        assertEquals(0, brightestInk(next, 591, 14, .2f))
    }

    @Test fun shortenedImeViewportPreservesMeasuredGuardsAndAClearMiddle() {
        val ime = darkChatFade(300f, 90f, 175f, 127f, 270f, 26f, 20f, 1f)
        assertEquals(26f, ime.top.guardY, .01f)
        assertEquals(270f, ime.bottom.guardY, .01f)
        assertTrue(ime.top.innerY < ime.bottom.innerY)
        assertEquals(0, brightestInk(ime, 4, 18, .5f))
        assertEquals(0, brightestInk(ime, 270, 18, 1f))
        val clear = ((ime.top.innerY + ime.bottom.innerY) / 2f).roundToInt()
        assertEquals(255, brightestInk(ime, clear - 5, 10, 1f))
    }
}
