package io.hydrion.hub.ui.chrome

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.lifecycle.ViewModelProvider
import io.hydrion.hub.MainActivity
import io.hydrion.hub.model.*
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.deepSeekProvider
import io.hydrion.hub.ui.messages.MessageStyleStore
import io.hydrion.hub.ui.messages.MessageTone
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import kotlin.math.roundToInt

// Seed only Robolectric's private app directory before the normal Activity restores its workspace.
class ContextWindowFixture : Application() {
    override fun onCreate() {
        super.onCreate()
        val base = deepSeekProvider.models.first()
        val known = base.copy(profile = base.profile.copy(contextWindowTokens = 1000))
        val opaque = base.copy(id = "fixture-opaque", profile = base.profile.copy(externalId = "fixture-opaque"))
        val provider = deepSeekProvider.copy(models = listOf(known, opaque))
        ProviderStore(this).apply {
            saveDefaults(provider, "", mapOf(Mode.Chat to known.id))
            setPerChatModel(true)
        }
        fun chat(id: String, model: String, usage: TokenUsage?): Conversation {
            val route = RemoteModel(provider.id, model)
            val q = Message(id = id + "-q", role = Role.User, text = "A fixture question")
            val a = Message(id = id + "-a", role = Role.Assistant, replyTo = q.id, text = "A fixture reply", remoteModel = route, usage = usage)
            return Conversation(id = id, title = id, messages = listOf(q, a), modelRoutes = mapOf(Mode.Chat to route))
        }
        val sample = chat("Reading", known.id, TokenUsage(200, 50, 250, 90, 20))
        val over = chat("Over", known.id, TokenUsage(totalTokens = 1250))
        val unknown = chat("Unknown", opaque.id, TokenUsage(inputTokens = 27))
        val owner = LocalAssistant.Default.copy(chatModel = sample.modelRoutes?.get(Mode.Chat))
        HistoryFile(this).save(SavedWorkspace(history = listOf(sample, over, unknown),
            drafts = mapOf(sample.id to Draft(text = "Existing draft")), assistants = listOf(owner), lastThreads = mapOf(owner.id to sample.id)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ContextWindowFixture::class, qualifiers = "en-rUS-w407dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContextWindowInteractionTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private fun tag(name: String) = screen.onNodeWithTag(name, useUnmergedTree = true)
    private fun part(type: ContextPart) = screen.onNodeWithTag("context-part-" + type.name)
    private fun chip() = tag("conversation-chip")
    private fun awaitText(text: String) {
        try {
            screen.waitUntil(10000) {
                shadowOf(Looper.getMainLooper()).idle()
                screen.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            val state = screen.runOnIdle { model().state.value }
            throw AssertionError("Missing '$text', current=" + state.current.title + ", saved=" + state.history.map { it.title }, timeout)
        }
    }
    private fun tapChip() = chip().performTouchInput { click() }
    private fun back() = screen.runOnIdle { screen.activity.onBackPressedDispatcher.onBackPressed() }
    private fun model() = ViewModelProvider(screen.activity)[HubViewModel::class.java]
    private fun systemScale(scale: Float) = ReflectionHelpers.callStaticMethod<Any?>(ValueAnimator::class.java, "setDurationScale",
        ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, scale))
    private fun openFixture(id: String) {
        val vm = screen.runOnIdle { model() }
        screen.waitUntil(10000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.workspaceLoad.value == WorkspaceLoad.Ready
        }
        screen.runOnIdle { vm.openConversation(id) }
        awaitText(id)
    }

    @Test fun opensFromTheChipAboveAFocusedDraftAndNavigatesToTheRealAssistantLimit() {
        openFixture("Reading")
        val field = screen.onNodeWithContentDescription("消息输入框")
        field.performClick().assertIsFocused().assertTextContains("Existing draft")
        val anchor = chip().fetchSemanticsNode().boundsInRoot
        tapChip()
        screen.onNodeWithText("250 / 1,000 (25%)").assertIsDisplayed()
        part(ContextPart.Unattributed).assertTextContains("250").assertTextContains("25%")
        part(ContextPart.Messages).assertTextContains("Not measured")
        part(ContextPart.Free).assertTextContains("750").assertTextContains("75%")
        val panel = tag("context-window").fetchSemanticsNode().boundsInRoot
        assertTrue("anchor=$anchor panel=$panel", panel.top >= anchor.bottom && panel.left <= anchor.center.x && panel.right >= anchor.center.x)
        assertEquals(anchor, chip().fetchSemanticsNode().boundsInRoot)
        val bar = tag("context-segments").fetchSemanticsNode().boundsInRoot
        val bitmap = screen.runOnIdle { screen.activity.window.decorView.drawToBitmap() }
        fun ink(x: Float) = bitmap.getPixel((bar.left + bar.width * x).roundToInt(), bar.center.y.roundToInt())
        assertEquals(0xFFA79AD1.toInt(), ink(.20f))
        assertEquals(0xFF383838.toInt(), ink(.30f))
        field.assertIsFocused().performTextReplacement("Existing draft with edits")
        screen.onNodeWithText("250 / 1,000 (25%)").assertIsDisplayed()
        back()
        tag("context-window").assertDoesNotExist()
        field.assertIsFocused().assertTextContains("Existing draft with edits")
        tapChip()
        screen.onNodeWithText("Context Management").performScrollTo().performTouchInput { click() }
        screen.onNodeWithContentDescription("Limit chat context").assertIsDisplayed().assertIsOff().performClick()
        screen.onNode(hasSetTextAction() and hasText("64")).performTextReplacement("3")
        screen.onNodeWithText("Save").performClick()
        screen.runOnIdle {
            assertEquals(3, model().state.value.assistants.single().contextLimit)
            assertEquals("Existing draft with edits", model().state.value.draft.text)
        }
        tapChip()
        // An adjacent capsule takes the original outside press and retains access to rename.
        screen.onNodeWithContentDescription("更多会话操作").performTouchInput { click() }
        awaitText("Rename conversation")
        tag("context-window").assertDoesNotExist()
    }

    @Test @Config(sdk = [35], application = ContextWindowFixture::class, qualifiers = "zh-rCN-w360dp-h720dp")
    fun unknownLimitAndOverageRemainDistinctWithReducedMotionAndRealHeaderNavigation() {
        val previousScale = ValueAnimator.getDurationScale()
        val style = MessageStyleStore.shared(screen.activity)
        val original = style.style.value
        try {
            systemScale(0f)
            openFixture("Over")
            screen.runOnIdle { style.update { it.copy(tone = MessageTone.Light) } }
            screen.mainClock.autoAdvance = false
            tapChip()
            screen.mainClock.advanceTimeBy(32)
            screen.onNodeWithText("1,250 / 1,000（125%）").assertIsDisplayed()
            part(ContextPart.Free).assertTextContains("0").assertTextContains("0%")
            val anchor = chip().fetchSemanticsNode().boundsInRoot
            assertTrue(tag("context-window").fetchSemanticsNode().boundsInRoot.top >= anchor.bottom)
            val bar = tag("context-segments").fetchSemanticsNode().boundsInRoot
            val pixel = screen.runOnIdle { screen.activity.window.decorView.drawToBitmap().getPixel(bar.center.x.roundToInt(), bar.center.y.roundToInt()) }
            assertEquals(0xFF736498.toInt(), pixel)
            tapChip()
            screen.mainClock.advanceTimeBy(32)
            tag("context-window").assertDoesNotExist()
            screen.mainClock.autoAdvance = true
            openFixture("Unknown")
            tapChip()
            screen.onNodeWithText("— / 上限未知").assertIsDisplayed()
            screen.onNodeWithText("用量未上报").assertIsDisplayed()
            part(ContextPart.Free).assertTextContains("未统计")
            part(ContextPart.Unattributed).assertDoesNotExist()
            back()
            tag("context-window").assertDoesNotExist()
        } finally {
            screen.mainClock.autoAdvance = true
            systemScale(previousScale)
            screen.runOnIdle { style.update { original } }
        }
    }

    @Test fun panelTravelsDownFromThePillAndReturnsThroughAnIntermediateFrame() {
        openFixture("Reading")
        val previousScale = ValueAnimator.getDurationScale()
        val clock = screen.mainClock
        fun frame() = screen.runOnIdle { screen.activity.window.decorView.drawToBitmap() }
        fun startTransition() {
            // The header lives in a subcomposed Scaffold. Deliver its Android layout pass
            // before advancing the Compose clock to the frame we want to measure.
            clock.advanceTimeByFrame()
            screen.waitForIdle()
        }
        try {
            systemScale(1f)
            val anchor = chip().fetchSemanticsNode().boundsInRoot
            val center = anchor.center.x.roundToInt()
            val band = (center - 4)..(center + 4)
            val before = frame()
            clock.autoAdvance = false
            tapChip()
            startTransition()
            clock.advanceTimeBy(128)
            val opening = frame()
            clock.advanceTimeBy(450)
            val expanded = tag("context-window").fetchSemanticsNode().boundsInRoot.top.roundToInt()
            val surface = frame().getPixel(center, expanded + 2)
            // Closing immediately removes the accessibility pane. Measure its painted shell
            // across the center of the title pill, where the rounded corners cannot interfere.
            fun shellTop(bitmap: Bitmap): Int? = (anchor.top.roundToInt()..expanded + 4).firstOrNull { y ->
                band.count { x -> bitmap.getPixel(x, y) == surface } >= 7
            }
            assertNull("unopened surface", shellTop(before))
            assertTrue("expanded=$expanded anchor=$anchor", expanded >= anchor.bottom)
            val openingTop = shellTop(opening)
            assertTrue("opening=$openingTop expanded=$expanded", openingTop != null && openingTop > anchor.top && openingTop < expanded)
            back()
            startTransition()
            clock.advanceTimeBy(144)
            val closing = shellTop(frame())
            assertTrue("anchor=$anchor closing=$closing expanded=$expanded", closing != null && closing > anchor.top && closing < expanded)
            clock.advanceTimeBy(450)
            tag("context-window").assertDoesNotExist()
            assertNull("returned to the title pill", shellTop(frame()))
        } finally {
            clock.autoAdvance = true
            systemScale(previousScale)
        }
    }
}
