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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.atomic.AtomicInteger
import androidx.lifecycle.ViewModelProvider
import io.hydrion.hub.MainActivity
import io.hydrion.hub.model.*
import io.hydrion.hub.provider.*
import io.hydrion.hub.ui.HydrionApp
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
                val models = List(18) { n -> ProviderModel("model-$n", "${names[n % names.size]} · ${n + 1}") }
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
            assertEquals(original.width, half.width, 1f)
            val intermediate = shellInkHeight(x, ink)
            assertTrue("The drawn shell must grow: $parkedInk → $intermediate", intermediate > parkedInk + 20)
            back()
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
        assertEquals(original.width, open.width, 1f)
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
        tag("chat-model-providers").performScrollToNode(hasTestTag("chat-model-provider:c"))
        tag("chat-model-provider:c").performClick()
        tag("chat-model-option:c/model-0").assertIsDisplayed()
        results.performScrollToNode(hasTestTag("chat-model-option:c/model-17"))
        tag("chat-model-option:c/model-17").assertIsDisplayed()
        capture("models-last")
        assertEquals(title, bounds("chat-model-heading"))
        assertEquals(queryBounds, bounds("chat-model-search-field"))
        assertEquals(rail, bounds("chat-model-providers"))
        results.performScrollToIndex(0)
        ui.onNodeWithText("Favorites").assertIsDisplayed()

        val search = tag("chat-model-search-field")
        search.performClick().assertIsFocused().performTextReplacement("no-matching-model")
        tag("chat-model-search-empty").assertIsDisplayed()
        assertEquals(rail, bounds("chat-model-providers"))
        tag("chat-model-provider:c").assertIsNotEnabled()
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
            tag("chat-model-list-back").performClick()
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
        list.performScrollToKey("model:1:b/model-9")
        val density = host.resources.displayMetrics.density
        assertEquals(6f * density, search.top - title.bottom, 1f)
        val frame = windowFrame()
        fun shadeAt(dp: Float): Int = Color.red(frame.getPixel(title.center.x.roundToInt(), (title.bottom + dp * density).roundToInt()))
        assertTrue("The header feather is painted across its gap", shadeAt(.5f) > shadeAt(5.5f))
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

    @Test fun toolsFollowClosingImeWithoutMovingHeaderAndScrollBeneathTheTitle() {
        editor.performClick().performTextInputSelection(selection)
        keyboard(260)
        ui.waitForIdle()
        val raised = shell
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
            repeat(22) { i ->
                if (i < 12) keyboard((260 * (11 - i)) / 12)
                clock.advanceTimeByFrame()
                ui.waitForIdle()
                capture("tools-" + (i + 1).toString().padStart(2, '0'))
            }
        } finally { clock.autoAdvance = true }
        val expanded = shell
        assertTrue(hides > dismissals)
        assertEquals(titleChip, bounds("conversation-chip"))
        assertEquals(messageBounds, ui.runOnIdle { tail.boundsInRoot })
        assertTrue(expanded.bottom > raised.bottom + 150f && expanded.top < raised.top)
        assertEquals(raised.width, expanded.width, 1f)
        assertEquals(header, bounds("floating-header"))
        tag("tools-search-field").assertDoesNotExist()
        val title = bounds("chat-tools-heading")
        tag("chat-tools-group:image").performScrollTo().performClick()
        tag("chat-tools-option:image:禁用").performScrollTo().assertIsDisplayed()
        assertEquals(title, bounds("chat-tools-heading"))
        tag("chat-tools-group:environment").performScrollTo().assertIsDisplayed()
        val showBeforeExit = shows
        clock.autoAdvance = false
        try {
            tag("chat-tools-back").performClick()
            repeat(22) { i ->
                clock.advanceTimeByFrame()
                ui.waitForIdle()
                capture("tools-close-" + i.toString().padStart(2, '0'))
            }
        } finally { clock.autoAdvance = true }
        editor.assertTextEquals(draft).assertIsNotFocused()
        assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        assertEquals(showBeforeExit, shows)
    }
}
