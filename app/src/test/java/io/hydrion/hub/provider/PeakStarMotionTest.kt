package io.hydrion.hub.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PeakStarMotionTest {
    private val fill = 249f
    private val width = 264f
    private val span = starDrawX(1f, fill) - starDrawX(0f, fill)

    private fun screenX(motion: PeakStarMotion, star: RailStar, rtl: Boolean): Float {
        val localX = starDrawX(motion.phase(star), fill)
        return if (rtl) width - localX else localX
    }

    @Test fun idleStarsDriftIndependentlyAndResumeInPlaceAfterFast() {
        val motion = PeakStarMotion()
        for (pause in listOf(0.0, .015, 2.8)) {
            motion.advance(3.5, false, false)
            val before = railStars.map { motion.phase(it) to motion.heightFraction(it) }
            motion.advance(.016, false, false)
            val changes = railStars.mapIndexed { i, star ->
                val dx = motion.phase(star) - before[i].first
                (dx - Math.round(dx)) to (motion.heightFraction(star) - before[i].second)
            }
            assertTrue(changes.any { it.first < 0f } && changes.any { it.first > 0f })
            assertTrue(changes.any { it.second < 0f } && changes.any { it.second > 0f })
            assertTrue(changes.all { abs(it.first) < .003f && abs(it.second) < .004f })

            val atSwitch = railStars.map { motion.phase(it) to motion.heightFraction(it) }
            motion.advance(0.0, true, false)
            assertEquals(atSwitch, railStars.map { motion.phase(it) to motion.heightFraction(it) })
            motion.advance(pause, true, false)
            val atRelease = railStars.map { motion.phase(it) to motion.heightFraction(it) }
            assertEquals(atSwitch.map { it.second }, atRelease.map { it.second })
            motion.advance(0.0, false, false)
            assertEquals(atRelease, railStars.map { motion.phase(it) to motion.heightFraction(it) })
        }
        val heights = railStars.map(motion::heightFraction)
        motion.advance(.2, false, false)
        assertTrue(railStars.indices.any { abs(motion.heightFraction(railStars[it]) - heights[it]) > .001f })
    }

    @Test fun fastAcceleratesLeftAndWrapsEveryStarBackToTheRight() {
        for (rtl in listOf(false, true)) for (fps in listOf(30, 60, 120)) {
            val motion = PeakStarMotion()
            motion.advance(1.37, false, rtl)
            val wraps = IntArray(railStars.size)
            val rightEdge = if (rtl) width - starDrawX(0f, fill) else starDrawX(1f, fill)
            val leftEdge = rightEdge - span
            var previousStep = 0f
            repeat(3 * fps) { frame ->
                val before = railStars.map { screenX(motion, it, rtl) }
                motion.advance(1.0 / fps, true, rtl)
                val steps = railStars.mapIndexed { i, star ->
                    val next = screenX(motion, star, rtl)
                    val dx = next - before[i]
                    if (dx > 0) {
                        wraps[i]++
                        assertTrue("Wrap must leave the left edge", before[i] < leftEdge + span * .05f)
                        assertTrue("Wrap must enter at the right edge", next > rightEdge - span * .05f)
                        span - dx
                    } else -dx
                }
                val step = steps.first()
                assertTrue("All stars must travel left together", steps.all { it > 0f && abs(it - step) < .0002f })
                assertTrue("Fast must ramp up without a discontinuity", step >= previousStep - .0002f)
                if (frame == 1) assertTrue(step > previousStep * 2)
                previousStep = step
            }
            assertTrue(wraps.all { it >= 3 })
        }
    }
}
