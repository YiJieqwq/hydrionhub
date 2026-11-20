package io.hydrion.hub.provider

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakStarMotionTest {
    private fun screenX(motion: RailStarMotion, star: RailStar, width: Float, fill: Float, rtl: Boolean): Float {
        val localX = starDrawX(motion.phase(star), fill)
        return if (rtl) width - localX else localX
    }

    private fun positions(motion: RailStarMotion) = railStars.map { motion.phase(it) to motion.heightFraction(it) }

    @Test fun idleStarsDriftIndependentlyAndResumeInPlaceAfterFast() {
        val motion = RailStarMotion()
        for (pause in listOf(0.0, .015, 2.8)) {
            motion.advance(3.5, false, false)
            val before = positions(motion)
            motion.advance(.016, false, false)
            val changes = railStars.mapIndexed { i, star ->
                val dx = motion.phase(star) - before[i].first
                (dx - Math.round(dx)) to (motion.heightFraction(star) - before[i].second)
            }
            assertTrue(changes.any { it.first < 0f } && changes.any { it.first > 0f })
            assertTrue(changes.any { it.second < 0f } && changes.any { it.second > 0f })
            assertTrue(changes.all { abs(it.first) < .003f && abs(it.second) < .004f })

            val atSwitch = positions(motion)
            motion.advance(0.0, true, false)
            assertEquals(atSwitch, positions(motion))
            motion.advance(pause, true, false)
            val atRelease = positions(motion)
            assertEquals(atSwitch.map { it.second }, atRelease.map { it.second })
            motion.advance(0.0, false, false)
            assertEquals(atRelease, positions(motion))
            motion.advance(.2, false, false)
            val drift = railStars.mapIndexed { i, star -> motion.heightFraction(star) - atRelease[i].second }
            assertTrue(drift.any { it < -.001f } && drift.any { it > .001f })
            assertTrue(drift.all { abs(it) < .035f })
        }
    }

    @Test fun regularAndPeakMatchEachStarsOldRegularScreenSpeedAndWrapToTheRight() {
        for (width in listOf(132f, 264f)) {
            val regularFill = railFillWidth(width, 15f, .4f)
            for (fill in listOf(regularFill, railFillWidth(width, 15f, 1f))) {
                val scale = fastStarSpeedScale(fill, regularFill)
                val span = starDrawX(1f, fill) - starDrawX(0f, fill)
                for (rtl in listOf(false, true)) for (fps in listOf(30, 60, 120)) {
                    val motion = RailStarMotion()
                    val left = if (rtl) width - starDrawX(1f, fill) else starDrawX(0f, fill)
                    val wraps = IntArray(railStars.size)
                    motion.advance(.18, true, rtl, scale)
                    repeat(10 * fps) {
                        val before = railStars.map { screenX(motion, it, width, fill, rtl) }
                        motion.advance(1.0 / fps, true, rtl, scale)
                        railStars.forEachIndexed { i, star ->
                            val next = screenX(motion, star, width, fill, rtl)
                            val dx = next - before[i]
                            val oldStep = 1.08 * regularFill / star.seconds / fps
                            if (dx > 0f) {
                                assertTrue(before[i] < left + oldStep + .0001)
                                assertTrue(next > left + span - oldStep - .0001)
                                wraps[i]++
                            }
                            val signedStep = if (dx > 0f) dx - span else dx
                            assertEquals(-oldStep, signedStep.toDouble(), .0001)
                        }
                    }
                    assertTrue(wraps.all { it > 0 })
                }
            }
        }
    }

    @Test fun acceleratingAndChangingFillRetainPhaseAcrossFrameRates() {
        val regular = 114f
        val peak = 250f
        for (rtl in listOf(false, true)) {
            val coarse = RailStarMotion()
            val fine = RailStarMotion()
            val start = coarse.phase(railStars.first())
            for ((leg, fill) in listOf(regular, peak, regular).withIndex()) {
                val scale = fastStarSpeedScale(fill, regular)
                val before = positions(coarse)
                coarse.advance(0.0, true, rtl, scale)
                assertEquals(before, positions(coarse))
                coarse.advance(.175, true, rtl, scale)
                repeat(21) { fine.advance(1.0 / 120, true, rtl, scale) }
                for (star in railStars) assertEquals(fine.phase(star), coarse.phase(star), .000001f)
                if (leg == 0) {
                    // The starting ramp integrates half the final velocity at 180ms.
                    val expected = .175 * .175 / (2 * .18) / railStars.first().seconds
                    val dx = coarse.phase(railStars.first()) - start
                    assertEquals(if (rtl) expected else -expected, (dx - Math.round(dx)).toDouble(), .000001)
                }
            }
        }
    }

    @Test fun verticalLaneLeavesRoomForEachPaintedRadiusWithoutPinningTheDrift() {
        for (density in listOf(1f, 2.75f)) {
            val height = 26 * density
            val gap = 1f + density
            for (star in railStars) {
                val radius = 1.5f * density * star.brightness
                val ys = (0..100).map { starDrawY(it / 100f, height, radius, gap) }
                assertTrue(ys.first() - radius >= gap - .0001f)
                assertTrue(ys.last() + radius <= height - gap + .0001f)
                assertTrue(ys.zipWithNext().all { (a, b) -> b > a })
            }
        }
    }

    @Test fun referenceWidthComesFromTheFilledRegionEndingAtTheThumbCenter() {
        assertEquals(15f, railFillWidth(264f, 15f, 0f), 0f)
        assertEquals(132f, railFillWidth(264f, 15f, .5f), 0f)
        assertEquals(249f, railFillWidth(264f, 15f, 1f), 0f)
        assertEquals(10f, railFillWidth(20f, 15f, 1f), 0f)
        assertEquals(1.0, fastStarSpeedScale(0f, 0f), 0.0)
    }
}
