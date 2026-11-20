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

    @Test fun reasoningAndListsShareTheMultilineComposerTopAndTrailingEdge() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value.draft.text }
        try {
            screen.runOnIdle { vm.setDraft("first line\nsecond line\nthird line") }
            screen.onNodeWithTag("chat-model-trigger").performClick()
            val density = screen.activity.resources.displayMetrics.density
            val rtl = screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            fun checkPage(tag: String): Float {
                val composerNode = screen.onNodeWithTag("composer-capsule", useUnmergedTree = true).fetchSemanticsNode()
                val pageNode = screen.onNodeWithTag(tag).fetchSemanticsNode()
                // Search changes the editor contents and may open the IME. Measure both live nodes in one frame.
                return screen.runOnIdle {
                    val composer = composerNode.boundsInRoot
                    val page = pageNode.boundsInRoot
                    assertEquals(if (rtl) composer.left else composer.right, if (rtl) page.left else page.right, 2f)
                    assertEquals(11.5f * density, composer.top - page.bottom, 2f)
                    page.width
                }
            }
            val viewport = screen.onNodeWithTag("chat-picker-viewport", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(minOf(288f * density, viewport.width - 24f * density), checkPage("chat-model-card"), 2f)
            screen.onNodeWithTag("chat-model-select").performClick()
            screen.onNodeWithTag("chat-model-search-field").assertIsFocused()
            val width = checkPage("chat-model-list")
            screen.onNodeWithTag("chat-model-search-close").performClick()
            screen.onNodeWithTag("chat-model-search-field").assertDoesNotExist()
            assertEquals(width, checkPage("chat-model-list"), 1f)
            screen.onNodeWithTag("chat-model-provider-trigger").performClick()
            assertEquals(width, checkPage("chat-provider-list"), 1f)
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
            assertTrue("The card must clear the multiline editor", card.bottom <= field.top)
            val root = screen.onRoot()
            val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
            screen.mainClock.autoAdvance = false
            trigger.performTouchInput { click(center) }
            screen.mainClock.advanceTimeByFrame()
            screen.mainClock.advanceTimeByFrame()
            root.performTouchInput { click(field.center - origin) }
            screen.mainClock.advanceTimeByFrame()
            editor.assertIsFocused()
        } finally {
            screen.mainClock.autoAdvance = true
            screen.runOnIdle { vm.setDraft(previous) }
        }
    }
}
