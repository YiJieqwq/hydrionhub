package io.hydrion.hub

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.provider.route
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

    @Test fun modelChangesApplyImmediatelyAndOnlyExposeTheirOwnCapabilities() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val store = vm.providers
        val before = store.catalog.value
        val previous = requireNotNull(before.selectedEntry(Mode.Chat))
        val default = requireNotNull(before.defaultEntry(Mode.Chat))
        val reason = RemoteModel("demo-chat", "demo-reason")
        val mini = RemoteModel("demo-chat", "demo-quick")
        val originalDraft = vm.state.value.draft.text
        try {
            screen.runOnIdle { vm.setDraft("model picker") }
            control("trigger").performClick()
            control("select").performClick()
            option(previous.route).assertIsSelected()
            option(reason).performClick()
            control("card").assertIsDisplayed()
            val effort = control("effort").assertIsDisplayed()
            val speed = control("speed").assertIsDisplayed()
            val initial = store.selection(Mode.Chat).parameters
            val range = effort.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            val otherEnd = if (range.current == range.range.start) range.range.endInclusive else range.range.start
            effort.performSemanticsAction(SemanticsActions.SetProgress) { it(otherEnd) }
            speed.performClick()
            screen.runOnIdle {
                val parameters = store.selection(Mode.Chat).parameters
                assertNotEquals(initial["effort"], parameters["effort"])
                assertNotEquals(initial["speed"], parameters["speed"])
            }

            control("select").performClick()
            option(reason).assertIsSelected()
            option(mini).performClick()
            screen.runOnIdle { assertEquals(mini, store.catalog.value.active[Mode.Chat]) }
            control("card").assertIsDisplayed()
            control("effort").assertDoesNotExist()
            control("speed").assertDoesNotExist()
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
                val touched = (listOf(reason, default.route).distinct() - previous.route) + previous.route
                for (route in touched) {
                    store.configure(Mode.Chat, route, before.overrides[route].orEmpty())
                }
                vm.setDraft(originalDraft)
            }
        }
    }

    @Test fun backClosesListThenCardWithoutDisturbingEditing() {
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
        control("trigger").performTouchInput { click(center) }
        control("card").assertIsDisplayed()
        assertEditing()
        control("select").performTouchInput { click(center) }
        control("list").assertIsDisplayed()
        assertEditing()
        option(active).assertIsSelected().performTouchInput { click(center) }
        control("card").assertIsDisplayed()
        assertEditing()
        control("select").performTouchInput { click(center) }
        control("list").assertIsDisplayed()
        pressBack()
        control("list").assertDoesNotExist()
        control("card").assertIsDisplayed()
        assertEditing()
        pressBack()
        control("card").assertDoesNotExist()
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
