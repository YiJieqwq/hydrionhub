package io.hydrion.hub

import android.animation.ValueAnimator
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toRect
import io.hydrion.hub.provider.ChatModelPickerState
import io.hydrion.hub.provider.PickerBubbleFace
import io.hydrion.hub.provider.PickerBubbleLayout
import io.hydrion.hub.ui.LocalPickerComposerBounds
import io.hydrion.hub.ui.LocalPickerModelLabelBounds
import io.hydrion.hub.ui.LocalPickerModelTravelX
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.provider.PickerBubbleMotionMillis
import io.hydrion.hub.provider.deepSeekProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

@RunWith(AndroidJUnit4::class)
class PickerBubbleMotionTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private val clock get() = screen.mainClock
    private fun node(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true)
    private fun bounds(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot
    private fun shell() = bounds("chat-picker-bubble")

    // Retargeted tweens cover only the distance still visible on screen.
    private fun advanceIntoLeg(remainingDistance: Float = 1f) {
        clock.advanceTimeBy((PickerBubbleMotionMillis * remainingDistance * 0.4f).roundToLong())
    }

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
        advanceIntoLeg()
        val opening = progressAlong(source, shell(), card)
        trigger.performClick()
        advanceIntoLeg(remainingDistance = opening)
        val closing = progressAlong(source, shell(), card)
        assertTrue("Reversed from the drawn opening frame", closing < opening)
        node("chat-model-select").assertDoesNotExist()
        trigger.performClick()
        advanceIntoLeg(remainingDistance = 1f - closing)
        assertTrue(progressAlong(source, shell(), card) > closing)
        clock.autoAdvance = true
        screen.waitForIdle()
        assertEquals(card, shell())
    }

    @Test fun effortHandoffUsesTheSamePlacedToolbarTravelAtQuarterFramesAndAfterInterrupting() {
        val browse = mutableStateOf(false)
        val progress = mutableFloatStateOf(0f)
        lateinit var neutralLabel: IntRect
        screen.runOnUiThread {
            screen.activity.setContent {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val capsule = IntRect(w / 16, h * 2 / 3, w * 15 / 16, h * 15 / 16)
                    neutralLabel = IntRect(w / 2, h * 5 / 6, w * 3 / 4, h * 7 / 8)
                    val p = progress.floatValue
                    val travel = (-80 * p).toInt()
                    val ime = (20 * p).toInt()
                    val picker = remember { ChatModelPickerState() }
                    CompositionLocalProvider(
                        LocalPickerComposerBounds provides capsule.translate(IntOffset(0, -ime)),
                        LocalPickerModelLabelBounds provides neutralLabel.translate(IntOffset(travel, -ime)),
                        LocalPickerModelTravelX provides travel,
                    ) {
                        PickerBubbleLayout(picker, if (browse.value) PickerBubbleFace.Label else PickerBubbleFace.Effort,
                            origin = null, hideForReturn = false, modelBrowseProgress = { progress.floatValue },
                            browsingModels = browse.value, listColor = Color.Gray, effortColor = Color.DarkGray,
                            effortBorder = Color.White, modifier = Modifier.fillMaxSize(), paneTitle = "Effort") {
                            Box(Modifier.height(96.dp))
                        }
                    }
                }
            }
        }
        screen.waitForIdle()
        val card = shell()
        val label = neutralLabel.toRect()
        fun sample(p: Float) {
            screen.runOnUiThread { progress.floatValue = p }
            screen.waitForIdle()
            if (p == 1f) {
                node("chat-picker-bubble").assertDoesNotExist()
                return
            }
            val actual = shell()
            val dx = (-80 * p).toInt()
            val dy = -(20 * p).toInt()
            assertEquals(card.left + (label.left - card.left) * p + dx, actual.left, 2f)
            assertEquals(card.right + (label.right - card.right) * p + dx, actual.right, 2f)
            assertEquals(card.top + (label.top - card.top) * p + dy, actual.top, 2f)
            assertEquals(card.bottom + (label.bottom - card.bottom) * p + dy, actual.bottom, 2f)
        }
        screen.runOnUiThread { browse.value = true }
        for (p in listOf(0f, .25f, .5f, .75f)) sample(p)
        screen.runOnUiThread { browse.value = false }
        for (p in listOf(.75f, .5f)) sample(p)
        screen.runOnUiThread { browse.value = true }
        for (p in listOf(.5f, .75f, 1f)) sample(p)
        screen.runOnUiThread { browse.value = false }
        for (p in listOf(.75f, .5f, .25f, 0f)) sample(p)
    }

    @Test fun openingTheFullModelsPageDismissesTheEffortShellAndAppBackLeavesNoHiddenPicker() = withParkedComposer {
        node("chat-model-trigger").performClick()
        val open = shell()
        clock.autoAdvance = false
        node("chat-model-select").performClick()
        advanceIntoLeg()
        val closing = shell()
        assertTrue("Effort leaves from its visible state", closing.width > 0f && closing.width < open.width)
        node("chat-model-card").assertDoesNotExist()
        clock.autoAdvance = true
        screen.waitForIdle()
        node("chat-model-list").assertIsDisplayed()
        node("chat-picker-bubble").assertDoesNotExist()
        val selected = screen.runOnIdle {
            ViewModelProvider(screen.activity)[HubViewModel::class.java].providers.catalog.value.active.getValue(Mode.Chat)
        }
        node("chat-model-option:${selected.providerId}/${selected.modelId}").performClick()
        node("chat-model-card").assertIsDisplayed()
        assertEquals(open.width, shell().width, 2f)
        node("chat-model-select").performClick()
        node("chat-model-list").assertIsDisplayed()
        node("chat-picker-bubble").assertDoesNotExist()
        screen.runOnUiThread { screen.activity.onBackPressedDispatcher.onBackPressed() }
        screen.waitForIdle()
        node("chat-model-card").assertDoesNotExist()
        node("chat-picker-bubble").assertDoesNotExist()
        node("chat-model-list").assertDoesNotExist()
        node("chat-model-trigger").performClick()
        node("chat-model-card").assertIsDisplayed()
    }
}
