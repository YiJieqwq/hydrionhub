package io.hydrion.hub

import android.animation.ValueAnimator
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.provider.deepSeekProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PickerBubbleMotionTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private val clock get() = screen.mainClock
    private fun node(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true)
    private fun bounds(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot
    private fun shell() = bounds("chat-picker-bubble")

    private fun withParkedComposer(test: () -> Unit) {
        assumeTrue("The intermediate-frame checks need animations enabled", ValueAnimator.areAnimatorsEnabled())
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val store = vm.providers
        val before = store.catalog.value
        assumeTrue("This fixture exercises the chat composer", vm.state.value.current.mode == Mode.Chat)
        val oldDraft = vm.state.value.draft.text
        val model = deepSeekProvider.models.first().copy(id = "one", name = "Fixture")
        val provider = deepSeekProvider.copy(id = "bubble-test-${UUID.randomUUID()}",
            models = listOf(model) + List(14) { model.copy(id = "other-$it", name = "Other $it") })
        try {
            screen.runOnIdle {
                store.save(provider, "", null)
                store.configure(Mode.Chat, RemoteModel(provider.id, model.id), emptyMap())
                vm.setDraft("A parked draft")
            }
            screen.waitForIdle()
            test()
        } finally {
            clock.autoAdvance = true
            screen.runOnIdle {
                store.remove(provider.id)
                before.active[Mode.Chat]?.let { store.configure(Mode.Chat, it, before.overrides[it].orEmpty()) }
                vm.setDraft(oldDraft)
            }
        }
    }

    // Infer the actual progress from the longest-travelling edge. All other edges must
    // share it, independent of easing and screen density. An effort detour fails this check.
    private fun progressAlong(start: Rect, frame: Rect, end: Rect): Float {
        val from = listOf(start.left, start.top, start.right, start.bottom)
        val to = listOf(end.left, end.top, end.right, end.bottom)
        val at = listOf(frame.left, frame.top, frame.right, frame.bottom)
        val longest = from.indices.maxBy { abs(to[it] - from[it]) }
        assertTrue(abs(to[longest] - from[longest]) > 4f)
        val fraction = (at[longest] - from[longest]) / (to[longest] - from[longest])
        assertTrue("Expected an intermediate shell: $start → $frame → $end", fraction > 0f && fraction < 1f)
        for (i in from.indices) assertEquals(from[i] + fraction * (to[i] - from[i]), at[i], 3f)
        return fraction
    }

    @Test fun reversingTheOpeningRetargetsTheVisibleBubbleWithoutReopeningTheOldControls() = withParkedComposer {
        val source = bounds("chat-model-source-label")
        val trigger = node("chat-model-trigger")
        trigger.performClick()
        val card = shell()
        trigger.performClick()
        node("chat-picker-bubble").assertDoesNotExist()
        clock.autoAdvance = false
        trigger.performClick()
        clock.advanceTimeByFrame()
        val first = shell()
        assertEquals(source.left, first.left, 2f)
        assertEquals(source.top, first.top, 2f)
        assertEquals(source.right, first.right, 2f)
        assertEquals(source.bottom, first.bottom, 2f)
        clock.advanceTimeBy(96)
        val opening = progressAlong(source, shell(), card)
        trigger.performClick()
        clock.advanceTimeBy(64)
        val closing = progressAlong(source, shell(), card)
        assertTrue("Reversed from the drawn opening frame", closing < opening)
        node("chat-model-select").assertDoesNotExist()
        trigger.performClick()
        clock.advanceTimeBy(64)
        val reopened = shell()
        assertTrue(reopened == card || progressAlong(source, reopened, card) > closing)
        clock.autoAdvance = true
        screen.waitForIdle()
        assertEquals(card, shell())
    }

    @Test fun explicitListBackReversesInPlaceAndAppBackShrinksDirectlyToTheSource() = withParkedComposer {
        val source = bounds("chat-model-source-label")
        node("chat-model-trigger").performClick()
        val card = shell()
        clock.autoAdvance = false
        node("chat-model-select").performClick()
        clock.advanceTimeBy(64)
        val growing = shell()
        clock.autoAdvance = true
        screen.waitForIdle()
        val list = shell()
        progressAlong(card, growing, list)
        assertEquals(card.bottom, list.bottom, 2f)
        val rtl = screen.activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        assertEquals(if (rtl) card.left else card.right, if (rtl) list.left else list.right, 2f)
        val oldBack = bounds("chat-model-list-back")

        clock.autoAdvance = false
        node("chat-model-list-back").performClick()
        clock.advanceTimeBy(64)
        progressAlong(list, shell(), card)
        clock.autoAdvance = true
        screen.waitForIdle()
        assertEquals(card, shell())
        node("chat-model-select").performClick()
        screen.waitForIdle()

        clock.autoAdvance = false
        screen.runOnUiThread { screen.activity.onBackPressedDispatcher.onBackPressed() }
        clock.advanceTimeBy(64)
        val exiting = progressAlong(list, shell(), source)
        node("chat-model-card").assertDoesNotExist()
        node("chat-model-list-back").assertDoesNotExist()
        // A real tap at the former list arrow cannot route this direct exit via the effort card.
        val root = screen.onRoot()
        val rootOrigin = root.fetchSemanticsNode().boundsInRoot.topLeft
        root.performTouchInput { click(oldBack.center - rootOrigin) }
        clock.advanceTimeBy(32)
        assertTrue(progressAlong(list, shell(), source) > exiting)
        clock.autoAdvance = true
        screen.waitForIdle()
        node("chat-picker-bubble").assertDoesNotExist()
        node("chat-model-card").assertDoesNotExist()
        node("chat-model-list").assertDoesNotExist()
    }
}
