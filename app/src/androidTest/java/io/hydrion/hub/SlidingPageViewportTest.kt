package io.hydrion.hub

import android.graphics.Color as Pixel
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.ui.ChatComposerLayers
import io.hydrion.hub.ui.ChatSlidingPage
import io.hydrion.hub.ui.clipSlidingPageContent
import io.hydrion.hub.ui.pickerCapsuleBounds
import io.hydrion.hub.ui.searchLineHeightLines
import io.hydrion.hub.ui.theme.HydrionTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlidingPageViewportTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun toolViewportMeasuresAtRestingSizeAndClipsWhileTravelling() = checkPage(model = false)

    @Test fun modelListKeepsItsSettledScrollPaddingAndClipsOnlyTheTravellingExcess() = checkPage(model = true)

    @OptIn(ExperimentalLayoutApi::class)
    private fun checkPage(model: Boolean) {
        val active = mutableStateOf(false)
        val progress = mutableFloatStateOf(0f)
        val viewport = mutableStateOf(300.dp)
        val listMeasures = AtomicInteger()
        val pageCompositions = AtomicInteger()
        val selected = AtomicInteger(-1)
        val chatTouches = AtomicInteger()
        val last = 29
        val lineHeight = if (model) 58.dp else 44.dp
        val banner = if (model) 12.dp else 0.dp
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                HydrionTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val open by remember { derivedStateOf { progress.floatValue == 1f } }
                        ChatComposerLayers(
                            Modifier.size(180.dp, viewport.value).consumeWindowInsets(WindowInsets.safeDrawing)
                                .testTag("viewport"),
                            composer = {
                                Layout(content = {}, modifier = Modifier.fillMaxWidth().padding(top = banner, bottom = 8.dp)
                                    .pickerCapsuleBounds()) { _, limits ->
                                    val line = lineHeight.roundToPx()
                                    val extra = (68.dp.roundToPx() * (1f - progress.floatValue)).roundToInt()
                                    layout(limits.maxWidth, line + extra, searchLineHeightLines(line)) {}
                                }
                            },
                            picker = {}, trackPickerAnchor = { false },
                            freezeTimelineDock = active.value, panelActive = active.value, panelSettled = open,
                        ) { _, panelDock, excessDockPx ->
                            SideEffect { pageCompositions.incrementAndGet() }
                            Spacer(Modifier.fillMaxSize().background(Color.Black).clickable { chatTouches.incrementAndGet() })
                            if (active.value) ChatSlidingPage({ progress.floatValue }, open, "travel") {
                                val pageModifier = if (model) Modifier.fillMaxSize()
                                    else Modifier.fillMaxSize().padding(bottom = panelDock)
                                Box(pageModifier.clipSlidingPageContent(excessDockPx)) {
                                    LazyColumn(Modifier.fillMaxSize().layout { child, constraints ->
                                        listMeasures.incrementAndGet()
                                        val measured = child.measure(constraints)
                                        layout(measured.width, measured.height) { measured.place(0, 0) }
                                    }.background(Color.Blue).testTag("rows"), contentPadding = PaddingValues(
                                        bottom = if (model) panelDock + 8.dp else 0.dp
                                    )) {
                                        items(last + 1) { index ->
                                            Spacer(Modifier.fillMaxWidth().height(48.dp).testTag("row-$index")
                                                .clickable { selected.set(index) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val host = screen.onNodeWithTag("viewport")
        val density = screen.activity.resources.displayMetrics.density
        val line = (lineHeight.value * density).roundToInt()
        val overflow = (68f * density).roundToInt()
        val gap = (8f * density).roundToInt() + (banner.value * density).roundToInt()
        assertEquals("Closed chat doesn't premeasure the list", 0, screen.runOnIdle { listMeasures.get() })

        repeat(2) {
            screen.runOnIdle { active.value = true }
            host.fetchSemanticsNode()
            val firstCount = screen.runOnIdle { listMeasures.get() }
            val initialCompositions = pageCompositions.get()
            assertTrue(firstCount > 0)
            // Interrupt the opening and reverse it. Bounds and the active clip must agree on the same frame.
            for (p in listOf(.5f, .8f, .6f, .8f, 1f)) {
                screen.runOnIdle { progress.floatValue = p }
                val frame = host.captureToImage().asAndroidBitmap()
                assertEquals("Changing only the dock does not remeasure the scroll viewport",
                    firstCount, listMeasures.get())
                val extra = (overflow * (1f - p)).roundToInt()
                val y = (frame.height * p).roundToInt() - extra - (if (model) 0 else line + gap)
                val x = frame.width / 2
                assertTrue("Last visible blue pixels at p=$p", Pixel.blue(frame.getPixel(x, y - 2)) > 80)
                if (y + 2 < frame.height) assertTrue("Blue ends at the clip on this frame",
                    Pixel.blue(frame.getPixel(x, y + 2)) < 30)
                if (p < 1f) {
                    assertEquals("A changing editor height must not re-enter page composition",
                        initialCompositions, pageCompositions.get())
                    host.performTouchInput { click(Offset(x.toFloat(), (y - 6).toFloat())) }
                    assertEquals("Outgoing chat stays shielded", 0, chatTouches.get())
                    assertEquals("Travelling options cannot be activated", -1, selected.get())
                }
            }
            val countAtRest = listMeasures.get()
            // Model the host height reduction/increase from a real parent applying its IME insets.
            screen.runOnIdle { viewport.value = 220.dp }
            screen.onNodeWithTag("rows").performScrollToIndex(last)
            assertTrue("A changed parent viewport is remeasured", listMeasures.get() > countAtRest)
            val lastRow = screen.onNodeWithTag("row-$last")
            val lastBottom = lastRow.fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("The last option scrolls above the dock",
                lastBottom <= host.fetchSemanticsNode().boundsInRoot.bottom - line - gap + 1)
            lastRow.performTouchInput { click(center) }
            assertEquals("The last row can scroll clear of the settled composer and handle a real tap", last, selected.get())
            screen.runOnIdle {
                active.value = false
                progress.floatValue = 0f
                viewport.value = 300.dp
                selected.set(-1)
            }
            screen.onNodeWithTag("rows").assertDoesNotExist()
        }
    }
}
