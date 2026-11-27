package io.hydrion.hub

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.provider.deepSeekProvider
import io.hydrion.hub.provider.route
import io.hydrion.hub.ui.messages.MessageStyleStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.ui.test.performScrollToNode
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatModelPickerTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val editor get() = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
    private fun control(name: String) = screen.onNodeWithTag("chat-model-$name")
    private fun option(route: RemoteModel) = control("option:${route.providerId}/${route.modelId}")

    private fun softwareKeyboardIsOpen() = screen.runOnUiThread {
        val view = screen.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(view)
        val ime = WindowInsetsCompat.Type.ime()
        val nav = WindowInsetsCompat.Type.navigationBars()
        view.hasWindowFocus() && insets != null && insets.isVisible(ime) &&
            insets.getInsets(ime).bottom > insets.getInsets(nav).bottom
    }

    @Test fun draggingPreviewsEffortUntilReleaseAndCancellationRestoresTheCommittedEffort() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val store = vm.providers
        val before = store.catalog.value
        val previous = requireNotNull(before.selectedEntry(Mode.Chat)).route
        val reason = RemoteModel("demo-chat", "demo-reason")
        val oldValues = before.overrides[reason].orEmpty()
        val rtl = screen.activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        val appearance = MessageStyleStore.shared(screen.activity)
        val previousEffortColor = appearance.style.value.colorCodeReasoningEffort
        try {
            screen.runOnIdle {
                appearance.update { it.copy(colorCodeReasoningEffort = true) }
                store.configure(Mode.Chat, reason, oldValues + mapOf("effort" to "Low", "speed" to "Standard"))
            }
            editor.performTouchInput { click(center) }
            screen.waitUntil(15_000) { softwareKeyboardIsOpen() }
            // The keyboard can become visible before the toolbar enables its controls.
            screen.waitUntil(15_000) {
                screen.onAllNodes(hasTestTag("chat-model-trigger") and isEnabled() and hasClickAction())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            control("trigger").assertIsDisplayed().performTouchInput { click(center) }
            val track = control("effort").assertIsDisplayed()
            val density = screen.activity.resources.displayMetrics.density
            assertTrue("The rail needs a 48dp touch height", track.fetchSemanticsNode().boundsInRoot.height >= 48f * density - 1f)
            val title = screen.onNodeWithTag("chat-model-effort-title", useUnmergedTree = true)
            val trigger = control("trigger")
            val triggerBounds = trigger.fetchSemanticsNode().boundsInRoot
            val mic = screen.onNodeWithTag("chat-composer-dictate", useUnmergedTree = true)
            val micBounds = mic.fetchSemanticsNode().boundsInRoot
            fun assertEffort(titleRes: Int, committed: String) {
                val label = screen.activity.getString(titleRes)
                title.assertTextEquals(label)
                assertEquals(label, track.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
                assertEquals("Hydrion $label", trigger.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
                val suffix = screen.onNode(hasText(label) and hasAnyAncestor(hasTestTag("chat-model-source-label")),
                    useUnmergedTree = true).assertIsDisplayed()
                val ink = suffix.captureToImage().asAndroidBitmap()
                val peakInk = (0 until ink.height).any { y -> (0 until ink.width).any { x ->
                    val pixel = ink.getPixel(x, y)
                    Color.blue(pixel) > 170 && Color.blue(pixel) - Color.red(pixel) > 50 &&
                        Color.red(pixel) - Color.green(pixel) > 30
                } }
                assertEquals(titleRes == R.string.chat_controls_high, peakInk)
                assertEquals(triggerBounds, trigger.fetchSemanticsNode().boundsInRoot)
                assertEquals(micBounds, mic.fetchSemanticsNode().boundsInRoot)
                screen.runOnIdle { assertEquals(committed, store.selection(Mode.Chat).parameters["effort"]) }
            }

            // Both positions belong to Medium. A thumb that snaps while held cannot cover both in turn.
            fun dragWithinMedium(fraction: Float, other: Float) {
                track.performTouchInput { moveTo(Offset(width * fraction, centerY)) }
                assertEffort(R.string.chat_controls_medium, "Low")
                val image = track.captureToImage().asAndroidBitmap()
                fun hasThumbAt(x: Float): Boolean {
                    val pixel = image.getPixel((image.width * x).toInt(), image.height / 2)
                    return minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 242
                }
                assertTrue("The thumb should follow the finger at $fraction", hasThumbAt(fraction))
                assertTrue("The opposite part of the rail should stay exposed", !hasThumbAt(other))
            }
            assertEffort(R.string.chat_controls_low, "Low")

            // Exercise the top and bottom edges outside the painted rail.
            track.performTouchInput { down(Offset(if (rtl) 2.dp.toPx() else width - 2.dp.toPx(), 2.dp.toPx())) }
            assertEffort(R.string.chat_controls_high, "Low")
            dragWithinMedium(.37f, .63f)
            dragWithinMedium(.63f, .37f)
            track.performTouchInput { moveTo(Offset(if (rtl) 2.dp.toPx() else width - 2.dp.toPx(), centerY)) }
            assertEffort(R.string.chat_controls_high, "Low")
            track.performTouchInput { up() }
            assertEffort(R.string.chat_controls_high, "High")

            // At the bottom, start inside the card's 14dp rounded outline.
            track.performTouchInput { down(Offset(if (rtl) width - 14.dp.toPx() else 14.dp.toPx(), height - 2.dp.toPx())) }
            assertEffort(R.string.chat_controls_low, "High")
            track.performTouchInput { moveTo(center) }
            assertEffort(R.string.chat_controls_medium, "High")
            track.performTouchInput { cancel() }
            assertEffort(R.string.chat_controls_high, "High")
            control("card").assertIsDisplayed()
            editor.assertIsFocused()
            assertTrue("Dragging must preserve the editor's soft keyboard", softwareKeyboardIsOpen())
        } finally {
            screen.runOnIdle {
                appearance.update { it.copy(colorCodeReasoningEffort = previousEffortColor) }
                store.configure(Mode.Chat, reason, oldValues)
                if (previous != reason) store.configure(Mode.Chat, previous, before.overrides[previous].orEmpty())
            }
        }
    }

    @Test fun browsingProvidersDoesNotSwitchModelsUntilAPick() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val store = vm.providers
        val before = store.catalog.value
        val previous = requireNotNull(before.selectedEntry(Mode.Chat)).route
        val default = requireNotNull(before.defaultEntry(Mode.Chat))
        val reason = RemoteModel("demo-chat", "demo-reason")
        val mini = RemoteModel("demo-chat", "demo-quick")
        val deepSeek = RemoteModel(deepSeekProvider.id, deepSeekProvider.models.first().id)
        val originalDraft = vm.state.value.draft.text
        try {
            screen.runOnIdle {
                store.configure(Mode.Chat, reason, before.overrides[reason].orEmpty())
                vm.setDraft("model picker")
            }
            fun currentTrigger() = control("trigger").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            control("trigger").performClick()
            control("select").performClick()
            option(reason).assertIsSelected()
            option(mini).assertIsDisplayed()
            control("results").performScrollToNode(hasTestTag("chat-model-option:${deepSeek.providerId}/${deepSeek.modelId}"))
            option(deepSeek).assertIsDisplayed().assertIsNotSelected()
            screen.runOnIdle { assertEquals(reason, store.catalog.value.active[Mode.Chat]) }
            option(deepSeek).performClick()
            screen.runOnIdle { assertEquals(deepSeek, store.catalog.value.active[Mode.Chat]) }
            val networkParameters = store.selection(Mode.Chat).parameters
            control("fast").assertIsNotEnabled().performTouchInput { click(center) }
            control("ultrafast").assertDoesNotExist()
            screen.runOnIdle { assertEquals(networkParameters, store.selection(Mode.Chat).parameters) }
            control("select").performClick()
            control("results").performScrollToNode(hasTestTag("chat-model-option:${deepSeek.providerId}/${deepSeek.modelId}"))
            option(deepSeek).assertIsSelected()
            control("results").performScrollToNode(hasTestTag("chat-model-option:${reason.providerId}/${reason.modelId}"))
            screen.runOnIdle { assertEquals(deepSeek, store.catalog.value.active[Mode.Chat]) }
            option(reason).performClick()

            assertCardTargetsStaySeparate()
            control("select").performTouchInput { click(Offset(2.dp.toPx(), center.y)) }
            control("list").assertIsDisplayed()
            control("search-field").assertIsDisplayed()
            assertTrue("Browsing from a parked draft keeps the keyboard down", !softwareKeyboardIsOpen())
            control("search-clear").assertDoesNotExist()
            control("list-back").performTouchInput { click(center) }
            control("trigger").performClick()
            val effort = control("effort").assertIsDisplayed()
            val initial = store.selection(Mode.Chat).parameters
            val range = effort.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            val otherEnd = if (range.current == range.range.start) range.range.endInclusive else range.range.start
            effort.performSemanticsAction(SemanticsActions.SetProgress) { it(otherEnd) }
            screen.runOnIdle { assertNotEquals(initial["effort"], store.selection(Mode.Chat).parameters["effort"]) }
            control("fast").assertIsEnabled().performTouchInput { click(center) }
            val expectedSpeed = if (initial["speed"] == "Fast") "Standard" else "Fast"
            screen.runOnIdle { assertEquals(expectedSpeed, store.selection(Mode.Chat).parameters["speed"]) }

            control("select").performClick()
            option(mini).performClick()
            screen.runOnIdle { assertEquals(mini, store.catalog.value.active[Mode.Chat]) }
            assertEquals(store.selection(Mode.Chat).label, currentTrigger())
            control("card").assertIsDisplayed()
            control("effort").assertDoesNotExist()
            control("fast").assertIsNotEnabled()
            if (default.route == mini) {
                control("select").performClick()
                option(reason).performClick()
            }
            control("reset").performClick()
            screen.runOnIdle {
                assertEquals(default.route, store.catalog.value.selectedEntry(Mode.Chat)?.route)
                val expected = before.parameters(default.first, emptyMap())
                assertEquals(expected, store.catalog.value.parameters(default.first, default.second))
            }
        } finally {
            screen.runOnIdle {
                val touched = (listOf(reason, mini, deepSeek, default.route).distinct() - previous) + previous
                for (route in touched) store.configure(Mode.Chat, route, before.overrides[route].orEmpty())
                vm.setDraft(originalDraft)
            }
        }
    }

    @Test fun aConfiguredFastOverrideCannotEnableAnUnwiredHttpSpeedControl() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val store = vm.providers
        val before = store.catalog.value
        val previous = requireNotNull(before.selectedEntry(Mode.Chat)).route
        val demo = before.entries(Mode.Chat).single { it.route == RemoteModel("demo-chat", "demo-reason") }.first
        // Deliberately reuse the local model ID and its declared speed choices under a real HTTP protocol.
        val remote = deepSeekProvider.copy(id = "picker-test-" + UUID.randomUUID(), name = "Picker test", models = listOf(demo))
        val active = RemoteModel(remote.id, demo.id)
        val originalDraft = vm.state.value.draft.text
        try {
            screen.runOnIdle {
                store.save(remote, "", null)
                store.configure(Mode.Chat, active, mapOf("speed" to "Fast"))
                vm.setDraft("do not send")
            }
            control("trigger").performClick()
            control("fast").assertIsNotEnabled().assertIsOff().performTouchInput { click(center) }
            control("ultrafast").assertDoesNotExist()
            screen.runOnIdle {
                assertEquals(active, store.catalog.value.active[Mode.Chat])
                assertEquals("Fast", store.catalog.value.overrides[active]?.get("speed"))
            }
        } finally {
            screen.runOnIdle {
                store.remove(remote.id)
                store.configure(Mode.Chat, previous, before.overrides[previous].orEmpty())
                vm.setDraft(originalDraft)
            }
        }
    }

    private fun assertCardTargetsStaySeparate() {
        val targets = listOf("select", "fast", "reset", "effort")
            .associateWith { control(it).fetchSemanticsNode().boundsInRoot }
        val density = screen.activity.resources.displayMetrics.density
        val minTarget = 48f * density - 1f
        val topRow = listOf("fast", "select", "reset").map(targets::getValue).sortedBy { it.left }
        topRow.zipWithNext { left, right ->
            assertEquals("No reserved space between top-row targets", left.right, right.left, 1f)
        }
        val center = targets.getValue("select")
        assertTrue("The whole center, including the space around its text, is clickable: $center",
            center.height >= 60f * density - 1f && center.width >= minTarget)
        fun labelBounds(suffix: String) = screen.onNodeWithTag("chat-model-$suffix", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val title = labelBounds("effort-title")
        val model = labelBounds("name")
        val chevron = labelBounds("name-chevron")
        assertTrue("The model and chevron belong below the effort title", title.bottom <= model.top + 1f)
        assertEquals("Keep the chevron on the model's line", model.center.y, chevron.center.y, 1f)
        val rtl = screen.activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        assertTrue("The chevron follows the model name", if (rtl) chevron.right <= model.left else model.right <= chevron.left)
        for (label in listOf(title, model, chevron)) {
            assertTrue("Both lines share the model selection click target", center.contains(label.center))
        }
        assertTrue("The effort track spans the central selection area", targets.getValue("effort").width >= center.width)
        targets.entries.forEachIndexed { index, (name, box) ->
            assertTrue("$name has a 48dp touch target: $box", box.width >= minTarget && box.height >= minTarget)
            for ((otherName, otherBox) in targets.entries.drop(index + 1)) {
                assertTrue("Overlapping $name $box and $otherName $otherBox", !box.overlaps(otherBox))
            }
        }
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun appBackFromGroupedListDismissesThePickerWithoutChangingEditing() {
        editor.performTouchInput { click(center) }
        screen.waitUntil(15_000) { softwareKeyboardIsOpen() }
        val active = screen.runOnIdle {
            val vm = ViewModelProvider(screen.activity)[HubViewModel::class.java]
            requireNotNull(vm.providers.catalog.value.selectedEntry(Mode.Chat)).route
        }
        val draft = "editable draft"
        val selection = TextRange(2, 6)
        editor.performTextReplacement(draft)
        editor.performTextInputSelection(selection)

        var editing = true
        fun assertEditing() {
            if (editing) editor.assertIsFocused() else editor.assertIsNotFocused()
            editor.assertTextEquals(draft)
            assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            assertEquals(editing, softwareKeyboardIsOpen())
        }
        val triggerSize = control("trigger").fetchSemanticsNode().size
        val closedLabel = control("trigger").fetchSemanticsNode().config[SemanticsProperties.Text]
        val closeLabel = screen.activity.getString(R.string.chat_picker_close)
        val modelDescription = control("trigger").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        control("trigger").performTouchInput { click(center) }
        control("card").assertIsDisplayed()
        control("trigger").assertContentDescriptionEquals(closeLabel)
        assertEquals(closedLabel, control("trigger").fetchSemanticsNode().config[SemanticsProperties.Text])
        assertEquals(modelDescription, control("trigger").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals(triggerSize, control("trigger").fetchSemanticsNode().size)
        assertEditing()
        control("select").performTouchInput { click(center) }
        control("list").assertIsDisplayed()
        control("search-field").assertIsNotFocused()
        screen.waitUntil(15_000) { !softwareKeyboardIsOpen() }
        editing = false
        control("search-clear").assertDoesNotExist()
        option(active).assertIsSelected().performTouchInput { click(center) }
        control("card").assertIsDisplayed()
        assertEditing()
        control("select").performTouchInput { click(center) }
        control("list").assertIsDisplayed()
        control("search-field").assertIsNotFocused()
        option(active).assertIsSelected()
        control("search-field").assertIsNotFocused()
        assertFalse(softwareKeyboardIsOpen())
        // Exercise the Back delivered to the app; a physical Back can be consumed by the IME first.
        screen.runOnUiThread { screen.activity.onBackPressedDispatcher.onBackPressed() }
        screen.onNodeWithTag("chat-provider-list").assertDoesNotExist()
        control("list").assertDoesNotExist()
        control("search-field").assertDoesNotExist()
        control("card").assertDoesNotExist()
        assertEquals(closedLabel, control("trigger").fetchSemanticsNode().config[SemanticsProperties.Text])
        assertEquals(triggerSize, control("trigger").fetchSemanticsNode().size)
        assertEditing()

        // Open without a touch ripple so the reference image contains the resting state.
        control("trigger").performClick()
        val card = control("card").fetchSemanticsNode().boundsInRoot
        val trigger = control("trigger").fetchSemanticsNode().boundsInRoot
        val root = screen.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("The card fits horizontally", card.left >= root.left && card.right <= root.right)
        assertTrue("The active chip remains outside the anchored card",
            card.bottom <= trigger.top || card.top >= trigger.bottom)
        // Press at the leading edge of the full hit target, well outside the visible label.
        val button = control("trigger")
        val density = screen.activity.resources.displayMetrics.density
        val rtl = screen.activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        val edge = Offset(if (rtl) trigger.width - 3f * density else 3f * density, trigger.height / 2f)
        val label = screen.onNodeWithTag("chat-model-source-label", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.translate(-trigger.topLeft).inflate(10f * density)
        assertTrue("The test press is in the trigger's empty margin", !label.contains(edge))
        fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        val resting = button.captureToImage().asAndroidBitmap()
        val before = pixels(resting)
        button.performTouchInput { down(edge) }
        try {
            screen.waitUntil(3_000) {
                val during = pixels(button.captureToImage().asAndroidBitmap())
                var changed = 0
                for (index in before.indices) if (before[index] != during[index]) {
                    val point = Offset((index % resting.width) + .5f, (index / resting.width) + .5f)
                    assertTrue("Press feedback escaped the label at $point", label.contains(point))
                    changed++
                }
                changed > density * density
            }
        } finally {
            button.performTouchInput { cancel() }
        }
        control("card").assertIsDisplayed()
        assertEditing()
        button.performTouchInput { click(edge) }
        control("card").assertDoesNotExist()
        assertEditing()
    }
}
