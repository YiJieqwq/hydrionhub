package io.hydrion.hub

import android.graphics.Color
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
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
import androidx.test.platform.app.InstrumentationRegistry
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.provider.deepSeekProvider
import io.hydrion.hub.provider.route
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatModelPickerTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val editor get() = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
    private fun control(name: String) = screen.onNodeWithTag("chat-model-$name")
    private fun provider(id: String) = control("provider:$id")
    private fun option(route: RemoteModel) = control("option:${route.providerId}/${route.modelId}")

    private fun softwareKeyboardIsOpen() = screen.runOnUiThread {
        val view = screen.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(view)
        val ime = WindowInsetsCompat.Type.ime()
        val nav = WindowInsetsCompat.Type.navigationBars()
        view.hasWindowFocus() && insets != null && insets.isVisible(ime) &&
            insets.getInsets(ime).bottom > insets.getInsets(nav).bottom
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        screen.waitForIdle()
    }

    @Test fun draggingPreviewsEffortUntilReleaseAndCancellationRestoresTheCommittedEffort() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val store = vm.providers
        val before = store.catalog.value
        val previous = requireNotNull(before.selectedEntry(Mode.Chat)).route
        val reason = RemoteModel("demo-chat", "demo-reason")
        val oldValues = before.overrides[reason].orEmpty()
        val rtl = screen.activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        try {
            screen.runOnIdle {
                store.configure(Mode.Chat, reason, oldValues + mapOf("effort" to "Low", "speed" to "Standard"))
            }
            editor.performTouchInput { click(center) }
            screen.waitUntil(15_000) { softwareKeyboardIsOpen() }
            control("trigger").performTouchInput { click(center) }
            val track = control("effort").assertIsDisplayed()
            val title = screen.onNodeWithTag("chat-model-effort-title", useUnmergedTree = true)
            fun assertEffort(titleRes: Int, committed: String) {
                title.assertTextEquals(screen.activity.getString(titleRes))
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

            // Grab near opposing corners of the hit target, outside the painted rail.
            track.performTouchInput { down(Offset(if (rtl) 2.dp.toPx() else width - 2.dp.toPx(), 2.dp.toPx())) }
            assertEffort(R.string.chat_controls_high, "Low")
            dragWithinMedium(.37f, .63f)
            dragWithinMedium(.63f, .37f)
            track.performTouchInput { moveTo(Offset(if (rtl) 2.dp.toPx() else width - 2.dp.toPx(), centerY)) }
            assertEffort(R.string.chat_controls_high, "Low")
            track.performTouchInput { up() }
            assertEffort(R.string.chat_controls_high, "High")

            track.performTouchInput { down(Offset(if (rtl) width - 2.dp.toPx() else 2.dp.toPx(), height - 2.dp.toPx())) }
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
            control("trigger").performClick()
            control("select").performClick()
            option(reason).assertIsSelected()
            option(mini).assertIsDisplayed()
            option(deepSeek).assertDoesNotExist()

            control("provider-trigger").performClick()
            provider(reason.providerId).assertIsSelected()
            provider("demo-image").assertDoesNotExist()
            provider(deepSeek.providerId).performClick()
            control("list").assertIsDisplayed()
            option(reason).assertDoesNotExist()
            option(deepSeek).assertIsDisplayed().assertIsNotSelected()
            screen.runOnIdle { assertEquals(reason, store.catalog.value.active[Mode.Chat]) }
            option(deepSeek).performClick()
            screen.runOnIdle { assertEquals(deepSeek, store.catalog.value.active[Mode.Chat]) }
            val networkParameters = store.selection(Mode.Chat).parameters
            control("fast").assertIsNotEnabled().performTouchInput { click(center) }
            control("ultrafast").assertDoesNotExist()
            screen.runOnIdle { assertEquals(networkParameters, store.selection(Mode.Chat).parameters) }
            control("select").performClick()
            option(deepSeek).assertIsSelected()
            control("provider-trigger").performClick()
            provider(reason.providerId).performClick()
            screen.runOnIdle { assertEquals(deepSeek, store.catalog.value.active[Mode.Chat]) }
            option(reason).performClick()

            assertCardTargetsStaySeparate()
            control("select").performTouchInput { click(Offset(2.dp.toPx(), center.y)) }
            control("list").assertIsDisplayed()
            control("search-field").assertIsFocused()
            pressBack()
            control("search-field").assertDoesNotExist()
            control("search").assertIsDisplayed()
            pressBack()
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
            center.height >= 54f * density - 1f && center.width >= minTarget)
        assertTrue("The effort track spans the central selection area", targets.getValue("effort").width >= center.width)
        targets.entries.forEachIndexed { index, (name, box) ->
            assertTrue("$name has a 48dp touch target: $box", box.width >= minTarget && box.height >= minTarget)
            for ((otherName, otherBox) in targets.entries.drop(index + 1)) {
                assertTrue("Overlapping $name $box and $otherName $otherBox", !box.overlaps(otherBox))
            }
        }
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun backClosesProviderListThenModelListThenCardWithoutDisturbingEditing() {
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

        fun assertEditing() {
            editor.assertIsFocused().assertTextEquals(draft)
            assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            assertTrue("Soft keyboard and activity window retain focus", softwareKeyboardIsOpen())
        }
        val triggerSize = control("trigger").fetchSemanticsNode().size
        val closedLabel = control("trigger").fetchSemanticsNode().config[SemanticsProperties.Text]
        val effortLabel = screen.activity.getString(R.string.chat_controls_select_effort)
        control("trigger").performTouchInput { click(center) }
        control("card").assertIsDisplayed()
        control("trigger").assertTextEquals(effortLabel)
        assertEquals(triggerSize, control("trigger").fetchSemanticsNode().size)
        assertEditing()
        control("select").performTouchInput { click(center) }
        control("list").assertIsDisplayed()
        control("search-field").assertIsFocused()
        control("search-close").assertIsDisplayed()
        option(active).assertIsSelected().performTouchInput { click(center) }
        control("card").assertIsDisplayed()
        assertEditing()
        control("select").performTouchInput { click(center) }
        control("list").assertIsDisplayed()
        control("search-field").assertIsFocused()
        control("provider-trigger").performTouchInput { click(center) }
        provider(active.providerId).assertIsSelected()
        assertEditing()
        pressBack()
        option(active).assertIsSelected()
        control("search-field").assertDoesNotExist()
        control("search").assertIsDisplayed()
        assertEditing()
        pressBack()
        control("list").assertDoesNotExist()
        control("card").assertIsDisplayed()
        assertEditing()
        pressBack()
        control("card").assertDoesNotExist()
        assertEquals(closedLabel, control("trigger").fetchSemanticsNode().config[SemanticsProperties.Text])
        assertEquals(triggerSize, control("trigger").fetchSemanticsNode().size)
        assertEditing()

        control("trigger").performTouchInput { click(center) }
        val card = control("card").fetchSemanticsNode().boundsInRoot
        val trigger = control("trigger").fetchSemanticsNode().boundsInRoot
        val root = screen.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("The card fits horizontally", card.left >= root.left && card.right <= root.right)
        assertTrue("The active chip remains outside the anchored card",
            card.bottom <= trigger.top || card.top >= trigger.bottom)
        // A real tap at the chip's position hits the outside layer rather than selecting or refocusing.
        control("trigger").performTouchInput { click(center) }
        control("card").assertDoesNotExist()
        assertEditing()
    }
}
