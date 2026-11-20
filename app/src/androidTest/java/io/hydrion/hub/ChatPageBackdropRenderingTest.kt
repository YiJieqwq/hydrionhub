package io.hydrion.hub

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.ui.ChatSlidingPage
import io.hydrion.hub.ui.SlidingPageBackdrop
import io.hydrion.hub.ui.chatPageHeader
import io.hydrion.hub.ui.theme.HydrionTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatPageBackdropRenderingTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun Bitmap.red(x: Int, y: Int) = android.graphics.Color.red(getPixel(x, y))

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun movingEdgePassesScreenPixelsAtDifferentTimesAndLeavesPageTextClear() {
        val travel = mutableFloatStateOf(0f)
        val height = mutableStateOf(280.dp)
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                HydrionTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(160.dp, height.value).consumeWindowInsets(WindowInsets.safeDrawing)
                            .background(Color.White).testTag("sample")) {
                            ChatSlidingPage({ travel.floatValue }, true, "page") {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("H", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
        fun render(progress: Float): Bitmap {
            screen.runOnIdle { travel.floatValue = progress }
            return screen.onNodeWithTag("sample").captureToImage().asAndroidBitmap()
        }
        fun fixedPixels(bitmap: Bitmap) = listOf(bitmap.height / 4, bitmap.height * 3 / 4)
            .map { y -> bitmap.red(bitmap.width / 8, y) }

        val body = (255f * (1f - SlidingPageBackdrop.BodyOpacity)).roundToInt()
        for (viewport in listOf(280.dp, 140.dp)) {
            screen.runOnIdle { height.value = viewport }
            assertEquals(listOf(255, 255), fixedPixels(render(0f)))
            val firstPass = fixedPixels(render(.25f))
            assertTrue(firstPass.first() in (body + 1)..254)
            assertEquals(255, firstPass.last())
            val middle = fixedPixels(render(.5f))
            assertEquals(body.toDouble(), middle.first().toDouble(), 2.0)
            assertEquals(255, middle.last())
            assertEquals(firstPass, fixedPixels(render(.25f)))
            val secondPass = fixedPixels(render(.75f))
            assertEquals(body.toDouble(), secondPass.first().toDouble(), 2.0)
            assertTrue(secondPass.last() in (body + 1)..254)

            val resting = render(1f)
            for (y in listOf(0, resting.height / 2, resting.height - 1)) {
                assertEquals(body.toDouble(), resting.red(resting.width / 8, y).toDouble(), 2.0)
            }
            val brightGlyph = (resting.height / 4 until 3 * resting.height / 4).any { y ->
                (resting.width / 4 until 3 * resting.width / 4).any { x -> resting.red(x, y) >= 250 }
            }
            assertTrue("A solid interior pixel of the white H stays bright on the translucent material", brightGlyph)
            assertEquals(middle, fixedPixels(render(.5f)))
        }
    }

    @Test fun headerMovesUpAndDimsWhileTheFollowingLayoutStaysInItsSlot() {
        val travel = mutableFloatStateOf(0f)
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(Modifier.width(100.dp).background(Color.Black).testTag("header-probe")) {
                        Spacer(Modifier.fillMaxWidth().height(64.dp).chatPageHeader { travel.floatValue }
                            .background(Color.White))
                        Spacer(Modifier.fillMaxWidth().height(8.dp).background(Color.Blue))
                    }
                }
            }
        }
        fun render(progress: Float): Bitmap {
            screen.runOnIdle { travel.floatValue = progress }
            return screen.onNodeWithTag("header-probe").captureToImage().asAndroidBitmap()
        }
        val initial = render(0f)
        val x = initial.width / 2
        val headerHeight = (0 until initial.height).first { initial.getPixel(x, it) == android.graphics.Color.BLUE }
        for (progress in listOf(.25f, .5f, 1f, .5f, .25f, 0f)) {
            val frame = render(progress)
            val litRows = (0 until headerHeight).count { frame.red(x, it) > 8 }
            assertEquals("Visible header height", (headerHeight * (1 - progress)).toDouble(), litRows.toDouble(), 1.5)
            assertEquals("Header foreground opacity", (255 * (1 - progress)).toDouble(), frame.red(x, 0).toDouble(), 2.0)
            assertEquals("Following row stays fixed", android.graphics.Color.BLUE, frame.getPixel(x, headerHeight))
        }
    }
}
