@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.hydrion.hub.ui.composer

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.atomic.AtomicInteger
import androidx.lifecycle.ViewModelProvider
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.model.*
import io.hydrion.hub.provider.*
import io.hydrion.hub.ui.HydrionApp
import io.hydrion.hub.ui.messages.MessageStyleStore
import io.hydrion.hub.ui.messages.MessageTone
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerPanelJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: HubViewModel
    private lateinit var host: View
    private var hides = 0
    private var shows = 0
    private val ime = object : SoftwareKeyboardController {
        override fun hide() { hides++ }
        override fun show() { shows++ }
    }
    private val draft = "Which model should we try?"
    private val selection = TextRange(6, 11)
    private fun tag(id: String) = ui.onNodeWithTag(id, useUnmergedTree = true)
    private fun bounds(id: String) = tag(id).fetchSemanticsNode().boundsInRoot
    private fun drawn(id: String): Rect {
        val layout = tag(id).fetchSemanticsNode().layoutInfo
        return ui.runOnUiThread {
            val c = layout.coordinates
            Rect(c.localToRoot(Offset.Zero), c.localToRoot(Offset(c.size.width.toFloat(), c.size.height.toFloat())))
        }
    }
    private val shell get() = bounds("composer-capsule")
    private val editor get() = ui.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
    private fun back() = ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }

    @Before fun loadOfflineCatalog() {
        vm = ui.runOnIdle { ViewModelProvider(ui.activity)[HubViewModel::class.java] }
        ui.waitUntil(10_000) { vm.workspaceLoad.value == WorkspaceLoad.Ready }
        ui.runOnIdle {
            vm.setDraft("Can we compare a few models?")
            vm.send("Offline demo", search = "禁用", imageTool = "禁用", remoteModel = null)
            vm.stop()
            check(vm.editMessage(vm.state.value.current.messages.last().id, "The list has three sample providers. Open Models to browse them."))
            vm.rename(vm.state.value.current.id, "Model comparison")
            for ((id, label) in listOf("a" to "OpenAI examples", "b" to "Research lab", "c" to "Local archive")) {
                val names = listOf("GPT-4.1", "GPT-4o mini", "GPT reasoning", "Atlas", "Orion", "Nova")
                val models = List(18) { n -> ProviderModel("model-$n",
                    if (id == "c" && n == 17) "Single offline example" else "${names[n % names.size]} · ${n + 1}") }
                vm.providers.save(Provider(id, Mode.Chat, label, "local", "", models), "", null)
                vm.providers.setChatModelFavorite(RemoteModel(id, models[0].id), true)
            }
            vm.providers.configure(Mode.Chat, RemoteModel("a", "model-0"), emptyMap())
            vm.setDraft(draft)
            ui.activity.setContent {
                host = LocalView.current
                CompositionLocalProvider(LocalSoftwareKeyboardController provides ime) { HydrionApp() }
            }
        }
    }

    private fun keyboard(heightDp: Int) = ui.runOnUiThread {
        val height = (heightDp * host.resources.displayMetrics.density).roundToInt()
        val base = ViewCompat.getRootWindowInsets(host) ?: WindowInsetsCompat.CONSUMED
        val frame = WindowInsetsCompat.Builder(base)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
            .setVisible(WindowInsetsCompat.Type.ime(), height > 0).build()
        ViewCompat.dispatchApplyWindowInsets(host, frame)
    }

    private var pixels: Bitmap? = null
    private fun windowFrame(): Bitmap {
        val status = AtomicInteger(-1)
        val frame = ui.runOnUiThread {
            val window = ui.activity.window
            val view = window.decorView
            val frame = pixels?.takeIf { it.width == view.width && it.height == view.height }
                ?: Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { pixels = it }
            PixelCopy.request(window, frame, status::set, Handler(Looper.getMainLooper()))
            frame
        }
        ui.waitUntil(5_000) { status.get() >= 0 }
        check(status.get() == PixelCopy.SUCCESS)
        return frame
    }

    private fun capture(name: String) {
        val path = System.getenv("HYDRION_PANEL_PREVIEW_DIR") ?: return
        val frame = windowFrame()
        File(path, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun backingPixel(box: Rect): Int = windowFrame().getPixel(
        (box.left + 4 * ui.density.density).roundToInt(), box.center.y.roundToInt())

    private fun tapOutside(at: Offset) = ui.onRoot().performTouchInput { click(at) }

    private fun scrollMax(id: String) = tag(id).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    private fun dragList(list: SemanticsNodeInteraction, upward: Boolean) {
        val name = if (upward) "up" else "down"
        list.performTouchInput { down(Offset(centerX, height * if (upward) .78f else .22f)) }
        try {
            repeat(14) { i ->
                list.performTouchInput { moveBy(Offset(0f, height * if (upward) -.035f else .035f)) }
                ui.waitForIdle()
                capture("models-drag-$name-" + i.toString().padStart(2, '0'))
            }
        } finally {
            list.performTouchInput { advanceEventTime(150); up() }
        }
    }

    // Sample the shell itself at an inset that misses both its children and the border.
    // This observes the composited Android frame while the Compose clock is paused.
    private fun shellInkHeight(x: Int, color: Int): Int {
        val bitmap = windowFrame()
        var run = 0
        var longest = 0
        for (y in bitmap.height / 4 until bitmap.height) {
            run = if (bitmap.getPixel(x, y) == color) run + 1 else 0
            longest = maxOf(longest, run)
        }
        return longest
    }

    @Test fun modelsPullFromComposerReverseThenKeepSearchProvidersFavoritesAndEditorPosition() {
        val clock = ui.mainClock
        val original = shell
        val header = bounds("floating-header")
        val x = (original.left + 4 * host.resources.displayMetrics.density).roundToInt()
        val ink = windowFrame().getPixel(x, original.center.y.roundToInt())
        val parkedInk = shellInkHeight(x, ink)
        assertTrue(parkedInk > original.height / 2)
        editor.performTextInputSelection(selection)
        tag("chat-model-trigger").performClick()
        tag("chat-model-select").assertIsDisplayed()
        ui.waitForIdle()
        clock.autoAdvance = false
        try {
            tag("chat-model-select").performClick()
            repeat(9) { i ->
                clock.advanceTimeByFrame()
                ui.waitForIdle()
                capture("reverse-" + i.toString().padStart(2, '0'))
            }
            capture("model-interrupt")
            tag("chat-model-list").assertExists()
            val half = shell
            assertTrue("Width grows from the parked dock", half.width > original.width)
            assertEquals(original.bottom, half.bottom, 1f)
            val middleRail = drawn("chat-model-providers")
            val middleTools = drawn("composer-action-row")
            val intermediate = shellInkHeight(x, ink)
            assertTrue("The drawn shell must grow: $parkedInk → $intermediate", intermediate > parkedInk + 20)
            back()
            assertEquals("The rail keeps its pose at reversal", middleRail, drawn("chat-model-providers"))
            assertEquals("The composer buttons keep their pose at reversal", middleTools, drawn("composer-action-row"))
            assertEquals("Back starts at the current rendered pixel", intermediate, shellInkHeight(x, ink))
            clock.advanceTimeByFrame()
            ui.waitForIdle()
            assertEquals("A stale opening tick must not grow the shell after Back",
                intermediate.toFloat(), shellInkHeight(x, ink).toFloat(), 3f)
            repeat(6) { i ->
                clock.advanceTimeByFrame()
                ui.waitForIdle()
                capture("reverse-" + (i + 9).toString().padStart(2, '0'))
            }
            val retreatingRail = drawn("chat-model-providers")
            assertTrue("The strip retreats through the lower edge", retreatingRail.top > middleRail.top)
            assertTrue("The strip shrinks back towards its center", retreatingRail.width < middleRail.width && retreatingRail.height < middleRail.height)
            assertTrue("The old buttons return from below", drawn("composer-action-row").center.y < middleTools.center.y)
            assertTrue("The drawn shell recedes from $intermediate", shellInkHeight(x, ink) < intermediate)
        } finally { clock.autoAdvance = true }
        tag("chat-model-list").assertDoesNotExist()
        assertEquals(original, shell)
        tag("chat-model-trigger").performClick()
        tag("chat-model-select").assertIsDisplayed()
        ui.waitForIdle()
        clock.autoAdvance = false
        try {
            capture("models-00")
            tag("chat-model-select").performClick()
            repeat(22) { i ->
                clock.advanceTimeByFrame()
                ui.waitForIdle()
                capture("models-" + (i + 1).toString().padStart(2, '0'))
            }
        } finally { clock.autoAdvance = true }
        val open = shell
        val scale = host.resources.displayMetrics.density
        assertEquals(12f * scale, open.left, 1f)
        assertEquals(original.bottom, open.bottom, 1f)
        assertEquals("Long list stops at the window cap", windowFrame().height * .8f, open.height, 2f)
        assertTrue(open.top > header.bottom && open.height > original.height * 2)
        assertEquals(header, bounds("floating-header"))
        tag("chat-model-search-field").assertIsNotFocused()
        val title = bounds("chat-model-heading")
        val queryBounds = bounds("chat-model-search-field")
        val rail = bounds("chat-model-providers")
        val results = tag("chat-model-results")
        results.performScrollToIndex(0)
        ui.onNodeWithText("Favorites").assertIsDisplayed()
        dragList(results, upward = true)
        dragList(results, upward = false)
        assertEquals(title, bounds("chat-model-heading"))
        tag("chat-model-list-back").assertDoesNotExist()
        tag("chat-model-title").assertDoesNotExist()
        tag("chat-model-providers").performScrollToNode(hasTestTag("chat-model-provider:c"))
        tag("chat-model-provider:c").performTouchInput { click(center) }
        tag("chat-model-option:c/model-0").assertIsDisplayed()
        results.performScrollToNode(hasTestTag("chat-model-option:c/model-17"))
        tag("chat-model-option:c/model-17").assertIsDisplayed()
        capture("models-last")
        assertEquals(title, bounds("chat-model-heading"))
        assertEquals(queryBounds, bounds("chat-model-search-field"))
        assertEquals(rail, bounds("chat-model-providers"))
        val search = tag("chat-model-search-field")
        val beforeBookmark = shows
        tag("chat-model-bookmark").performTouchInput { click(center) }
        ui.onNodeWithText("Favorites").assertIsDisplayed()
        search.assertIsNotFocused()
        assertEquals(beforeBookmark, shows)

        search.performClick().assertIsFocused().performTextReplacement("Single offline example")
        tag("chat-model-option:c/model-17").assertIsDisplayed()
        val short = shell
        val shortList = bounds("chat-model-results")
        assertTrue("One match should use less space than a full window", short.height < open.height / 2)
        assertEquals(0f, scrollMax("chat-model-results"), 0f)
        assertEquals(shortList.bottom, bounds("chat-model-providers").top, 1f)
        capture("models-short")
        search.performTextReplacement("no-matching-model")
        tag("chat-model-search-empty").assertIsDisplayed()
        assertTrue(shell.height < short.height)
        assertEquals(rail, bounds("chat-model-providers"))
        tag("chat-model-provider:c").assertIsNotEnabled()
        capture("models-empty")
        val bookmarkKeyboard = shows
        tag("chat-model-bookmark").performTouchInput { click(center) }
        ui.onNodeWithText("Favorites").assertIsDisplayed()
        assertEquals("", search.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(bookmarkKeyboard, shows)
        println("Model shell px: parked=$original long=$open one=$short, density=$scale")
        search.performTextReplacement("GPT")
        keyboard(250)
        ui.waitForIdle()
        assertTrue(shell.bottom < open.bottom - 150f)
        assertEquals("Title stays parked as search IME rises", title.top, bounds("chat-model-heading").top, 24f)
        search.assertTextContains("GPT")
        keyboard(0)
        val rowTag = "chat-model-option:b/model-6"
        results.performScrollToNode(hasTestTag(rowTag))
        val beforeEdit = bounds(rowTag).top
        tag(rowTag).performTouchInput { longClick(center) }
        tag("chat-model-config").assertIsDisplayed()
        back()
        search.assertTextContains("GPT").assertIsNotFocused()
        assertEquals(beforeEdit, bounds(rowTag).top, 2f)
        val showBeforeExit = shows
        capture("models-before-close")
        clock.autoAdvance = false
        try {
            back()
            repeat(23) { i ->
                clock.advanceTimeByFrame()
                ui.waitForIdle()
                capture("models-close-" + i.toString().padStart(2, '0'))
            }
        } finally { clock.autoAdvance = true }
        editor.assertTextEquals(draft).assertIsNotFocused()
        assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        assertEquals(showBeforeExit, shows)
    }

    @Test fun aModelScrollsOutAtFullWidthAboveAndShrinksGraduallyBelow() {
        ui.runOnIdle { vm.providers.configure(Mode.Chat, RemoteModel("b", "model-9"), emptyMap()) }
        tag("chat-model-trigger").performClick()
        tag("chat-model-select").performClick()
        val title = bounds("chat-model-heading")
        val search = bounds("chat-model-search-field")
        val rail = bounds("chat-model-providers")
        val list = tag("chat-model-results")
        val viewport = bounds("chat-model-results")
        val row = tag("chat-model-option:b/model-9")
        val density = host.resources.displayMetrics.density
        fun seam(y: Float): Int = windowFrame().getPixel(title.center.x.roundToInt(), (title.bottom + y * density).roundToInt())
        assertEquals("There is no stationary stripe at the start of the list", seam(.5f), seam(5.5f))
        list.performScrollToKey("model:1:b/model-9")
        assertEquals(8f * density, viewport.top - title.bottom, 1f)
        val searchBox = bounds("chat-model-search-box")
        assertEquals(16f * density, searchBox.left - shell.left, 1f)
        assertEquals(48f * density, searchBox.height, 1f)
        assertEquals(searchBox.bottom, title.bottom, 1f)
        val bookmark = bounds("chat-model-bookmark")
        assertEquals(searchBox.right, bookmark.right, 1f)
        assertTrue(search.right <= bookmark.left + 1f)
        val frame = windowFrame()
        val left = searchBox.left.roundToInt()
        val y = (searchBox.top + 3f * density).roundToInt()
        val outside = frame.getPixel(left - 2, y)
        val inside = frame.getPixel(left + (28 * density).roundToInt(), y)
        assertNotEquals(outside, inside)
        assertEquals("Curved search corner leaves the shell visible", outside, frame.getPixel(left + density.roundToInt(), y))
        assertEquals("14 dp corner reaches the fill", inside, frame.getPixel(left + (10 * density).roundToInt(), y))
        fun shadeAt(dp: Float): Int = Color.red(frame.getPixel(title.center.x.roundToInt(), (title.bottom + dp * density).roundToInt()))
        assertEquals("Scrolled empty padding retains the shell color", shadeAt(.5f), shadeAt(5.5f))
        // Unclipped viewport coordinates let us distinguish shrinking a row from simply cropping it.
        fun drawn(): Rect {
            val layout = row.fetchSemanticsNode().layoutInfo
            return ui.runOnIdle {
                val corners = layout.coordinates
                Rect(corners.localToRoot(Offset.Zero),
                    corners.localToRoot(Offset(layout.width.toFloat(), layout.height.toFloat())))
            }
        }
        fun placeAt(y: Float, frame: String): Rect {
            val delta = drawn().top - y
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            ui.waitForIdle()
            capture(frame)
            return drawn().also { assertEquals(y, it.top, 3f * density) }
        }
        fun selectionInk(row: Rect): Int {
            val frame = windowFrame()
            val y = (row.top + 2f * density).roundToInt()
            val painted = frame.getPixel(row.center.x.roundToInt(), y)
            val surface = frame.getPixel((viewport.left + density).roundToInt(), y)
            return Color.red(painted) - Color.red(surface)
        }
        val middle = placeAt(viewport.center.y, "scroll-middle")
        val middleInk = selectionInk(middle)
        val upper = placeAt(viewport.top - 8f * density, "scroll-top")
        assertEquals(middle.width, upper.width, 1f)
        val crossing = windowFrame()
        fun edgeInk(atDp: Float): Int {
            val py = (viewport.top + density * atDp).roundToInt()
            return Color.red(crossing.getPixel(upper.center.x.roundToInt(), py)) -
                Color.red(crossing.getPixel((viewport.left + density).roundToInt(), py))
        }
        assertTrue("Only scrolling content fades at the upper edge: ${edgeInk(1f)}, ${edgeInk(6f)}", edgeInk(1f) < edgeInk(6f))
        val near = placeAt(viewport.bottom - 21f * density, "scroll-near")
        val nearInk = selectionInk(near)
        val edge = placeAt(viewport.bottom - 8f * density, "scroll-edge")
        val edgeInk = selectionInk(edge)
        assertTrue("Selection fades into the shell: $middleInk, $nearInk, $edgeInk",
            middleInk > nearInk && nearInk > edgeInk && edgeInk >= 0)
        assertTrue("Lower edge widths: ${middle.width}, ${near.width}, ${edge.width}",
            middle.width > near.width && near.width > edge.width)
        assertEquals(near.width, placeAt(viewport.bottom - 21f * density, "scroll-return").width, 2f)
        assertEquals(middle.width, placeAt(viewport.center.y, "scroll-restored").width, 1f)
        assertEquals(title, bounds("chat-model-heading"))
        assertEquals(search, bounds("chat-model-search-field"))
        assertEquals(rail, bounds("chat-model-providers"))
    }

    @Test @Config(qualifiers = "zh-rCN-w393dp-h851dp-night-xhdpi")
    fun toolsFollowClosingImeWithoutMovingHeaderAndScrollBeneathTheTitle() {
        editor.performClick().performTextInputSelection(selection)
        keyboard(260)
        ui.waitForIdle()
        val raised = shell
        val actionWidth = drawn("composer-action-row").width
        val header = bounds("floating-header")
        val titleChip = bounds("conversation-chip")
        val tail = tag("message-turn-" + vm.state.value.current.messages.last().id).fetchSemanticsNode()
        val messageBounds = ui.runOnIdle { tail.boundsInRoot }
        val dismissals = hides
        val clock = ui.mainClock
        clock.autoAdvance = false
        try {
            capture("tools-00")
            ui.onNodeWithContentDescription("打开工具箱").performClick()
            val path = mutableListOf<Pair<Float, Float>>()
            var sawOverlap = false
            val readings = mutableListOf<String>("frame,ime_dp,bottom_px,top_minus_bottom_px,row_minus_bottom_px")
            repeat(42) { i ->
                val height = if (i < 20) 260 * (19 - i) / 20 else 0
                keyboard(height)
                clock.advanceTimeByFrame()
                ui.waitForIdle()
                capture("tools-" + (i + 1).toString().padStart(2, '0'))
                val box = shell
                val row = drawn("chat-tools-group:local")
                readings += "${i+1},$height,${box.bottom},${box.top-box.bottom},${row.top-box.bottom}"
                if (height >= 65) {
                    assertEquals("The whole editor descends at its original height", raised.height, box.height, 2f)
                    assertEquals(raised.width, box.width, 1f)
                }
                if (height in 26..39 && box.height > raised.height + 2f) {
                    assertEquals("Only the shell opens during overlap", actionWidth, drawn("composer-action-row").width, 1f)
                    sawOverlap = true
                }
                if (height == 0) path += box.height to (row.top - box.bottom)
            }
            assertTrue("Sampled the shell growing before the IME landed", sawOverlap)
            val end = path.last()
            val mid = path.filter { (it.first - raised.height) / (end.first - raised.height) in .52f.. .95f }
            assertTrue("Mid rows continue moving with the shell: $mid", mid.size >= 3 &&
                mid.zipWithNext().all { (first, next) -> next.second < first.second - 1f })
            System.getenv("HYDRION_PANEL_PREVIEW_DIR")?.let { File(it, "ime-motion.csv").writeText(readings.joinToString("\n")) }
        } finally { clock.autoAdvance = true }
        val expanded = shell
        assertTrue(hides > dismissals)
        assertEquals(titleChip, bounds("conversation-chip"))
        assertEquals(messageBounds, ui.runOnIdle { tail.boundsInRoot })
        assertTrue(expanded.bottom > raised.bottom + 150f && expanded.top < raised.top)
        assertEquals(raised.width, expanded.width, 1f)
        assertEquals(header, bounds("floating-header"))
        tag("tools-search-field").assertDoesNotExist()
        tag("chat-tools-back").assertDoesNotExist()
        tag("chat-tools-group:image").performScrollTo().performClick()
        val title = bounds("chat-tools-heading")
        tag("chat-tools-option:image:禁用").performScrollTo().assertIsDisplayed()
        assertEquals(title, bounds("chat-tools-heading"))
        tag("chat-tools-group:environment").performScrollTo().assertIsDisplayed()
        val showBeforeExit = shows
        clock.autoAdvance = false
        try {
            tapOutside(titleChip.center)
            var started = false
            var inset = 0
            repeat(40) { i ->
                if (shows > showBeforeExit) started = true
                if (started) {
                    inset = (inset + 22).coerceAtMost(260)
                    keyboard(inset)
                }
                clock.advanceTimeByFrame()
                ui.waitForIdle()
                capture("tools-close-" + i.toString().padStart(2, '0'))
                if (inset > 0) assertEquals("Rising editor keeps the invoking width", raised.width, shell.width, 1f)
                if (inset >= 110) assertEquals("Rising editor keeps the invoking height", raised.height, shell.height, 2f)
            }
            assertTrue("Closing restores the invoking editor's IME", started)
        } finally { clock.autoAdvance = true }
        editor.assertTextEquals(draft).assertIsFocused()
        tag("context-window").assertDoesNotExist()
        assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        assertEquals(raised, shell)
    }

    @Test @Config(qualifiers = "zh-rCN-w393dp-h851dp-night-xhdpi")
    fun emptyEditorPromptTravelsIntoModelSearchAndAnEarlyBackRestoresTheEditor() {
        ui.runOnIdle { vm.setDraft("") }
        editor.performClick()
        keyboard(260)
        ui.waitForIdle()
        val raised = shell
        val hint = ui.activity.getString(R.string.chat_message_hint, vm.state.value.composerAssistantName)
        ui.onNodeWithText(hint, useUnmergedTree = true).assertIsDisplayed()
        val clock = ui.mainClock
        val showBefore = shows
        fun begin() {
            tag("chat-model-trigger").performClick()
            tag("chat-model-select").assertIsDisplayed()
        }
        begin()
        clock.autoAdvance = false
        try {
            tag("chat-model-select").performClick()
            keyboard(155)
            clock.advanceTimeBy(64)
            assertEquals("Waiting for the real IME", raised.height, shell.height, 2f)
            back()
            clock.advanceTimeBy(64)
            keyboard(260)
            clock.advanceTimeBy(360)
        } finally { clock.autoAdvance = true }
        editor.assertIsFocused()
        assertTrue(shows > showBefore)
        tag("chat-model-grip").assertDoesNotExist()
        ui.onNodeWithText(hint, useUnmergedTree = true).assertIsDisplayed()
        begin()
        clock.autoAdvance = false
        try {
            capture("hint-model-00")
            tag("chat-model-select").performClick()
            repeat(40) { i ->
                keyboard(if (i < 16) 260 * (15 - i) / 16 else 0)
                clock.advanceTimeByFrame()
                ui.waitForIdle()
                capture("hint-model-" + (i + 1).toString().padStart(2, '0'))
            }
        } finally { clock.autoAdvance = true }
        tag("chat-model-search-field").assertIsNotFocused()
        ui.onNodeWithText(ui.activity.getString(R.string.chat_panel_model_search_hint), useUnmergedTree = true).assertIsDisplayed()
        assertEquals("", ui.runOnIdle { vm.state.value.draft.text })
    }

    @Test fun handleTracksTheFingerAndDecidesOnReleaseInBothPanels() {
        val root = ui.onRoot()
        val dp = ui.density.density
        for ((panel, raised) in listOf("tools" to false, "model" to true)) {
            if (raised) {
                editor.performClick().performTextInputSelection(selection)
                keyboard(260)
                ui.waitForIdle()
            }
            fun open() {
                if (panel == "model") {
                    tag("chat-model-trigger").performClick()
                    tag("chat-model-select").performClick()
                } else ui.onNodeWithContentDescription("打开工具箱").performClick()
                if (raised) keyboard(0)
                ui.waitForIdle()
                ui.mainClock.advanceTimeBy(400)
                ui.waitForIdle()
            }
            val editorFill = backingPixel(shell)
            open()
            val expanded = shell
            val grip = bounds("chat-$panel-grip").center
            val hit = bounds("chat-$panel-handle")
            val near = grip + Offset(0f, 9f * dp)
            assertTrue(hit.contains(near) && !bounds("chat-$panel-grip").contains(near))

            fun start(at: Offset = grip) = root.performTouchInput { down(at) }
            fun move(at: Offset, dy: Float, millis: Long = 100) {
                root.performTouchInput { moveTo(at + Offset(0f, dy), millis) }
                ui.waitForIdle()
            }
            fun slowRelease() = root.performTouchInput { advanceEventTime(200); up() }

            root.performTouchInput { click(grip); swipe(grip, grip + Offset(40f * dp, 0f), 200) }
            assertEquals("Taps and horizontal gestures keep the panel open", expanded, shell)
            start()
            capture("grip-$panel-00")
            for (i in 1..16) {
                val fraction = (if (i <= 8) i else 16 - i) * .075f
                val dy = expanded.height * fraction
                move(grip, dy)
                assertEquals("$panel shell follows the held finger at $fraction", expanded.top + dy, shell.top, 2f)
                assertEquals("$panel grip stays under the held finger", grip.y + dy, bounds("chat-$panel-grip").center.y, 2f)
                if (i == 8) assertEquals("$panel backing while held", editorFill, backingPixel(shell))
                capture("grip-$panel-" + i.toString().padStart(2, '0'))
            }
            assertEquals("Crossing the threshold while held does not dismiss", expanded, shell)
            slowRelease()
            assertEquals(expanded, shell)
            start(near)
            move(near, expanded.height * .55f)
            root.performTouchInput { cancel() }
            ui.mainClock.advanceTimeBy(400)
            assertEquals("Android pointer cancellation restores the open panel", expanded, shell)

            for (portion in listOf(.49f, .5f, .51f)) {
                start(near)
                move(near, expanded.height * portion, 650)
                val release = shell
                assertEquals("$panel held at $portion ($expanded -> $release)", expanded.top + expanded.height * portion,
                    release.top, 2f * dp)
                val before = shows
                val clock = ui.mainClock
                clock.autoAdvance = false
                try {
                    slowRelease()
                    clock.advanceTimeByFrame()
                    ui.waitForIdle()
                    assertEquals("Release continues from the held pose", release.top, shell.top, 3f * dp)
                    var inset = 0
                    repeat(40) {
                        if (raised && shows > before && inset < 260) {
                            inset = (inset + 28).coerceAtMost(260)
                            keyboard(inset)
                        }
                        clock.advanceTimeByFrame()
                        ui.waitForIdle()
                    }
                } finally { clock.autoAdvance = true }
                if (portion > .5f && raised) assertTrue("No draft IME request after release: shell=$shell grip=" +
                    ui.onAllNodesWithTag("chat-model-grip", true).fetchSemanticsNodes().size, shows > before)
                if (portion <= .5f) assertEquals("$panel slow release at $portion", expanded, shell)
            }
            tag("chat-$panel-grip").assertDoesNotExist()
            if (raised) {
                keyboard(260)
                editor.assertIsFocused()
                assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            } else editor.assertIsNotFocused()
            editor.assertTextEquals(draft)

            open()
            val again = bounds("chat-$panel-grip").center
            // The same short distance which springs back slowly dismisses on a downward fling.
            start(again)
            move(again, 56f * dp, 360)
            slowRelease()
            assertEquals(expanded.height, shell.height, 2f)
            root.performTouchInput {
                down(again)
                for (i in 1..5) moveTo(again + Offset(0f, i * 11.2f * dp), 8)
                up()
            }
            tag("chat-$panel-grip").assertDoesNotExist()
            if (raised) { keyboard(260); editor.assertIsFocused() } else editor.assertIsNotFocused()
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w393dp-h851dp-night-xhdpi")
    fun bothPanelsOpenFromActualParkedDraftAndReverseToIt() {
        editor.performClick().performTextInputSelection(selection)
        keyboard(260)
        val wide = shell
        keyboard(0)
        val parked = shell
        val editorFill = backingPixel(parked)
        assertTrue(parked.width < wide.width)
        val titleChip = bounds("conversation-chip")
        val restingActions = drawn("composer-action-row")
        val parkedSelection = editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        println("Selection before invoking either panel, after parking IME: $parkedSelection")
        val noNewKeyboard = shows
        val clock = ui.mainClock
        fun firstProviderInk(): Int {
            val text = ui.onAllNodes(hasAnyAncestor(hasTestTag("chat-model-providers")) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
            val frame = windowFrame()
            return (text.top.roundToInt() until text.bottom.roundToInt()).maxOf { y ->
                (text.left.roundToInt() until text.right.roundToInt()).maxOf { x -> Color.red(frame.getPixel(x, y)) }
            }
        }
        fun frames(name: String, start: Rect, opening: Boolean): List<Rect> {
            val path = mutableListOf(start)
            var previousRailScale: Float? = null
            var middleInk: Int? = null
            var fullInk: Int? = null
            repeat(26) { i ->
                clock.advanceTimeByFrame()
                ui.waitForIdle()
                capture("$name-" + (i + 1).toString().padStart(2, '0'))
                val current = shell
                if (i == 8 || i == 25) assertEquals("$name backing at frame $i", editorFill, backingPixel(current))
                val prev = path.last()
                assertEquals("$name frame $i bottom", parked.bottom, current.bottom, 1f)
                assertEquals("$name frame $i center", parked.center.x, current.center.x, 1f)
                assertTrue("$name frame $i: $prev → $current", if (opening)
                    current.width >= prev.width - 1f && current.top <= prev.top + 1f
                    else current.width <= prev.width + 1f && current.top >= prev.top - 1f)
                val fraction = (current.width - parked.width) / (wide.width - parked.width)
                if (fraction > .02f && fraction < .95f) {
                    val actions = drawn("composer-action-row")
                    val scale = actions.width / (restingActions.width + current.width - parked.width)
                    assertTrue("Toolbar shrinks towards its center", scale < 1f)
                    assertEquals(parked.center.x + (restingActions.center.x - parked.center.x) * scale, actions.center.x, 2f)
                    assertEquals("Toolbar has one planar scale", 1f - .36f * fraction, scale, .02f)
                    val natural = actions.height / scale
                    assertEquals("Toolbar descends", current.bottom + natural * (fraction - .5f), actions.center.y, 4f)
                }
                if (name.contains("model") && fraction > .01f) {
                    val rail = drawn("chat-model-providers")
                    val size = tag("chat-model-providers").fetchSemanticsNode().layoutInfo.coordinates.size
                    val scale = rail.width / size.width
                    assertEquals("Uniform provider scale", scale, rail.height / size.height, .005f)
                    assertEquals("Strip stays centered", current.center.x, rail.center.x, 1f)
                    if (fraction in 0.05f..0.85f) assertTrue("Partly expanded strip still scales: $scale", scale in 0.55f..0.99f)
                    if (fraction >= .999f) assertEquals(1f, scale, .005f)
                    previousRailScale?.let { previous ->
                        assertTrue("Scale follows the direction of travel", if (opening) scale >= previous - .001f else scale <= previous + .001f)
                    }
                    previousRailScale = scale
                    val inset = 6f * host.resources.displayMetrics.density
                    assertEquals("Provider strip crosses the live lower edge on the shell clock",
                        (size.height + inset) * fraction, current.bottom - rail.top, 3f)
                    assertTrue("Provider hit bounds stay clipped", bounds("chat-model-providers").bottom <= current.bottom + 1f)
                    if (opening && middleInk == null && fraction in 0.60f..0.75f) middleInk = firstProviderInk()
                    if (opening && fullInk == null && fraction >= .999f) fullInk = firstProviderInk()
                }
                path += current
            }
            assertTrue("$name shows intermediate widths", path.map { it.width }.distinct().size > 4)
            if (name == "parked-model") {
                assertTrue("Painted provider label fades in: $middleInk to $fullInk", checkNotNull(fullInk) - checkNotNull(middleInk) > 15)
            }
            return path
        }
        for (id in listOf("model", "tools")) {
            if (id == "model") {
                tag("chat-model-trigger").performClick()
                tag("chat-model-select").assertIsDisplayed()
            }
            ui.waitForIdle()
            clock.autoAdvance = false
            try {
                capture("parked-$id-00")
                if (id == "model") tag("chat-model-select").performClick()
                else ui.onNodeWithContentDescription("打开工具箱").performClick()
                assertEquals("First layout starts at the actual dock", parked, shell)
                val path = frames("parked-$id", parked, true)
                assertEquals(wide.width, path.last().width, 1f)
                assertEquals(wide.left, path.last().left, 1f)
                println("$id parked entry px: start=$parked middle=${path[8]} end=${path.last()}, fully edited=$wide")
            } finally { clock.autoAdvance = true }

            if (id == "tools") {
                for (group in listOf("environment", "mcp", "web")) tag("chat-tools-group:$group").performScrollTo().performClick()
                val short = shell
                assertEquals(0f, scrollMax("chat-tools-page"), 0f)
                capture("tools-short")
                tag("chat-tools-group:local").performClick()
                tag("panel-switch:Time").performClick().assertIsOn()
                val local = shell
                assertTrue(local.height > short.height)
                capture("tools-local")
                println("Tools shell px: groups=$short local=$local")
                tag("chat-tools-group:environment").performScrollTo().performClick()
                tag("chat-tools-page").performTouchInput { swipeDown() }
                tag("chat-tools-page").assertIsDisplayed()
            } else {
                ui.onNodeWithText("搜索模型或服务商", useUnmergedTree = true).assertIsDisplayed()
                tag("chat-model-search-field").assertIsNotFocused()
            }
            val beforeClose = shell
            clock.autoAdvance = false
            try {
                if (id == "model") tapOutside(titleChip.center) else back()
                val path = frames("parked-$id-close", beforeClose, false)
                assertEquals(parked, path.last())
            } finally { clock.autoAdvance = true }
            editor.assertTextEquals(draft).assertIsNotFocused()
            assertEquals(parkedSelection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            tag("context-window").assertDoesNotExist()
            assertEquals(noNewKeyboard, shows)
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w393dp-h851dp-night-xhdpi")
    fun orderedGroupsAndTracksUseOnlyTheExistingDraftModes() {
        ui.runOnIdle {
            vm.setSearch("模型内置搜索")
            vm.setImageTool(CUSTOM_IMAGE_TOOL)
        }
        val original = vm.state.value.draft
        ui.onNodeWithContentDescription("打开工具箱").performClick()
        val order = listOf("web" to "联网搜索", "image" to "图像生成", "local" to "本地工具", "mcp" to "MCP", "environment" to "工作环境")
        fun group(id: String) = tag("chat-tools-group:$id")
        for ((id, title) in order) {
            group(id).performScrollTo()
            ui.onNode(hasText(title) and hasAnyAncestor(hasTestTag("chat-tools-group:$id")), useUnmergedTree = true).assertIsDisplayed()
        }
        val ys = order.map { drawn("chat-tools-group:" + it.first).top }
        assertTrue("Panel order: $ys", ys.zipWithNext().all { (a, b) -> a < b })

        val rail = tag("chat-tools-track:web").performScrollTo()
        fun verify(mode: String) {
            tag("chat-tools-option:web:$mode").assertIsSelected()
            assertEquals(original.copy(search = mode), ui.runOnIdle { vm.state.value.draft })
        }
        verify("模型内置搜索")
        rail.performTouchInput { click(Offset(4f, centerY)) }
        verify("禁用")
        val trackBounds = bounds("chat-tools-track-fill:web")
        val sampleX = (trackBounds.left + trackBounds.width * .4f).roundToInt()
        val sampleY = (trackBounds.top + 7f * ui.density.density).roundToInt()
        val unselected = windowFrame().getPixel(sampleX, sampleY)
        rail.performTouchInput { down(Offset(width / 6f, centerY)); moveTo(Offset(width / 3f, centerY), 80) }
        try {
            verify("禁用")
            assertNotEquals("Thumb moves behind the labels before commit", unselected, windowFrame().getPixel(sampleX, sampleY))
        } finally {
            rail.performTouchInput { moveTo(center, 100); up() }
        }
        verify("自定义搜索")
        rail.performTouchInput { down(center); moveTo(Offset(right - 6f, centerY)); cancel() }
        verify("自定义搜索")
        tag("chat-tools-option:web:模型内置搜索").performClick()
        verify("模型内置搜索")
        rail.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        verify("自定义搜索")
        val afterSearch = ui.runOnIdle { vm.state.value.draft }
        group("image").performScrollTo().performClick()
        val imageTrack = tag("chat-tools-track:image").performScrollTo()
        for (id in listOf("web", "image")) {
            val heading = drawn("chat-tools-group:$id")
            val fill = drawn("chat-tools-track-fill:$id")
            assertEquals(heading.left, fill.left, 1f)
            assertEquals(heading.right, fill.right, 1f)
            assertEquals(32f * ui.density.density, fill.height, 1f)
            assertTrue(drawn("chat-tools-track:$id").height >= 48f * ui.density.density)
        }
        capture("compact-tracks")
        tag("chat-tools-option:image:$CUSTOM_IMAGE_TOOL").assertIsSelected()
        imageTrack.performTouchInput { swipe(Offset(right - 3, centerY), Offset(3f, centerY), 220) }
        tag("chat-tools-option:image:禁用").assertIsSelected()
        assertEquals(afterSearch.copy(imageTool = "禁用"), ui.runOnIdle { vm.state.value.draft })
        imageTrack.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertEquals(afterSearch, ui.runOnIdle { vm.state.value.draft })
        ui.onAllNodes(hasAnyAncestor(hasTestTag("chat-tools-section:image")) and isSelectable()).assertCountEquals(2)
        group("local").performScrollTo().performClick()
        rail.performScrollTo()
        val list = tag("chat-tools-page")
        fun scrollY() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val oldY = scrollY()
        rail.performTouchInput { swipe(center, center - Offset(0f, height * 2f), 240) }
        assertTrue("Vertical gesture over a rail scrolls the tool list", scrollY() > oldY + 8f)
        assertEquals(afterSearch, ui.runOnIdle { vm.state.value.draft })
        group("local").performScrollTo().performClick()
        group("web").performScrollTo()
        capture("tools-search-modes")
        back()
        ui.onNodeWithContentDescription("打开工具箱").performClick()
        verify("自定义搜索")
        ui.runOnIdle { vm.setSearch("future-preference") }
        for (mode in listOf("禁用", "自定义搜索", "模型内置搜索")) tag("chat-tools-option:web:$mode").assertIsNotSelected()
        tag("chat-tools-track:web").performTouchInput { click(Offset(5f, centerY)) }
        verify("禁用")
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h740dp-night-xhdpi", fontScale = 1.5f)
    fun longModeLabelsShareOnePaintedTrackAndMoveItsSelection() {
        ui.onNodeWithContentDescription("打开工具箱").performClick()
        tag("chat-tools-group:image").performScrollTo().performClick()
        val rail = tag("chat-tools-track:image").performScrollTo()
        val area = bounds("chat-tools-track-fill:image")
        capture("modes-en-large-start")
        val webHeight = drawn("chat-tools-track-fill:web").height
        assertEquals("Both painted rails accommodate the longest label", webHeight, drawn("chat-tools-track-fill:image").height, 1f)
        val selections = listOf("禁用" to "Off", CUSTOM_IMAGE_TOOL to "Custom image generation")
        val cells = selections.map { (key, label) ->
            val id = "chat-tools-option:image:$key"
            val text = ui.onNode(hasText(label) and hasAnyAncestor(hasTestTag(id)), useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val cell = bounds(id)
            val glyphs = text.fetchSemanticsNode().boundsInRoot
            assertTrue("$label text=$glyphs cell=$cell",
                glyphs.left >= cell.left - 1 && glyphs.right <= cell.right + 1 && glyphs.top >= cell.top - 1 &&
                    glyphs.bottom <= cell.bottom + 1)
            assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            val frame = windowFrame()
            val ink = layout.layoutInput.style.color.toArgb()
            for (line in 0 until layout.lineCount) {
                assertFalse("Full label on line $line", layout.isLineEllipsized(line))
                val left = (glyphs.left + layout.getLineLeft(line)).roundToInt()
                val right = (glyphs.left + layout.getLineRight(line)).roundToInt()
                val top = (glyphs.top + layout.getLineTop(line)).roundToInt()
                val bottom = (glyphs.top + layout.getLineBottom(line)).roundToInt()
                assertTrue("Line inside its slot", left >= cell.left - 1 && right <= cell.right + 1 && top >= cell.top - 1 && bottom <= cell.bottom + 1)
                assertTrue("Line paints ink in the actual track", (top until bottom).any { y ->
                    (left until right).any { x -> ColorUtils.calculateContrast(ink, frame.getPixel(x, y)) < 1.2 }
                })
            }
            assertEquals(area.top, cell.top, 1f)
            assertEquals(area.bottom, cell.bottom, 1f)
            if (key == CUSTOM_IMAGE_TOOL) assertTrue("Long label wraps at larger font scale", layout.lineCount > 1)
            cell to text
        }
        assertEquals(area.left, cells.first().first.left, 1f)
        assertEquals(cells.first().first.right, cells.last().first.left, 1f)
        assertEquals(area.right, cells.last().first.right, 1f)
        fun fills(): List<Int> {
            ui.waitForIdle()
            val currentArea = bounds("chat-tools-track-fill:image")
            val image = windowFrame()
            val y = (currentArea.top + 3 * ui.density.density).roundToInt()
            val outside = image.getPixel(currentArea.left.roundToInt() - 2, currentArea.center.y.roundToInt())
            val seam = cells.first().first.right.roundToInt()
            for (x in seam - 2..seam + 2) assertNotEquals("No gap through to the panel", outside, image.getPixel(x, currentArea.center.y.roundToInt()))
            return cells.map { (cell, text) ->
                val layouts = mutableListOf<TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val ink = layouts.single().layoutInput.style.color.toArgb()
                val fill = image.getPixel(cell.center.x.roundToInt(), y)
                assertTrue("Label contrast on its painted background", ColorUtils.calculateContrast(ink, fill) >= 4.5)
                fill
            }
        }
        tag("chat-tools-option:image:禁用").assertIsSelected()
        val before = fills()
        assertNotEquals(before.first(), before.last())
        rail.performTouchInput { click(Offset(width * .78f, centerY)) }
        tag("chat-tools-option:image:$CUSTOM_IMAGE_TOOL").assertIsSelected()
        assertEquals(before.reversed(), fills())
        assertEquals(CUSTOM_IMAGE_TOOL, ui.runOnIdle { vm.state.value.draft.imageTool })
        capture("modes-en-large")
        val styles = MessageStyleStore.shared(ui.activity)
        val original = styles.style.value
        try {
            ui.runOnIdle { styles.update { it.copy(tone = MessageTone.Light) } }
            val light = fills()
            assertNotEquals(light.first(), light.last())
            assertNotEquals(before.reversed(), light)
            ui.runOnIdle {
                vm.providers.save(deepSeekProvider, "", null)
                vm.providers.configure(Mode.Chat, deepSeekProvider.models.first().id, emptyMap())
            }
            rail.performScrollTo().assertIsNotEnabled()
            assertNotEquals("Disabled palette stays legible", light, fills())
            ui.runOnIdle { styles.update { original } }
            rail.performScrollTo().assertIsNotEnabled()
            assertNotEquals(before.reversed(), fills())
        } finally {
            ui.runOnIdle { styles.update { original } }
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w393dp-h851dp-night-xhdpi")
    fun searchProviderSettingsRemainReachableWithTheSharedCardCorners() {
        capture("corner-chat")
        ui.onNodeWithContentDescription("打开侧栏").performClick()
        tag("drawer-settings").performClick()
        tag("settings-back-circle").assertIsDisplayed()
        capture("corner-settings")
        val label = ui.activity.getString(R.string.settings_web_search)
        val target = hasText(label) and hasClickAction()
        if (ui.onAllNodes(target).fetchSemanticsNodes().isEmpty()) {
            ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasScrollAction())
                .performScrollToNode(target)
        }
        ui.onNode(target).performScrollTo().performClick()
        val control = ui.onNode(hasText(ui.activity.getString(R.string.search_local_demo)) and isToggleable())
            .assertIsDisplayed()
        val box = control.fetchSemanticsNode().boundsInRoot
        val bitmap = windowFrame()
        val density = host.resources.displayMetrics.density
        val x = box.left.roundToInt()
        val y = (box.top + 3f * density).roundToInt()
        val outside = bitmap.getPixel(x - 3, y)
        val fill = bitmap.getPixel(x + (34 * density).roundToInt(), y)
        assertNotEquals(outside, fill)
        val entrance = (0..(34 * density).roundToInt()).first { bitmap.getPixel(x + it, y) != outside } / density
        // At 3 dp below the top, a 24 dp arc begins at 12.4 dp from the side.
        assertEquals("Painted outer card corner", 12.4f, entrance, 1.8f)
        capture("corner-providers")
        back()
        tag("settings-back-circle").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "en-rUS-w1080dp-h720dp-night-mdpi")
    fun tapOutsideTheCenteredTabletColumnDismissesOnlyThePanel() {
        ui.onNodeWithContentDescription("打开工具箱").performClick()
        tag("chat-tools-page").assertIsDisplayed()
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        val farLeft = Offset(root.left + 3f, root.center.y)
        assertTrue(farLeft.x < shell.left)
        tapOutside(farLeft)
        tag("chat-tools-page").assertDoesNotExist()
        editor.assertTextEquals(draft).assertIsNotFocused()
    }
}
