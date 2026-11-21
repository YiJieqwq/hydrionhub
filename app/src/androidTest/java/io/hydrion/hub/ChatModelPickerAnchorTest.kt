package io.hydrion.hub

import android.view.inputmethod.EditorInfo
import android.view.ViewConfiguration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatModelPickerAnchorTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun bounds(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private val editor get() = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
    private val card get() = screen.onNodeWithTag("chat-model-card")
    private val trigger get() = screen.onNodeWithTag("chat-model-trigger")
    private val line get() = screen.onNodeWithTag("composer-editable-text", useUnmergedTree = true)

    private fun keyboardVisible() = screen.runOnUiThread {
        ViewCompat.getRootWindowInsets(screen.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun assertAboveComposer() {
        val composer = bounds("composer-capsule")
        val panel = card.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = screen.activity.resources.displayMetrics.density
        assertEquals("The whole card clears the composer", 11.5f * density, composer.top - panel.bottom, 2f)
        assertTrue("Every pixel of the editor remains exposed", !panel.overlaps(editor.fetchSemanticsNode().boundsInRoot))
    }

    @Test fun effortCardClearsParkedAndKeyboardOpenMultilineDrafts() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value.draft.text }
        try {
            screen.runOnIdle { vm.setDraft("first line\nsecond line\nthird line") }
            trigger.performClick()
            assertAboveComposer()
            val density = screen.activity.resources.displayMetrics.density
            val viewport = bounds("chat-picker-viewport")
            assertEquals(minOf(288f * density, viewport.width - 24f * density), bounds("chat-model-card").width, 2f)

            line.performTouchInput { click(center) }
            screen.waitUntil(15_000) { keyboardVisible() }
            card.assertDoesNotExist()
            trigger.performClick()
            assertAboveComposer()
        } finally {
            screen.runOnIdle { vm.setDraft(previous) }
        }
    }

    @Test fun tappingTheDraftDismissesEffortWithoutTakingEitherTapFromTheEditor() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value.draft.text }
        try {
            screen.runOnIdle { vm.setDraft("4111") }
            assertTrue("Start with a parked draft", !keyboardVisible())
            for (atEnd in listOf(true, false)) {
                trigger.performTouchInput { click(center) }
                assertAboveComposer()
                val layouts = mutableListOf<TextLayoutResult>()
                editor.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val x = layouts.single().getCursorRect(if (atEnd) 4 else 0).center.x
                line.performTouchInput { click(Offset(x.coerceIn(1f, width - 1f), centerY)) }
                card.assertDoesNotExist()
                screen.waitUntil(15_000) { keyboardVisible() }
                editor.assertIsFocused()
                val caret = editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
                assertEquals("The same tap positions the caret", TextRange(if (atEnd) 4 else 0), caret)
            }
            trigger.performTouchInput { click(center) }
            card.assertIsDisplayed()
            editor.assertIsFocused()
            assertTrue(keyboardVisible())
            editor.performTextInput("你")
            card.assertDoesNotExist()
            screen.runOnIdle { assertEquals("你4111", vm.state.value.draft.text) }
            assertTrue(keyboardVisible())

            // The real editing surface is still tappable when only its placeholder is visible.
            editor.performTextReplacement("")
            trigger.performClick()
            card.assertIsDisplayed()
            line.performTouchInput { click(center) }
            card.assertDoesNotExist()
            editor.assertIsFocused()
            assertTrue(keyboardVisible())
            screen.runOnIdle { assertEquals("", vm.state.value.draft.text) }

            // A screen-reader click activates editing too, without requiring a new focus event.
            trigger.performClick()
            card.assertIsDisplayed()
            editor.performSemanticsAction(SemanticsActions.OnClick)
            card.assertDoesNotExist()
            editor.assertIsFocused()
            assertTrue(keyboardVisible())

            trigger.performClick()
            assertAboveComposer()
            screen.onNodeWithTag("chat-picker-viewport", useUnmergedTree = true)
                .performTouchInput { click(Offset(2.dp.toPx(), 2.dp.toPx())) }
            card.assertDoesNotExist()
        } finally {
            screen.runOnIdle { vm.setDraft(previous) }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun focusAndSelectionAloneKeepEffortOpenButEveryDraftInputPathCanDismissIt() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value.draft.text }
        try {
            screen.runOnIdle { vm.setDraft("ab") }
            trigger.performClick()
            editor.performSemanticsAction(SemanticsActions.RequestFocus)
            editor.assertIsFocused()
            card.assertIsDisplayed()
            editor.performTextInputSelection(TextRange(1, 2))
            card.assertIsDisplayed()

            // Drive the editor's IME connection directly so that the composing span, then
            // its replacement, cross the card's dismissal without committing twice.
            val connection = screen.runOnIdle {
                requireNotNull(screen.activity.currentFocus?.onCreateInputConnection(EditorInfo()))
            }
            screen.runOnUiThread { assertTrue(connection.setComposingText("水", 1)) }
            screen.waitUntil(5_000) { vm.state.value.draft.text == "a水" }
            card.assertDoesNotExist()
            screen.runOnUiThread {
                assertTrue(connection.setComposingText("水面", 1))
                assertTrue(connection.finishComposingText())
            }
            screen.waitUntil(5_000) { vm.state.value.draft.text == "a水面" }
            editor.assertIsFocused()

            trigger.performClick()
            card.assertIsDisplayed()
            editor.performKeyInput { pressKey(Key.Backspace) }
            card.assertDoesNotExist()
            screen.runOnIdle { assertEquals("a水", vm.state.value.draft.text) }

            trigger.performClick()
            card.assertIsDisplayed()
            editor.performTextReplacement("updated")
            card.assertDoesNotExist()
            editor.assertIsFocused()
            screen.runOnIdle { assertEquals("updated", vm.state.value.draft.text) }
        } finally {
            screen.runOnIdle { vm.setDraft(previous) }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun withoutAPickerTheEditorUsesThePlatformTextSelectionGestures() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value.draft.text }
        val text = "alpha bravo omega"
        val native = "reference-text-field"
        fun selections(field: SemanticsNodeInteraction, line: SemanticsNodeInteraction): Pair<TextRange, TextRange> {
            field.performClick()
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val glyph = layouts.single().getBoundingBox(text.indexOf("bravo") + 2)
            val x = glyph.left + glyph.width * .25f
            fun selection() = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
            field.performTextInputSelection(TextRange.Zero)
            line.performTouchInput {
                advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
                doubleClick(Offset(x, centerY))
            }
            val doubleTapped = selection()
            field.performTextInputSelection(TextRange.Zero)
            line.performTouchInput {
                advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
                longClick(Offset(x, centerY))
            }
            return doubleTapped to selection()
        }
        try {
            screen.runOnIdle { vm.setDraft(text) }
            screen.onNodeWithTag("chat-model-card").assertDoesNotExist()
            val actual = selections(screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction()),
                screen.onNodeWithTag("composer-editable-text", useUnmergedTree = true))
            // Compare the same physical gestures with an undecorated editor on this Compose/Android version.
            screen.runOnUiThread {
                screen.activity.setContent {
                    var value by remember { mutableStateOf(TextFieldValue(text)) }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BasicTextField(value, { value = it }, Modifier.fillMaxWidth().testTag(native))
                    }
                }
            }
            val reference = screen.onNodeWithTag(native)
            val expected = selections(reference, reference)
            assertEquals("Unmodified double tap selection", expected.first, actual.first)
            assertEquals(TextRange(6, 11), expected.second)
            assertEquals("Native touch word selection remains reachable", expected.second, actual.second)
        } finally {
            screen.runOnIdle { vm.setDraft(previous) }
        }
    }

    @Test fun fadingCardLetsTheNextTapFocusTheEditor() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value.draft.text }
        val editor = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
        try {
            screen.runOnIdle { vm.setDraft("first\nsecond\nthird") }
            val trigger = screen.onNodeWithTag("chat-model-trigger")
            trigger.performClick()
            assertAboveComposer()
            editor.assertIsNotFocused()
            val point = line.fetchSemanticsNode().boundsInRoot.center
            val root = screen.onRoot()
            val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
            screen.mainClock.autoAdvance = false
            trigger.performTouchInput { click(center) }
            screen.mainClock.advanceTimeByFrame()
            screen.mainClock.advanceTimeByFrame()
            root.performTouchInput { click(point - origin) }
            screen.mainClock.advanceTimeByFrame()
            editor.assertIsFocused()
        } finally {
            screen.mainClock.autoAdvance = true
            screen.runOnIdle { vm.setDraft(previous) }
        }
    }
}
