package io.hydrion.hub

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color as Pixel
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.provider.EffortRail
import io.hydrion.hub.provider.effortPalette
import io.hydrion.hub.provider.ordinaryFastRailColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class EffortRailRenderingTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private val stops = listOf("Low", "Medium", "High")
    private val selected = mutableStateOf(0)
    private val fast = mutableStateOf(false)
    private val direction = mutableStateOf(LayoutDirection.Ltr)

    private fun show() = screen.activityRule.scenario.onActivity { activity ->
        activity.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(248.dp, 70.dp).testTag("rail-fixture").background(Color(0xFF292A2D)),
                            contentAlignment = Alignment.Center) {
                            EffortRail({ selected.value.toFloat() / stops.lastIndex }, { 1f }, stops,
                                stops.lastIndex, selected.value, fast.value)
                        }
                    }
                }
            }
        }
    }

    private fun image() = screen.onNodeWithTag("rail-fixture").captureToImage().asAndroidBitmap()

    @Test fun railAndThumbHaveDistinctHeightsAndMirrorTheirEndpoint() {
        show()
        for (dir in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            screen.runOnIdle { direction.value = dir }
            val bitmap = image()
            val dp = bitmap.width / 248f
            val palette = effortPalette(stops, "Low", true)
            val middleY = bitmap.height / 2
            val thumbPixels = (0 until bitmap.width).filter { bitmap.getPixel(it, middleY) == palette.thumb.toArgb() }
            assertTrue("Thumb must be visible", thumbPixels.isNotEmpty())
            val thumbX = (thumbPixels.first() + thumbPixels.last()) / 2
            val expectedX = if (dir == LayoutDirection.Ltr) 15f else 233f
            assertEquals("Thumb centre (dp)", expectedX, thumbX / dp, 1f)
            // The fractional border and antialiasing cover the outermost pixels.
            assertEquals("Thumb width (dp)", 30f, thumbPixels.size / dp, 2f)
            val thumbRows = (0 until bitmap.height).count { bitmap.getPixel(thumbX, it) == palette.thumb.toArgb() }
            assertEquals("Thumb height (dp)", 30f, thumbRows / dp, 2f)
            val plainTrackX = (162 * dp).roundToInt()
            val trackRows = (0 until bitmap.height).count { bitmap.getPixel(plainTrackX, it) == palette.track.toArgb() }
            assertEquals("Track height (dp)", 26f, trackRows / dp, 2f)
        }
    }

    @Test fun ordinaryFastUsesItsBluePaletteAndOnlyAddsDustInsideTheFill() {
        selected.value = 1
        show()
        screen.runOnIdle { fast.value = true }
        val moving = image()
        val dp = moving.width / 248f
        val blue = ordinaryFastRailColors(true)
        var dustPixels = 0
        for (y in (moving.height / 2 - 11 * dp).roundToInt()..(moving.height / 2 + 11 * dp).roundToInt()) {
            // Both strips are clear of the thumb, rounded ends and ticks.
            for (x in (30 * dp).roundToInt()..(102 * dp).roundToInt()) {
                if (Pixel.red(moving.getPixel(x, y)) > Pixel.red(blue.fill.toArgb()) + 12) dustPixels++
            }
            for (x in (150 * dp).roundToInt()..(215 * dp).roundToInt()) {
                assertEquals("Unfilled groove", blue.track.toArgb(), moving.getPixel(x, y))
            }
        }
        assertTrue("Enabled Fast should decorate the blue fill", dustPixels > 0)
    }

    @Test fun peakGradientCrossfadesInBothDirections() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        selected.value = 1
        screen.mainClock.autoAdvance = false
        try {
            show()
            screen.mainClock.advanceTimeByFrame()
            val ordinary = fillRed(image())
            screen.runOnIdle { selected.value = stops.lastIndex }
            screen.mainClock.advanceTimeByFrame()
            val entry = fillRed(image())
            screen.mainClock.advanceTimeBy(112)
            val intermediate = fillRed(image())
            screen.mainClock.advanceTimeBy(176)
            val peak = fillRed(image())
            assertTrue("Entry must start near the displayed ordinary fill", abs(entry - ordinary) < abs(entry - peak))
            assertTrue("Expected a mixed frame", abs(intermediate - ordinary) > 1 && abs(intermediate - peak) > 1)
            screen.runOnIdle { selected.value = 1 }
            screen.mainClock.advanceTimeByFrame()
            val exit = fillRed(image())
            assertTrue("Exit must keep the displayed gradient initially", abs(exit - peak) < abs(exit - ordinary))
            screen.mainClock.advanceTimeBy(288)
            assertEquals(ordinary, fillRed(image()))
        } finally {
            screen.mainClock.autoAdvance = true
        }
    }

    // A median in the common filled section excludes isolated stars and samples the actual brush.
    private fun fillRed(bitmap: Bitmap): Int {
        val dp = bitmap.width / 248f
        val samples = mutableListOf<Int>()
        for (x in (36 * dp).roundToInt()..(62 * dp).roundToInt()) {
            samples += Pixel.red(bitmap.getPixel(x, bitmap.height / 2))
        }
        return samples.sorted()[samples.size / 2]
    }

    @Test fun arrivalMotesAppearOnlyAfterEnteringPeak() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        selected.value = stops.lastIndex
        screen.mainClock.autoAdvance = false
        try {
            show()
            screen.mainClock.advanceTimeBy(180)
            assertEquals("Opening at peak", 0, brightPixelsOutsideTrack(image()))
            screen.runOnIdle { selected.value = 1 }
            screen.mainClock.advanceTimeBy(64)
            screen.runOnIdle { selected.value = stops.lastIndex }
            screen.mainClock.advanceTimeBy(240)
            assertTrue("Arrival bloom should reach beyond the rail", brightPixelsOutsideTrack(image()) > 0)
        } finally {
            screen.mainClock.autoAdvance = true
        }
    }

    private fun brightPixelsOutsideTrack(bitmap: Bitmap): Int {
        val dp = bitmap.width / 248f
        var count = 0
        for (y in 0 until bitmap.height) {
            if (abs(y - bitmap.height / 2) < 18f * dp) continue
            for (x in (176 * dp).roundToInt() until bitmap.width) {
                if (Pixel.blue(bitmap.getPixel(x, y)) > 75) count++
            }
        }
        return count
    }
}
