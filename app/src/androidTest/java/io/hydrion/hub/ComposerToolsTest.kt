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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Draft
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.provider.chatProtocol
import io.hydrion.hub.provider.route
import io.hydrion.hub.model.Mode
import io.hydrion.hub.ui.composer.AwaitToolsImeDismissal
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private fun dismissTools() {
        val grip = action("grip").fetchSemanticsNode().boundsInRoot.center
        val travel = screen.onNodeWithTag("composer-capsule", true).fetchSemanticsNode().boundsInRoot.height * .6f
        screen.onRoot().performTouchInput { swipe(grip, grip + androidx.compose.ui.geometry.Offset(0f, travel), 400) }
    }
    private fun option(group: String, value: String) = action("option:$group:$value")

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
        val tools = screen.onNodeWithContentDescription("打开工具箱").assertIsEnabled().assertHasClickAction()
        val terminal = screen.onNodeWithContentDescription("工作区终端")
        val ordered = if (mode == Mode.Image) {
            listOf(add, tools, terminal.assertIsNotEnabled().assertHasNoClickAction())
        } else {
            terminal.assertDoesNotExist()
            listOf(add, tools)
        }
        val density = screen.activity.resources.displayMetrics.density
        val direction = if (screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        val targets = ordered.map { it.fetchSemanticsNode().boundsInRoot }
        val glyphs = ordered.mapIndexed { index, node ->
            val image = node.captureToImage().asAndroidBitmap()
            // Each glyph has a red channel above the dark composer surface.
            val columns = (0 until image.width).filter { x ->
                (image.height / 4 until 3 * image.height / 4).any { y -> Color.red(image.getPixel(x, y)) > 140 }
            }
            targets[index].left + (columns.first() + columns.last() + 1) / 2f
        }
        for (index in 1 until ordered.size) {
            val before = targets[index - 1]
            val after = targets[index]
            assertEquals("$mode target width", 42f * density, after.width, 1f)
            assertEquals("$mode target spacing", 42f * density, direction * (after.center.x - before.center.x), 1f)
            assertEquals("$mode visible icon spacing", 39f * density, direction * (glyphs[index] - glyphs[index - 1]), 2f)
        }
        if (mode == Mode.Chat) {
            val microphone = screen.onNodeWithTag("chat-composer-dictate").assertIsDisplayed().assertIsEnabled()
                .assertHasClickAction()
            microphone.assertGlyphGray("chat microphone", 255)
        } else {
            screen.onNodeWithTag("chat-composer-dictate").assertDoesNotExist()
            val terminalImage = terminal.captureToImage().asAndroidBitmap()
            val shift = -6f
            terminalImage.assertStroke("terminal upper chevron", Color.WHITE, shift, 3.5f, 6.5f)
            terminalImage.assertStroke("terminal lower chevron", Color.WHITE, shift, 3.5f, 11.5f)
            terminalImage.assertStroke("terminal underline", Color.WHITE, shift, 13f, 14f)
        }
    }

    @Test fun effortCardOutsideTapUsesTheRealWorkspaceTargetOnce() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val oldDraft = screen.runOnIdle { vm.state.value.draft.text }
        val catalog = vm.providers.catalog.value
        val oldModel = catalog.active[Mode.Chat]
        val toolModel = catalog.entries(Mode.Chat).first { it.second.chatProtocol() == null }
        val root = screen.onRoot()
        val card = screen.onNodeWithTag("chat-model-card")
        val trigger = screen.onNodeWithTag("chat-model-trigger")
        fun tap(target: SemanticsNodeInteraction) {
            val point = target.fetchSemanticsNode().boundsInRoot.center - root.fetchSemanticsNode().boundsInRoot.topLeft
            root.performTouchInput { click(point) }
        }
        fun open() {
            tap(trigger)
            card.assertIsDisplayed()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val files = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val monitor = instrumentation.addMonitor(files, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            screen.runOnIdle { vm.providers.configure(Mode.Chat, toolModel.route, catalog.parameters(toolModel.first, toolModel.second)) }
            tap(editor)
            screen.waitUntil(15_000) { imeVisible() }
            editor.performTextReplacement("keep this draft")
            open()
            tap(tools)
            page.assertIsDisplayed()
            screen.waitUntil(15_000) { !imeVisible() }
            dismissTools()
            screen.waitUntil(15_000) { imeVisible() }
            editor.assertTextEquals("keep this draft")
            open()
            val shell = screen.onNodeWithTag("chat-picker-bubble", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val dock = screen.onNodeWithTag("composer-capsule", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val gap = androidx.compose.ui.geometry.Offset(shell.center.x, (shell.bottom + dock.top) / 2)
            val rootOrigin = root.fetchSemanticsNode().boundsInRoot.topLeft
            root.performTouchInput { click(gap - rootOrigin) }
            card.assertDoesNotExist()
            field.assertDoesNotExist()
            repeat(2) { n ->
                open()
                tap(screen.onNodeWithTag("chat-composer-attach"))
                card.assertDoesNotExist()
                screen.runOnIdle { assertEquals(n + 1, monitor.hits) }
            }
            editor.performTextReplacement("")
            open()
            val history = screen.runOnIdle { vm.state.value.current.messages }
            val send = screen.onNodeWithTag("chat-composer-send")
            send.assertIsNotEnabled()
            tap(send)
            card.assertDoesNotExist()
            screen.runOnIdle { assertEquals(history, vm.state.value.current.messages) }
            open()
            // At the drawer button's visual center the real header, not an overlay action, must run.
            tap(screen.onNodeWithContentDescription("打开侧栏"))
            screen.onNodeWithTag("drawer-settings").assertIsDisplayed()
            card.assertDoesNotExist()
        } finally {
            instrumentation.removeMonitor(monitor)
            screen.runOnIdle {
                vm.setDraft(oldDraft)
                if (oldModel != null) vm.providers.configure(Mode.Chat, oldModel, catalog.overrides[oldModel].orEmpty())
            }
        }
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
            tools.assertToolInk(enabled = true, mode = Mode.Image)
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
            tools.assertToolInk(enabled = true, mode = Mode.Chat)
            page.assertDoesNotExist()

            withStableIme(false) {
                tools.performTouchInput { click(center) }
                page.assertIsDisplayed()
                action("title").assertIsDisplayed()
                option("web", "模型内置搜索").assertIsSelected()
                val custom = option("web", "自定义搜索")
                custom.performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
                screen.runOnIdle { assertEquals("自定义搜索", vm.state.value.draft.search) }
                action("group:image").performScrollTo().performClick()
                option("image", "禁用").performScrollTo().performClick().assertIsSelected()
                screen.runOnIdle { assertEquals("禁用", vm.state.value.draft.imageTool) }
                action("group:web").performScrollTo().performClick()
                custom.assertDoesNotExist()
                dismissTools()
                page.assertDoesNotExist()
                screen.onNodeWithTag("chat-tools-surface", useUnmergedTree = true).assertDoesNotExist()
                editor.assertTextEquals("parked draft")

                tools.performTouchInput { click(center) }
                page.assertIsDisplayed()
                custom.assertIsSelected()
                option("image", "禁用").assertDoesNotExist()
                action("group:image").performScrollTo().performClick()
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

    @Test fun switchingConversationReleasesThePreviousChatsToolOptions() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val before = screen.runOnIdle { vm.state.value }
        check(before.current.messages.isEmpty() && before.draft == Draft())
        try {
            screen.runOnIdle { vm.setDraft("first chat") }
            tools.performTouchInput { click(center) }
            val firstOptions = page.fetchSemanticsNode().layoutInfo
            action("group:image").performScrollTo().performClick()
            option("image", "禁用").assertExists()
            dismissTools()
            page.assertDoesNotExist()

            screen.runOnIdle { vm.newConversation() }
            screen.waitForIdle()
            screen.runOnIdle {
                assertTrue(before.current.id != vm.state.value.current.id)
                assertFalse("Closing the conversation releases its retained nodes", firstOptions.isAttached)
                vm.setDraft("second chat")
            }
            page.assertDoesNotExist()
            tools.performTouchInput { click(center) }
            assertTrue(firstOptions !== page.fetchSemanticsNode().layoutInfo)
            field.assertDoesNotExist()
            option("web", "模型内置搜索").assertExists()
            option("image", "禁用").assertDoesNotExist()
        } finally {
            screen.runOnIdle { vm.setDraft("") }
        }
    }

    @Test fun openingToolsKeepsTheLastMessageStillAsTheShellExpands() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val conversation = screen.runOnIdle {
            check(vm.state.value.current.messages.isEmpty() && vm.state.value.draft.text.isEmpty())
            vm.setDraft((1..48).joinToString("\n") { "offline line $it" })
            vm.send("Hydrion")
            vm.stop()
            vm.editMessage(vm.state.value.current.messages.last().id, "end of transcript")
            vm.setDraft("line one\nline two\nline three")
            vm.state.value.current
        }
        val clock = screen.mainClock
        val capsule = screen.onNodeWithTag("composer-capsule", true)
        try {
            for (keyboardOpen in listOf(false, true)) {
                if (keyboardOpen) {
                    editor.performTouchInput { click(center) }
                    val view = screen.activity.window.decorView
                    var lastInset = -1
                    var stableSince = 0L
                    screen.waitUntil(15_000) {
                        val inset = screen.runOnUiThread {
                            ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                        }
                        val now = android.os.SystemClock.uptimeMillis()
                        if (lastInset != inset) {
                            stableSince = now
                            lastInset = inset
                        }
                        inset > 0 && now - stableSince >= 200
                    }
                }
                screen.waitForIdle()
                // The covering page hides chat semantics. Keep the actual, attached layout
                // node to read the same message's screen position throughout the transition.
                val tail = screen.onNodeWithTag("message-turn-${conversation.messages.last().id}", true)
                    .fetchSemanticsNode()
                val before = screen.runOnIdle { tail.boundsInRoot }
                val editorHeight = capsule.fetchSemanticsNode().boundsInRoot.height
                assertTrue("Tail message must be visible at the bottom", before.height > 0f)
                fun assertUnmoved() {
                    assertEquals("Tail message, IME open=$keyboardOpen", before, screen.runOnIdle { tail.boundsInRoot })

                }
                clock.autoAdvance = false
                tools.performTouchInput { click(center) }
                repeat(5) {
                    clock.advanceTimeBy(100)
                    assertUnmoved()
                }
                assertTrue("The original shell must have grown",
                    capsule.fetchSemanticsNode().boundsInRoot.height > editorHeight)
                dismissTools()
                clock.autoAdvance = true
                page.assertDoesNotExist()
            }
        } finally {
            clock.autoAdvance = true
            screen.runOnIdle { vm.deleteConversation(conversation.id) }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun toolsDismissTheKeyboardAndPreserveTheDraftSelectionAndAttachment() {
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
            val saved = screen.runOnIdle { vm.state.value.draft }
            tools.performTouchInput { click(center) }
            page.assertIsDisplayed()
            screen.waitUntil(15_000) { !imeVisible() }
            field.assertDoesNotExist()
            dismissTools()
            screen.waitUntil(15_000) { imeVisible() }
            editor.assertTextEquals(saved.text).assertIsFocused()
            assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            screen.onNodeWithText(attachment.name).assertIsDisplayed()
            screen.runOnIdle { assertEquals(saved, vm.state.value.draft) }
        } finally {
            screen.runOnIdle { vm.removeAttachment(attachment.id); vm.setDraft(before.text) }
        }
    }
}

internal fun SemanticsNodeInteraction.assertGlyphGray(label: String, expectedGray: Int) {
    val image = captureToImage().asAndroidBitmap()
    // Sub-mdpi strokes can fall entirely between pixel centers.
    val tolerance = if (fetchSemanticsNode().layoutInfo.density.density < 1f) 30 else 2
    var solidPixels = 0
    var brightest = 0
    for (y in image.height / 4 until 3 * image.height / 4) {
        for (x in image.width / 4 until 3 * image.width / 4) {
            val color = image.getPixel(x, y)
            val channels = listOf(Color.red(color), Color.green(color), Color.blue(color))
            if (channels.max() - channels.min() <= 2 && channels.all { abs(it - expectedGray) <= tolerance }) solidPixels++
            brightest = maxOf(brightest, channels.max())
        }
    }
    assertTrue("$label has $solidPixels solid stroke pixels, brightest channel $brightest; expected $expectedGray",
        solidPixels >= 2 && abs(brightest - expectedGray) <= tolerance)
}

internal const val ExpectedChatToolInk = Color.WHITE

internal fun SemanticsNodeInteraction.assertToolInk(enabled: Boolean, mode: Mode = Mode.Chat) {
    if (enabled) assertIsEnabled() else assertIsNotEnabled()
    val bitmap = captureToImage().asAndroidBitmap()
    if (mode == Mode.Chat) {
        assertGlyphGray("chat tool rings", Color.red(ExpectedChatToolInk))
        bitmap.assertToolRingPositions()
        return
    }
    val shift = -3f
    val ink = if (enabled) Color.WHITE else Color.rgb(175, 175, 175)
    for ((y, thumb) in listOf(3.5f to 11.5f, 9f to 5.5f, 14.5f to 10.5f)) {
        bitmap.assertStroke("tool track at $y, left", ink, shift, 2f, y)
        bitmap.assertStroke("tool track at $y, right", ink, shift, 15.5f, y)
        bitmap.assertStroke("tool thumb above $y", ink, shift, thumb, y - 1.6f)
        bitmap.assertStroke("tool thumb below $y", ink, shift, thumb, y + 1.6f)
    }
}

// The widest row through each rail crosses a dark hole inside its contrasting ring.
private fun Bitmap.assertToolRingPositions() {
    fun ringCenter(rows: IntRange): Float {
        val rail = rows.map { y ->
            (0 until width).filter { x -> Color.red(getPixel(x, y)) > 145 }
        }.maxBy { it.size }
        assertTrue("Each rail should span the glyph", rail.size >= 2 && rail.last() - rail.first() > width * .3f)
        val (left, right) = rail.zipWithNext().maxBy { (a, b) -> b - a }
        assertTrue("The rail must leave the ring interior hollow", right - left > 1)
        return (left + right) / 2f
    }
    val topRing = ringCenter(height / 4 until height / 2)
    val bottomRing = ringCenter(height / 2 until height * 3 / 4)
    assertTrue("The upper ring sits to the right of the lower ring", topRing > bottomRing + width * .05f)
}

// Sample small regions of the glyph in its own 42dp control. The window contains the
// opaque stroke core even when a rounded or diagonal edge lands between device pixels.
private fun Bitmap.assertStroke(label: String, ink: Int, shift: Float, glyphX: Float, glyphY: Float) {
    val scale = width / 42f
    val rtl = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val x = width / 2f + ((if (rtl) -shift else shift) + glyphX - 9f) * scale
    val y = height / 2f + (glyphY - 9f) * scale
    val xs = (x - scale).roundToInt()..(x + scale).roundToInt()
    val ys = (y - scale).roundToInt()..(y + scale).roundToInt()
    val pixels = ys.flatMap { row -> xs.map { col -> getPixel(col, row) } }
    val peak = Color.rgb(pixels.maxOf(Color::red), pixels.maxOf(Color::green), pixels.maxOf(Color::blue))
    fun matches(color: Int) = abs(Color.red(color) - Color.red(ink)) <= 2 &&
        abs(Color.green(color) - Color.green(ink)) <= 2 && abs(Color.blue(color) - Color.blue(ink)) <= 2
    assertTrue("$label has peak ${Integer.toHexString(peak)}, expected a stroke of ${Integer.toHexString(ink)}",
        matches(peak) && pixels.any(::matches))
}
