package io.hydrion.hub.provider

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerBubbleTrailTest {
    @Test fun aStationaryEdgeAndSlowMotionLeaveNoTrace() {
        val crossing = Offset(-8f, 8f)
        assertEquals(0f, pickerEdgeTrailPx(305f, 392f, 392f, crossing, 1f), 0f)
        assertEquals(0f, pickerEdgeTrailPx(305f, 97f, 152f, Offset.Zero, 1f), 0f)
        assertEquals(0f, pickerEdgeTrailPx(0f, 200f, 600f, Offset(1.5f, 0f), 1f), 0f)
    }

    @Test fun listEntryAndItsReverseEchoOnlyTheMovingSides() {
        val toList = Offset(-8f, 8f)
        fun edge(from: Float, to: Float, velocity: Offset) = pickerEdgeTrailPx(250f, from, to, velocity, 1f)
        val left = edge(104f, 152f, toList)
        val top = edge(555f, 340f, toList)
        assertTrue(left < 0f && top > 0f)
        assertEquals(-left, edge(104f, 152f, -toList), 0f)
        assertEquals(-top, edge(555f, 340f, -toList), 0f)
        assertEquals(0f, edge(392f, 392f, toList), 0f)
        assertEquals(0f, edge(685f, 685f, toList), 0f)
    }

    @Test fun theTraceIsShortAtEveryDensityAndDoesNotDependOnRefreshRate() {
        for (fps in listOf(60f, 90f, 120f)) {
            for (density in listOf(1f, 2.75f, 4f)) {
                val pixelsPerFrame = 1200f * density / fps
                val weightsPerSecond = pixelsPerFrame * fps / (200f * density)
                val result = pickerEdgeTrailPx(0f, 200f * density, 0f, Offset(weightsPerSecond, 0f), density)
                assertEquals(-2f, result / density, .00001f)
            }
        }
        val slower = pickerEdgeTrailPx(0f, 200f, 0f, Offset(3f, 0f), 1f)
        assertTrue(slower > -2f && slower < 0f)
    }
}
