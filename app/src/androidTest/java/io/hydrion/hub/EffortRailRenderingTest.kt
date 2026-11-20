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

    @Test fun fastOnlyAddsDustInsideTheFilledSection() {
        selected.value = 1
        show()
        val still = image()
        screen.runOnIdle { fast.value = true }
        val moving = image()
        val dp = still.width / 248f
        var changed = 0
        for (y in 0 until still.height) for (x in 0 until still.width) {
            if (still.getPixel(x, y) == moving.getPixel(x, y)) continue
            assertTrue("Dust outside the fill at ($x,$y)", x <= still.width / 2 &&
                abs(y - still.height / 2) <= 13 * dp + 1)
            changed++
        }
        assertTrue("Enabled Fast should decorate a non-peak level", changed > 0)
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
