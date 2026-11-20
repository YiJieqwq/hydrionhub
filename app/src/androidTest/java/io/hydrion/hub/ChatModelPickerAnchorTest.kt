package io.hydrion.hub

import android.view.View
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
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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

    private fun exposedTextPoint(text: Rect, card: Rect, towardsEnd: Boolean): Offset {
        val y = text.center.y
        val pieces = if (y !in card.top..card.bottom) listOf(text.left to text.right) else
            listOf(text.left to minOf(text.right, card.left), maxOf(text.left, card.right) to text.right)
        val (left, right) = pieces.maxBy { (start, end) -> end - start }
        assertTrue("Some of the editor line must remain exposed: $text, $card", right - left > 4f)
        return Offset(if (towardsEnd) right - 2 else left + 2, y) - text.topLeft
    }

    @Test fun reasoningTracksTheModelLabelInFrontOfTheMultilineComposer() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value.draft.text }
        try {
            screen.runOnIdle { vm.setDraft("first line\nsecond line\nthird line") }
            screen.onNodeWithTag("chat-model-trigger").performClick()
            val density = screen.activity.resources.displayMetrics.density
            val viewport = bounds("chat-picker-viewport")
            val source = bounds("chat-model-source-label")
            val card = bounds("chat-model-card")
            val composer = bounds("composer-capsule")
            assertEquals(minOf(288f * density, viewport.width - 24f * density), card.width, 2f)
            assertTrue("The effort card floats over the editable area but clears its label",
                card.bottom > composer.top && card.bottom < source.top && card.contains(Offset(source.center.x, card.center.y)))
            assertEquals(6f * density, source.top - card.bottom, 2f)
            val editor = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
            val overlap = card.intersect(editor.fetchSemanticsNode().boundsInRoot)
            assertTrue("This fixture places the effort card over the editing field", !overlap.isEmpty)
        } finally {
            screen.runOnIdle { vm.setDraft(previous) }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun parkedDraftReceivesEditingTapsWhileTheReasoningCardFollowsTheKeyboard() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value.draft.text }
        val editor = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
        val card = screen.onNodeWithTag("chat-model-card")
        val composer = screen.onNodeWithTag("composer-capsule", useUnmergedTree = true)
        val viewport = screen.onNodeWithTag("chat-picker-viewport", useUnmergedTree = true)
        val density = screen.activity.resources.displayMetrics.density
        fun keyboardVisible() = screen.runOnUiThread {
            val view = screen.activity.window.decorView
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        try {
            screen.runOnIdle { vm.setDraft("4111") }
            screen.onNodeWithTag("chat-model-trigger").performTouchInput { click(center) }
            editor.assertIsNotFocused()
            assertTrue("The draft must start parked", !keyboardVisible())
            val parkedBottom = card.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom
            val textLine = screen.onNodeWithTag("composer-editable-text", useUnmergedTree = true)
            val rtl = screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            fun caretPoint(towardsEnd: Boolean) = exposedTextPoint(textLine.fetchSemanticsNode().boundsInRoot,
                card.fetchSemanticsNode().boundsInRoot, towardsEnd)
            var tap = caretPoint(!rtl)
            textLine.performTouchInput { click(tap) }
            editor.assertIsFocused()
            card.assertIsDisplayed()
            screen.waitUntil(15_000) {
                keyboardVisible() && card.fetchSemanticsNode().boundsInRoot.bottom < parkedBottom
            }
            val raised = card.fetchSemanticsNode().boundsInRoot
            val label = bounds("chat-model-source-label")
            assertTrue(raised.bottom < label.top && raised.bottom > composer.fetchSemanticsNode().boundsInRoot.top)
            assertTrue(label.center.x in raised.left..raised.right)

            val firstCaret = editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
            tap = caretPoint(rtl)
            textLine.performTouchInput { click(tap) }
            val secondCaret = editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
            assertTrue("Each tap should position the cursor",
                firstCaret.collapsed && secondCaret.collapsed && firstCaret != secondCaret)
            editor.performTextInput("a")
            val expected = "4111".replaceRange(secondCaret.start, secondCaret.end, "a")
            screen.runOnIdle { assertEquals(expected, vm.state.value.draft.text) }
            card.assertIsDisplayed()
            assertTrue("Editing should retain the keyboard", keyboardVisible())

            val beforeDoubleTap = TextRange(1, 3)
            editor.performTextInputSelection(beforeDoubleTap)
            tap = caretPoint(rtl)
            textLine.performTouchInput {
                advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
                doubleClick(tap)
            }
            card.assertDoesNotExist()
            editor.assertIsFocused()
            assertEquals(beforeDoubleTap, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            assertTrue(keyboardVisible())
            screen.runOnIdle { assertEquals(expected, vm.state.value.draft.text) }

            screen.onNodeWithTag("chat-model-trigger").performTouchInput { click(center) }
            card.assertIsDisplayed()
            val blank = viewport.fetchSemanticsNode().boundsInRoot.topLeft + Offset(2f * density, 2f * density)
            assertTrue(!card.fetchSemanticsNode().boundsInRoot.contains(blank))
            assertTrue(!composer.fetchSemanticsNode().boundsInRoot.contains(blank))
            viewport.performTouchInput { click(Offset(2.dp.toPx(), 2.dp.toPx())) }
            card.assertDoesNotExist()

            screen.runOnUiThread {
                val window = screen.activity.window
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
            screen.waitUntil(15_000) { !keyboardVisible() }
            screen.onNodeWithTag("chat-model-trigger").performTouchInput { click(center) }
            card.assertIsDisplayed()
            assertTrue("Start the double tap with the keyboard hidden", !keyboardVisible())
            tap = caretPoint(rtl)
            textLine.performTouchInput { doubleClick(tap) }
            card.assertDoesNotExist()
            screen.runOnIdle { assertEquals(expected, vm.state.value.draft.text) }
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
            val card = screen.onNodeWithTag("chat-model-card").fetchSemanticsNode().boundsInRoot
            editor.assertIsNotFocused()
            val field = editor.fetchSemanticsNode().boundsInRoot
            val covered = card.intersect(field)
            assertTrue("The resting effort card is in front of the multiline editor", !covered.isEmpty)
            assertTrue(card.bottom < bounds("chat-model-source-label").top)
            val root = screen.onRoot()
            val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
            screen.mainClock.autoAdvance = false
            trigger.performTouchInput { click(center) }
            screen.mainClock.advanceTimeByFrame()
            screen.mainClock.advanceTimeByFrame()
            root.performTouchInput { click(covered.center - origin) }
            screen.mainClock.advanceTimeByFrame()
            editor.assertIsFocused()
        } finally {
            screen.mainClock.autoAdvance = true
            screen.runOnIdle { vm.setDraft(previous) }
        }
    }
}
