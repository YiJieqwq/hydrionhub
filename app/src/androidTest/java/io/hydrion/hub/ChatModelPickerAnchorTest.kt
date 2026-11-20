package io.hydrion.hub

import android.view.View
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

    @Test fun reasoningCardFollowsTheTriggerAndDrillDownListsStayAboveTheMultilineComposer() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value.draft.text }
        try {
            screen.runOnIdle { vm.setDraft("first line\nsecond line\nthird line") }
            screen.onNodeWithTag("chat-model-trigger").performClick()
            val composer = screen.onNodeWithTag("composer-capsule", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val density = screen.activity.resources.displayMetrics.density
            val rtl = screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            fun checkPage(tag: String): Float {
                val page = screen.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertEquals(if (rtl) composer.left else composer.right, if (rtl) page.left else page.right, 2f)
                assertEquals(11.5f * density, composer.top - page.bottom, 2f)
                return page.width
            }
            val trigger = screen.onNodeWithTag("chat-model-trigger").fetchSemanticsNode().boundsInRoot
            val viewport = screen.onNodeWithTag("chat-picker-viewport", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val card = screen.onNodeWithTag("chat-model-card").fetchSemanticsNode().boundsInRoot
            val margin = 12f * density
            assertEquals(minOf(272f * density, viewport.width - 2 * margin), card.width, 2f)
            val left = (trigger.center.x - card.width / 2).coerceIn(viewport.left + margin,
                maxOf(viewport.left + margin, viewport.right - margin - card.width))
            assertEquals(left, card.left, 2f)
            assertEquals(8f * density, trigger.top - card.bottom, 2f)
            screen.onNodeWithTag("chat-model-select").performClick()
            val width = checkPage("chat-model-list")
            screen.onNodeWithTag("chat-model-provider-trigger").performClick()
            assertEquals(width, checkPage("chat-provider-list"), 1f)
        } finally {
            screen.runOnIdle { vm.setDraft(previous) }
        }
    }

    @Test fun fadingCardLetsTapsThroughToThePreviouslyCoveredEditor() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value.draft.text }
        val editor = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
        try {
            screen.runOnIdle { vm.setDraft("first\nsecond\nthird") }
            val trigger = screen.onNodeWithTag("chat-model-trigger")
            trigger.performClick()
            val card = screen.onNodeWithTag("chat-model-card").fetchSemanticsNode().boundsInRoot
            editor.assertIsNotFocused()
            val overlap = card.intersect(editor.fetchSemanticsNode().boundsInRoot)
            assertTrue("The card covers part of the multiline editor", !overlap.isEmpty)
            val root = screen.onRoot()
            val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
            screen.mainClock.autoAdvance = false
            trigger.performTouchInput { click(center) }
            screen.mainClock.advanceTimeByFrame()
            screen.mainClock.advanceTimeByFrame()
            root.performTouchInput { click(overlap.center - origin) }
            screen.mainClock.advanceTimeByFrame()
            editor.assertIsFocused()
        } finally {
            screen.mainClock.autoAdvance = true
            screen.runOnIdle { vm.setDraft(previous) }
        }
    }
}
