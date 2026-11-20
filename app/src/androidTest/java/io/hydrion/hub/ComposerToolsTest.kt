package io.hydrion.hub

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.input.key.Key
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.ui.composer.AwaitToolsImeDismissal
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerToolsTest {
    @get:Rule
    val screen = createAndroidComposeRule<MainActivity>()

    private val editor get() = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
    private val field get() = screen.onNodeWithTag("tools-search-field")
    private val page get() = screen.onNodeWithTag("chat-tools-page")
    private val tools get() = screen.onNodeWithContentDescription("打开工具箱")
    private fun action(name: String) = screen.onNodeWithTag("chat-tools-$name")
    private fun option(group: String, value: String) = action("option:$group:$value")

    private fun assertQuery(expected: String) {
        assertEquals(expected, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun imeVisible() = screen.runOnUiThread {
        ViewCompat.getRootWindowInsets(screen.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun withStableIme(visible: Boolean, actions: () -> Unit) {
        val view = screen.activity.window.decorView
        val frames = mutableListOf<Boolean>()
        val listener = ViewTreeObserver.OnPreDrawListener {
            frames += ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            true
        }
        assertEquals(visible, imeVisible())
        screen.runOnUiThread { view.viewTreeObserver.addOnPreDrawListener(listener) }
        try {
            actions()
            screen.waitForIdle()
            assertEquals(visible, imeVisible())
            screen.runOnUiThread {
                assertTrue("IME visibility during the tools transition: $frames",
                    frames.isNotEmpty() && frames.all { it == visible })
            }
        } finally {
            screen.runOnUiThread { view.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
    }

    private fun assertToolbarSlots(mode: Mode) {
        val add = screen.onNodeWithContentDescription("添加附件").assertIsEnabled().assertHasClickAction()
        val tools = screen.onNodeWithContentDescription("打开工具箱")
        val terminal = screen.onNodeWithContentDescription("工作区终端").assertIsNotEnabled().assertHasNoClickAction()
        val ordered = listOf(add, tools, terminal)
        val density = screen.activity.resources.displayMetrics.density
        val direction = if (screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        val targets = ordered.map { it.fetchSemanticsNode().boundsInRoot }
        val glyphs = ordered.mapIndexed { index, node ->
            val image = node.captureToImage().asAndroidBitmap()
            // The composer background is dark; the disabled gray tool is also brighter than this threshold.
            val columns = (0 until image.width).filter { x ->
                (image.height / 4 until 3 * image.height / 4).any { y -> Color.red(image.getPixel(x, y)) > 140 }
            }
            targets[index].left + (columns.first() + columns.last() + 1) / 2f
        }
        for (index in 1..2) {
            val before = targets[index - 1]
            val after = targets[index]
            assertEquals("$mode target width", 42f * density, after.width, 1f)
            assertEquals("$mode target spacing", 42f * density, direction * (after.center.x - before.center.x), 1f)
            assertEquals("$mode visible icon spacing", 39f * density, direction * (glyphs[index] - glyphs[index - 1]), 2f)
        }
        val terminalImage = terminal.captureToImage().asAndroidBitmap()
        val shift = -6f
        terminalImage.assertStroke("terminal upper chevron", 255, shift, 3.5f, 6.5f)
        terminalImage.assertStroke("terminal lower chevron", 255, shift, 3.5f, 11.5f)
        terminalImage.assertStroke("terminal underline", 255, shift, 13f, 14f)
    }

    @Test fun imageComposerKeepsTheSecondSlotAndItsKeyboardToSheetHandoff() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value }
        try {
            screen.runOnIdle {
                vm.setMode(Mode.Image)
                vm.setDraft("image draft")
            }
            screen.waitForIdle()
            screen.runOnIdle { assertEquals(Mode.Image, vm.state.value.current.mode) }
            assertToolbarSlots(Mode.Image)
            val tools = screen.onNodeWithContentDescription("打开工具箱")
            tools.assertToolInk(enabled = true)
            editor.performTouchInput { click(center) }
            screen.waitUntil(15_000) { imeVisible() }
            tools.assertIsEnabled().performTouchInput { click(center) }
            screen.waitUntil(15_000) { !imeVisible() && screen.onNodeWithText("Tools").isDisplayed() }
            page.assertDoesNotExist()
            screen.onNodeWithText("添加参考图").assertIsDisplayed()
            screen.onNodeWithText("生成参数").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            screen.onNodeWithText("Tools").assertDoesNotExist()
        } finally {
            screen.runOnIdle {
                vm.setDraft(previous.draft.text)
                vm.setMode(previous.current.mode)
            }
        }
    }

    @Test fun imageSheetWaitsForImeDismissalAndDropsCancelledRequests() {
        val pending = mutableStateOf(false)
        val imeActive = mutableStateOf(true)
        val eligible = mutableStateOf(true)
        val decisions = mutableListOf<Boolean>()
        screen.runOnUiThread {
            screen.activity.setContent {
                if (pending.value) {
                    AwaitToolsImeDismissal(imeActive = imeActive.value, eligible = eligible.value) { present ->
                        decisions += present
                        pending.value = false
                    }
                }
            }
        }
        screen.waitForIdle()
        val view = screen.activity.window.decorView
        screen.waitUntil(5_000) { screen.runOnUiThread { view.hasWindowFocus() } }
        val clock = screen.mainClock
        clock.autoAdvance = false
        fun change(block: () -> Unit) {
            screen.runOnUiThread(block)
            clock.advanceTimeByFrame()
            screen.waitForIdle()
        }
        fun assertDecisions(vararg expected: Boolean) {
            assertEquals(expected.toList(), screen.runOnUiThread { decisions.toList() })
        }
        try {
            change { pending.value = true }
            clock.advanceTimeBy(400)
            assertDecisions()
            change { imeActive.value = false }
            assertDecisions(true)
            clock.advanceTimeBy(1100)
            assertDecisions(true)

            // Disposing the request cancels its timer. Even a later hidden-inset signal must do nothing.
            change {
                imeActive.value = true
                pending.value = true
            }
            clock.advanceTimeBy(160)
            change { pending.value = false }
            change { imeActive.value = false }
            clock.advanceTimeBy(1100)
            assertDecisions(true)

            // The initiating route becomes ineligible on the same frame Android reports dismissal.
            change {
                imeActive.value = true
                pending.value = true
            }
            change {
                eligible.value = false
                imeActive.value = false
            }
            assertDecisions(true, false)
            clock.advanceTimeByFrame() // Dispose the completed request before issuing another.

            change {
                eligible.value = true
                imeActive.value = true
                pending.value = true
            }
            clock.advanceTimeBy(900)
            assertDecisions(true, false)
            clock.advanceTimeBy(160)
            assertDecisions(true, false, true)
        } finally {
            clock.autoAdvance = true
        }
    }

    @Test fun chatToolChoicesPersistIndependentlyWithoutOpeningTheKeyboard() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val before = vm.state.value.draft
        try {
            screen.runOnIdle {
                vm.setDraft("parked draft")
                vm.setSearch("模型内置搜索")
                vm.setImageTool("自定义图片生成")
            }
            assertToolbarSlots(Mode.Chat)
            screen.onNodeWithContentDescription("添加附件").assertGlyphGray("添加附件", 255)
            tools.assertToolInk(enabled = true)
            screen.onNodeWithContentDescription("工作区终端").performTouchInput { click(center) }
            page.assertDoesNotExist()

            withStableIme(false) {
                tools.performTouchInput { click(center) }
                page.assertIsDisplayed()
                action("count").assertIsDisplayed()
                option("web", "模型内置搜索").assertIsSelected()
                val custom = option("web", "自定义搜索")
                custom.assertIsDisplayed().performClick().assertIsSelected()
                screen.runOnIdle { assertEquals("自定义搜索", vm.state.value.draft.search) }
                action("group:image").performClick()
                option("image", "禁用").performClick().assertIsSelected()
                screen.runOnIdle { assertEquals("禁用", vm.state.value.draft.imageTool) }
                action("back").performTouchInput { click(center) }
                page.assertDoesNotExist()
                editor.assertTextEquals("parked draft")

                tools.performTouchInput { click(center) }
                custom.assertIsSelected()
                if (!option("image", "禁用").isDisplayed()) action("group:image").performClick()
                option("image", "禁用").assertIsSelected()
                screen.runOnUiThread { screen.activity.onBackPressedDispatcher.onBackPressed() }
                page.assertDoesNotExist()
            }
        } finally {
            screen.runOnIdle {
                vm.setDraft(before.text)
                vm.setSearch(before.search)
                vm.setImageTool(before.imageTool)
            }
        }
    }

    @Test fun travellingAttachmentAndSendSwapEndsAndReverseFromTheDrawnPosition() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val before = screen.runOnIdle { vm.state.value }
        val clock = screen.mainClock
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val pickerMonitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        fun slot(name: String) = screen.onNodeWithTag("chat-toolbar-$name-glyph-slot", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center
        fun visibleToolsHeight() = screen.onNodeWithTag("chat-tools-surface", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.height
        fun nextFrame() {
            clock.advanceTimeByFrame()
            screen.waitForIdle()
        }
        try {
            screen.runOnIdle { vm.setDraft("must not send") }
            val attachmentStart = slot("attach")
            val sendStart = slot("send")
            val travel = sendStart.x - attachmentStart.x
            fun attachProgress() = (slot("attach").x - attachmentStart.x) / travel
            fun sendProgress() = (sendStart.x - slot("send").x) / travel
            fun beginAndSampleMidFrame() {
                tools.performTouchInput { click(center) }
                repeat(48) {
                    nextFrame()
                    if (attachProgress() in 0.16f..0.65f) {
                        assertEquals("Both named slots travel together", attachProgress(), sendProgress(), .015f)
                        return
                    }
                }
                throw AssertionError("No intermediate positions for the attachment and send slots")
            }
            clock.autoAdvance = false
            beginAndSampleMidFrame()
            val midProgress = attachProgress()
            val midHeight = visibleToolsHeight()
            for (id in listOf("chat-composer-attach", "chat-composer-send", "chat-tools-clear", "chat-tools-back")) {
                screen.onAllNodes(hasTestTag(id) and hasClickAction()).assertCountEquals(0)
            }
            // Tap actual moving glyph centers, independently of action semantics. A leaked attach
            // is caught before launching DocumentsUI; a leaked send would add to the conversation.
            val root = screen.onRoot()
            val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
            listOf(slot("attach"), slot("send")).forEach { position ->
                root.performTouchInput { click(position - origin) }
            }
            clock.advanceTimeBy(1_000)
            screen.waitForIdle()
            page.assertIsDisplayed()
            assertEquals("The page descends with the moving toolbar", midProgress, midHeight / visibleToolsHeight(), .04f)
            screen.onNodeWithContentDescription("打开侧栏").assertDoesNotExist()
            fun actionCenter(name: String) = action(name).fetchSemanticsNode().boundsInRoot.center.x
            assertEquals("Send becomes Back at the old attachment end", attachmentStart.x, actionCenter("back"), 2f)
            assertEquals("Attachment becomes Clear at the old send end", sendStart.x, actionCenter("clear"), 2f)
            assertEquals(0, pickerMonitor.hits)
            screen.runOnUiThread {
                assertEquals("must not send", vm.state.value.draft.text)
                assertEquals(before.current.messages, vm.state.value.current.messages)
            }
            action("back").performTouchInput { click(center) }
            clock.advanceTimeBy(1_000)
            screen.waitForIdle()
            assertEquals(0f, attachProgress(), .01f)
            assertEquals(0f, sendProgress(), .01f)

            beginAndSampleMidFrame()
            val interrupted = attachProgress()
            screen.runOnUiThread { screen.activity.onBackPressedDispatcher.onBackPressed() }
            val reversing = List(45) {
                nextFrame()
                val value = attachProgress()
                assertEquals(value, sendProgress(), .015f)
                value
            }
            assertTrue("Back must reverse before reaching the tools endpoints: $reversing", reversing.max() < .95f)
            assertTrue("The return must have intermediate geometry: $reversing",
                reversing.any { it > .01f && it < interrupted - .01f })
            assertEquals(0f, reversing.last(), .01f)
            page.assertDoesNotExist()
            editor.assertTextEquals("must not send")
            screen.onNodeWithContentDescription("打开侧栏").assertIsDisplayed()
            screen.onNodeWithContentDescription("添加附件").assertIsEnabled()
            screen.onNodeWithTag("chat-composer-send").assertIsEnabled()
        } finally {
            clock.autoAdvance = true
            instrumentation.removeMonitor(pickerMonitor)
            screen.runOnIdle { vm.setDraft(before.draft.text) }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun toolQueryKeepsTheOpenKeyboardAndRestoresDraftSelectionAndAttachment() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val before = vm.state.value.draft
        val attachment = Attachment("tools-draft-file", "tools-draft.txt", mimeType = "text/plain")
        val selection = TextRange(2, 8)
        try {
            screen.runOnIdle { vm.addAttachment(attachment) }
            editor.performTouchInput { click(center) }
            screen.waitUntil(15_000) { imeVisible() }
            editor.performTextReplacement("first line\nsecond line")
            editor.performTextInputSelection(selection)
            val saved = screen.runOnIdle { vm.state.value }
            val model = screen.runOnIdle { vm.providers.catalog.value.active[Mode.Chat] }
            withStableIme(true) {
                tools.performTouchInput { click(center) }
                page.assertIsDisplayed()
                field.assertIsFocused()
                assertQuery("")
                screen.onNodeWithTag("chat-model-trigger").assertDoesNotExist()
                screen.onNodeWithContentDescription("工作区终端").assertDoesNotExist()
                val custom = option("web", "自定义搜索")
                val label = custom.fetchSemanticsNode().config[SemanticsProperties.Text].first().text
                field.performTextReplacement(label)
                custom.assertIsDisplayed()
                option("web", "模型内置搜索").assertDoesNotExist()
                action("group:image").assertDoesNotExist()
                field.performTextReplacement("does-not-match-any-tool")
                action("empty").assertIsDisplayed()
                screen.onNodeWithTag("chat-composer-send").assertDoesNotExist()
                screen.onNodeWithContentDescription("添加附件").assertDoesNotExist()
                field.performKeyInput {
                    keyDown(Key.Enter)
                    keyUp(Key.Enter)
                }
                assertQuery("does-not-match-any-tool")
                page.assertIsDisplayed()
                screen.runOnIdle {
                    assertEquals(saved.draft, vm.state.value.draft)
                    assertEquals(saved.current.messages, vm.state.value.current.messages)
                    assertEquals(model, vm.providers.catalog.value.active[Mode.Chat])
                }
                action("clear").performTouchInput { click(center) }
                field.assertIsFocused()
                assertQuery("")
                option("web", "模型内置搜索").assertIsDisplayed()
                field.performTextInput("query to discard")
                action("back").performTouchInput { click(center) }
                editor.assertIsFocused().assertTextEquals(saved.draft.text)
                assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
                screen.onNodeWithText(attachment.name).assertIsDisplayed()
                screen.onNodeWithTag("chat-composer-send").assertIsEnabled()
                screen.onNodeWithTag("chat-model-trigger").assertHasClickAction()
                screen.onNodeWithContentDescription("工作区终端").assertIsNotEnabled().assertHasNoClickAction()
                tools.performTouchInput { click(center) }
                assertQuery("")
                action("back").performTouchInput { click(center) }
            }
        } finally {
            screen.runOnIdle {
                vm.removeAttachment(attachment.id)
                vm.setDraft(before.text)
            }
        }
    }
}

internal fun SemanticsNodeInteraction.assertGlyphGray(label: String, expectedGray: Int) {
    val image = captureToImage().asAndroidBitmap()
    var solidPixels = 0
    var brightest = 0
    for (y in image.height / 4 until 3 * image.height / 4) {
        for (x in image.width / 4 until 3 * image.width / 4) {
            val color = image.getPixel(x, y)
            val channels = listOf(Color.red(color), Color.green(color), Color.blue(color))
            if (channels.all { abs(it - expectedGray) <= 2 }) solidPixels++
            brightest = maxOf(brightest, channels.max())
        }
    }
    assertTrue("$label has $solidPixels solid stroke pixels, brightest channel $brightest; expected $expectedGray",
        solidPixels >= 2 && abs(brightest - expectedGray) <= 2)
}

internal fun SemanticsNodeInteraction.assertToolInk(enabled: Boolean) {
    val bitmap = captureToImage().asAndroidBitmap()
    val shift = -3f
    val gray = if (enabled) 255 else 175
    for ((y, thumb) in listOf(3.5f to 11.5f, 9f to 5.5f, 14.5f to 10.5f)) {
        bitmap.assertStroke("tool track at $y, left", gray, shift, 2f, y)
        bitmap.assertStroke("tool track at $y, right", gray, shift, 15.5f, y)
        bitmap.assertStroke("tool thumb above $y", gray, shift, thumb, y - 1.6f)
        bitmap.assertStroke("tool thumb below $y", gray, shift, thumb, y + 1.6f)
    }
}

// Sample small regions of the glyph in its own 42dp control. The window contains the
// opaque stroke core even when a rounded or diagonal edge lands between device pixels.
private fun Bitmap.assertStroke(label: String, gray: Int, shift: Float, glyphX: Float, glyphY: Float) {
    val scale = width / 42f
    val rtl = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val x = width / 2f + ((if (rtl) -shift else shift) + glyphX - 9f) * scale
    val y = height / 2f + (glyphY - 9f) * scale
    val xs = (x - scale).roundToInt()..(x + scale).roundToInt()
    val ys = (y - scale).roundToInt()..(y + scale).roundToInt()
    val pixels = ys.flatMap { row -> xs.map { col -> getPixel(col, row) } }
    val peaks = listOf(pixels.maxOf(Color::red), pixels.maxOf(Color::green), pixels.maxOf(Color::blue))
    assertTrue("$label has RGB peaks $peaks; expected $gray", peaks.all { abs(it - gray) <= 2 })
}
