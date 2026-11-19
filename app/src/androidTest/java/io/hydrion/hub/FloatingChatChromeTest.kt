package io.hydrion.hub

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.ui.HydrionApp
import io.hydrion.hub.ui.messages.CardMaterial
import io.hydrion.hub.ui.messages.MessageCardStyle
import io.hydrion.hub.ui.messages.MessageStyleStore
import io.hydrion.hub.ui.messages.MessageTone
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingChatChromeTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val density get() = screen.activity.resources.displayMetrics.density
    private fun bounds(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun assertDp(label: String, expected: Float, pixels: Float) {
        assertEquals(label, expected, pixels / density, 1.5f)
    }

    private fun checkHintLayout(shell: ComposeRect, startDp: Float, baselineDp: Float) {
        val hint = screen.onNodeWithText("Chat with AI", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        hint.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val box = hint.fetchSemanticsNode().boundsInRoot
        val baseline = box.top - shell.top + layout.firstBaseline
        assertDp("placeholder start", startDp, box.left - shell.left)
        if (screen.activity.resources.configuration.fontScale == 1f) {
            assertDp("placeholder first baseline", baselineDp, baseline)
        }
        Log.i("HydrionUiTest", "hint start=${box.left - shell.left} baseline=$baseline " +
            "lineBox=${box.height} font=${layout.layoutInput.style.fontSize} lineHeight=${layout.layoutInput.style.lineHeight}")
    }

    @Test fun capsuleTracksWindowAndKeyboardInDeviceDp() {
        val root = screen.onRoot().fetchSemanticsNode().boundsInRoot
        val panel = bounds("floating-header")
        val compact = bounds("composer-capsule")
        assertDp("compact height", 49.25f, compact.height)
        checkHintLayout(compact, 48f, 30.5f)
        assertDp("compact left margin", 35f, compact.left - panel.left)
        assertDp("compact right margin", 35f, panel.right - compact.right)
        val nav = screen.runOnIdle {
            ViewCompat.getRootWindowInsets(screen.activity.window.decorView)!!
                .getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        }
        assertDp("compact bottom clearance", maxOf(35f, nav / density + 8f), root.bottom - compact.bottom)

        screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction()).performClick()
        // Measure the keyboard edge independently of the composable. Wait for Android's IME resize to settle.
        var keyboardTop = 0
        screen.waitUntil(20_000) {
            val view = screen.activity.window.decorView
            val frame = Rect()
            screen.runOnUiThread {
                view.getWindowVisibleDisplayFrame(frame)
                val ime = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                keyboardTop = if (ime > 80 * density) view.height - ime else frame.bottom
            }
            val shell = screen.onNodeWithTag("composer-capsule", true).fetchSemanticsNode().boundsInWindow
            keyboardTop > 0 && view.height - keyboardTop > 80 * density &&
                abs(shell.height / density - 94.25f) < 1.5f && abs((keyboardTop - shell.bottom) / density - 12.25f) < 1.5f
        }
        val expanded = bounds("composer-capsule")
        assertDp("expanded height", 94.25f, expanded.height)
        checkHintLayout(expanded, 15f, 35f)
        assertDp("expanded left margin", 12.25f, expanded.left - panel.left)
        assertDp("expanded right margin", 12.25f, panel.right - expanded.right)
        val shellBottom = screen.onNodeWithTag("composer-capsule", true).fetchSemanticsNode().boundsInWindow.bottom
        assertDp("space above IME", 12.25f, keyboardTop - shellBottom)
    }

    @Test fun visibleImeKeepsEmptyComposerExpandedAcrossWindowFocusLoss() {
        val overlay = mutableStateOf(false)
        screen.runOnUiThread {
            screen.activity.setContent {
                val actual = LocalWindowInfo.current
                val window = remember(actual) {
                    object : WindowInfo by actual {
                        override val isWindowFocused: Boolean
                            get() = !overlay.value && actual.isWindowFocused
                    }
                }
                CompositionLocalProvider(LocalWindowInfo provides window) { HydrionApp() }
            }
        }
        val input = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
        val view = screen.activity.window.decorView
        fun imeHeight() = screen.runOnUiThread {
            ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        input.performClick()
        screen.waitUntil(20_000) {
            imeHeight() > 80 * density && abs(bounds("composer-capsule").height / density - 94.25f) < 1.5f
        }
        screen.waitForIdle()
        val expanded = bounds("composer-capsule")
        val keyboard = imeHeight()
        for (covered in listOf(true, false)) {
            screen.runOnUiThread { overlay.value = covered }
            screen.mainClock.advanceTimeBy(160)
            screen.waitForIdle()
            input.assertIsFocused()
            assertEquals(keyboard, imeHeight())
            assertEquals(expanded, bounds("composer-capsule"))
            screen.onNodeWithContentDescription("打开工具箱").assertIsDisplayed()
        }
    }

    @Test fun composerRendersAnIntermediateToolbarFrameOnTheComposeClock() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val clock = screen.mainClock
        val start = bounds("composer-capsule")
        clock.autoAdvance = false
        try {
            // A draft opens the same toolbar without the OS keyboard. Advance real Compose layout/draw one frame at a time.
            screen.runOnUiThread { vm.setDraft("x") }
            val frames = List(6) {
                clock.advanceTimeByFrame()
                screen.waitForIdle()
                sampleToolFrame()
            }
            Log.i("HydrionUiTest", "Compose-clock toolbar frames $frames")
            val end = frames.last()
            val middle = frames.firstOrNull { frame ->
                val travel = (frame.bounds.height - start.height) / (end.bounds.height - start.height)
                travel in 0.25f..0.75f
            }
            assertTrue("The toolbar must have a rendered intermediate height: $frames", middle != null)
            assertTrue("Entering a draft without the IME must keep its side margins",
                frames.all { it.bounds.left == start.left && it.bounds.right == start.right })
            requireNotNull(middle)
            assertTrue("The moving tool stroke must fade in: $frames", middle.ink in 50 until end.ink - 2)
            assertTrue("The tool must translate within the changing capsule: $frames", middle.glyphX + density < end.glyphX)
            assertTrue("The finished tool is #AFAFAF", abs(end.ink - 175) <= 2)
        } finally {
            clock.autoAdvance = true
            screen.runOnIdle { vm.setDraft("") }
        }
    }

    private data class ToolFrame(val bounds: ComposeRect, val ink: Int, val glyphX: Float)

    private fun sampleToolFrame(): ToolFrame {
        val shell = screen.onNodeWithTag("composer-capsule", true)
        val image = shell.captureToImage().asAndroidBitmap()
        // Crop around the second toolbar button, leaving out the editor, adjacent glyphs, and capsule edge.
        val xs = (46f * density).roundToInt()..(78f * density).roundToInt()
        val ys = image.height - (32f * density).roundToInt()..image.height - (15f * density).roundToInt()
        val ink = ys.maxOf { y -> xs.maxOf { x -> Color.red(image.getPixel(x, y)) } }
        val strokeColumns = xs.filter { x -> ys.any { y -> Color.red(image.getPixel(x, y)) >= ink - 2 } }
        val center = (strokeColumns.first() + strokeColumns.last()) / 2f
        return ToolFrame(shell.fetchSemanticsNode().boundsInRoot, ink, center)
    }

    @Test fun scrollingRevealsMessagesBehindChromeAndClearsFirstAndLastTurns() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val styles = MessageStyleStore.shared(screen.activity)
        val previousStyle = styles.style.value
        val fill = "#764588"
        val conversationId = screen.runOnIdle {
            check(vm.state.value.current.messages.isEmpty() && vm.state.value.draft.text.isEmpty())
            styles.update { style ->
                style.copy(tone = MessageTone.Light, material = CardMaterial.Solid, assistantBubble = true,
                    light = style.light.copy(assistant = MessageCardStyle(fill, fill, "#FFFFFF")))
            }
            vm.setDraft("开始")
            vm.send("Hydrion")
            vm.stop()
            val longAnswer = (1..64).joinToString("\n") { "第 $it 行。向上或向下滚动这段离线示例，可以检查浮动按钮旁的消息底色。" }
            vm.editMessage(vm.state.value.current.messages.last().id, longAnswer)
            vm.setDraft("最后一轮")
            vm.send("Hydrion")
            vm.stop()
            vm.editMessage(vm.state.value.current.messages.last().id, "结束")
            vm.state.value.current.id
        }
        try {
            val messages = vm.state.value.current.messages
            val timeline = screen.onNodeWithTag("message-timeline", true)
            screen.waitForIdle()
            val header = bounds("floating-header")
            val capsule = bounds("composer-capsule")
            val last = bounds("message-turn-${messages.last().id}")
            assertTrue("Last turn must be fully above composer ($last, $capsule)", last.height > 0 && last.bottom <= capsule.top)
            timeline.performScrollToIndex(0)
            val first = bounds("message-turn-${messages.first().id}")
            assertTrue("First turn must be fully below header ($first, $header)", first.height > 0 && first.top >= header.bottom)

            // Position the middle of the tall purple reply behind both overlays.
            timeline.performScrollToIndex(1)
            timeline.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, header.height + 180f * density) }
            screen.waitForIdle()
            val root = screen.onRoot().captureToImage().asAndroidBitmap()
            val ink = Color.parseColor(fill)
            val timelineBounds = timeline.fetchSemanticsNode().boundsInRoot
            assertTrue("Transcript viewport reaches header and lower clearance", timelineBounds.top < header.bottom && timelineBounds.bottom > capsule.bottom)
            val band = 3f * density
            val nearLeft = capsule.left - 5f * density
            val nearRight = capsule.right + 5f * density
            assertPaint(root, nearLeft, capsule.center.y, band, ink, "left of composer")
            assertPaint(root, nearRight, capsule.center.y, band, ink, "right of composer")
            assertPaint(root, capsule.center.x, capsule.bottom + 3f * density, band, ink, "below composer")
            assertPaint(root, header.center.x, header.bottom - 2f * density, 1.5f * density, ink, "under header capsules")
        } finally {
            screen.runOnIdle {
                styles.update { previousStyle }
                vm.deleteConversation(conversationId)
            }
        }
    }

    private fun assertPaint(bitmap: Bitmap, x: Float, y: Float, radius: Float, color: Int, name: String) {
        val area = ComposeRect(x - radius, y - radius, x + radius, y + radius)
        val found = (area.top.roundToInt()..area.bottom.roundToInt()).any { row ->
            (area.left.roundToInt()..area.right.roundToInt()).any { col ->
                col in 0 until bitmap.width && row in 0 until bitmap.height && bitmap.getPixel(col, row) == color
            }
        }
        assertTrue("Expected scrolling reply color $name at ($x, $y)", found)
    }
}
