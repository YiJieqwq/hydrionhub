package io.hydrion.hub.ui.messages

import android.app.Application
import android.os.Looper
import android.content.Context
import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import java.nio.file.Files
import android.util.Base64
import android.view.inspector.WindowInspector
import android.webkit.WebView
import android.widget.Magnifier
import androidx.core.view.allViews
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyBubbleInteractionTest {
    private val question = Message(id = "q", role = Role.User, text = "Fixture question")
    private val answer = Message(id = "r", role = Role.Assistant, replyTo = question.id, text = "Middle revision")
    private val group = MessageVersions(1, listOf(answer.copy(text = "First revision"), answer.copy(text = "Last revision", translation = ReplyTranslation("English", "Saved translation"))))
    private val original = Conversation(title = "Versions", messages = listOf(question, answer),
        versions = mapOf(answer.id to group), modelRoutes = emptyMap())
    private lateinit var recorded: Conversation
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    // The history reader and Markdown renderer post their results to Android's paused main Looper.
    private fun awaitHost(condition: () -> Boolean) = screen.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun open(width: Dp? = null, fontScale: Float = 1f, initial: Conversation = original): HubViewModel {
        val host = screen.activity
        val directory = Files.createTempDirectory(host.cacheDir.toPath(), "bubble-history-").toFile()
        val app = object : Application() {
            init { attachBaseContext(host.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
        }
        recorded = recordedChat()
        HistoryFile(app).save(SavedWorkspace(listOf(initial, recorded)))
        val vm = screen.runOnIdle {
            ViewModelProvider(host, ViewModelProvider.AndroidViewModelFactory(app))["bubble-fixture", HubViewModel::class.java]
        }
        awaitHost { vm.workspaceLoad.value != WorkspaceLoad.Reading }
        assertEquals(WorkspaceLoad.Ready, vm.workspaceLoad.value)
        screen.activityRule.scenario.onActivity { activity ->
            vm.openConversation(initial.id)
            assertEquals(initial.messages.last().text, vm.state.value.current.messages.last().text)
            activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        Box(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width)) {
                            ProvideMessageStyle { MessageList(vm, vm.state.collectAsState().value, "", null, 0) }
                        }
                    }
                }
            }
        }
        val reply = initial.messages.last()
        screen.onNodeWithTag("message-timeline").performScrollToKey(reply.id)
        screen.onNodeWithTag("message-turn-${reply.id}").assertIsDisplayed()
        return vm
    }

    private fun label(id: Int) = screen.activity.getString(id)
    private fun more() = screen.onNodeWithContentDescription(label(R.string.turn_more_reply)).performClick()
    private fun tap(id: Int) = screen.onNode(hasText(label(id)) and hasClickAction()).performScrollTo().performClick()
    private fun confirm(id: Int) = screen.onNode(hasText(label(id)) and hasClickAction()).performClick()

    private fun usageChat(reply: Message, vararg revisions: Message) = Conversation(title = "Usage fixture",
        messages = listOf(question, reply), versions = if (revisions.isEmpty()) emptyMap()
        else mapOf(reply.id to MessageVersions(0, revisions.toList())))

    private fun assertUsageRows(vararg expected: Pair<Int, String>) {
        screen.onNodeWithTag("turn-usage-panel").assertIsDisplayed()
        val row = hasAnyAncestor(hasTestTag("turn-usage-panel")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        screen.onAllNodes(row).assertCountEquals(expected.size)
        expected.forEach { (labelId, value) ->
            screen.onNode(row and hasText(label(labelId)) and hasText(value)).assertIsDisplayed()
        }
    }

    @Test fun assistantHeaderFooterAndMenuUseTheReferencePhoneGeometry() {
        val vm = open()
        screen.runOnIdle { vm.openConversation(recorded.id) }
        fun action(id: Int) = screen.onNodeWithContentDescription(label(id)).getUnclippedBoundsInRoot()
        val timeline = screen.onNodeWithTag("message-timeline").getUnclippedBoundsInRoot()
        val avatar = screen.onNodeWithTag("turn-assistant-avatar").getUnclippedBoundsInRoot()
        val byline = screen.onNodeWithText(recorded.messages.last().model, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val body = screen.onNodeWithTag("message-assistant-body").getUnclippedBoundsInRoot()
        val copy = action(R.string.turn_copy_reply)
        val retry = action(R.string.turn_retry)
        val token = screen.onNodeWithText("30 tokens").getUnclippedBoundsInRoot()
        assertEquals(32.dp, avatar.right - avatar.left)
        assertEquals(timeline.left + 20.dp, avatar.left)
        assertEquals(avatar.right + 8.dp, byline.left)
        assertEquals(avatar.left, body.left)
        assertEquals(28.dp, copy.right - copy.left)
        assertEquals(34.dp, retry.left - copy.left)
        assertEquals(copy.top, token.top)
        assertEquals(timeline.right - 20.dp, token.right)
        more()
        val first = screen.onNode(hasText(label(R.string.turn_select_copy)) and hasClickAction()).getUnclippedBoundsInRoot()
        val second = screen.onNode(hasText(label(R.string.turn_copy_markdown_source)) and hasClickAction()).getUnclippedBoundsInRoot()
        assertEquals(12.dp, first.left)
        assertEquals(44.dp, first.bottom - first.top)
        assertTrue(second.top - first.bottom <= 1.dp)
    }

    @Test fun narrowLargeTextFooterWrapsAndKeepsRevisionsAndUsageInteractive() {
        val first = answer.copy(timing = ReplyTiming(null, null, 1250))
        val last = answer.copy(text = "Last revision", timing = ReplyTiming(0, null, null))
        open(width = 224.dp, fontScale = 1.6f, initial = usageChat(first, last))
        val copy = screen.onNodeWithContentDescription(label(R.string.turn_copy_reply)).getUnclippedBoundsInRoot()
        val next = screen.onNodeWithContentDescription(label(R.string.turn_next))
        val usage = screen.onNode(hasText(label(R.string.turn_usage)) and hasClickAction())
        assertTrue(next.getUnclippedBoundsInRoot().top >= copy.bottom)
        assertTrue(usage.getUnclippedBoundsInRoot().top >= next.getUnclippedBoundsInRoot().bottom)
        assertTrue(usage.getUnclippedBoundsInRoot().right <= 224.dp - 20.dp)
        next.performClick()
        screen.onNodeWithText("Last revision").assertIsDisplayed()
        usage.performScrollTo().performClick()
        assertUsageRows(R.string.turn_ttft to "0 ms")
    }

    @Test fun deletingMiddleVersionKeepsContextAndForkCopiesOnlyTheSurvivingVisibleRevision() {
        val vm = open()
        more()
        tap(R.string.turn_set_start)
        screen.onNodeWithText(label(R.string.turn_start)).assertIsDisplayed()
        more()
        tap(R.string.turn_delete_version)
        screen.onNodeWithText(label(R.string.turn_confirm_one)).assertIsDisplayed()
        confirm(R.string.drawer_delete)
        screen.onNodeWithText("Last revision").assertIsDisplayed()
        screen.onNodeWithText("2/2").assertIsDisplayed()
        screen.onNodeWithText(label(R.string.turn_start)).assertIsDisplayed()
        val beforeFork = screen.runOnIdle { vm.state.value.current }
        more()
        screen.onNodeWithText(label(R.string.turn_delete_all)).assertIsNotEnabled()
        tap(R.string.turn_fork)
        confirm(R.string.turn_fork)
        screen.onNodeWithText("Last revision").assertIsDisplayed()
        screen.onNodeWithText("2/2").assertDoesNotExist()
        screen.runOnIdle {
            val branch = vm.state.value.current
            val source = vm.state.value.history.single { it.id == original.id }
            assertNotEquals(source.id, branch.id)
            assertEquals(listOf(question.text, "Last revision"), branch.messages.map { it.text })
            assertEquals(branch.messages.last().id, branch.contextStart)
            assertEquals(branch.messages.first().id, branch.messages.last().replyTo)
            assertEquals(beforeFork, source)
            vm.openConversation(original.id)
        }
        screen.onNodeWithText("2/2").assertIsDisplayed()
        screen.onNodeWithContentDescription(label(R.string.turn_previous)).performClick()
        screen.onNodeWithText("First revision").assertIsDisplayed()
        screen.onNodeWithText("1/2").assertIsDisplayed()
    }

    @Test fun deletingOtherVersionsThenAllVersionsOnlyAffectsTheChosenReplySlot() {
        val vm = open()
        more()
        tap(R.string.turn_delete_others)
        confirm(R.string.drawer_delete)
        screen.onNodeWithText(answer.text).assertIsDisplayed()
        screen.onNodeWithText("2/3").assertDoesNotExist()
        more()
        tap(R.string.turn_edit)
        screen.onNode(hasSetTextAction() and hasText(answer.text)).performTextReplacement("Edited in the bubble")
        confirm(R.string.settings_save)
        screen.onNodeWithText("2/2").assertIsDisplayed()
        screen.onNodeWithText("Edited in the bubble").assertIsDisplayed()
        more()
        tap(R.string.turn_delete_all)
        screen.onNodeWithText(label(R.string.turn_delete_reply_all)).assertIsDisplayed()
        confirm(R.string.drawer_delete)
        screen.onNodeWithText(question.text).assertIsDisplayed()
        screen.onNodeWithText("Edited in the bubble").assertDoesNotExist()
        screen.runOnIdle {
            assertEquals(listOf(question.id), vm.state.value.current.messages.map { it.id })
            assertTrue(vm.state.value.current.versions.isEmpty())
            assertNotNull(vm.state.value.history.find { it.id == recorded.id })
        }
    }

    @Test fun multiSelectCanRemoveOneDisplayedRevisionWithoutDeletingThePrompt() {
        val vm = open()
        more()
        tap(R.string.turn_selection)
        screen.onNode(hasText(question.text) and isToggleable()).performClick()
        val one = screen.activity.getString(R.string.turn_delete_selected, 1)
        screen.onNode(hasText(one) and hasClickAction()).performClick()
        confirm(R.string.drawer_delete)
        screen.runOnIdle {
            val chat = vm.state.value.current
            assertEquals(listOf(question.text, "Last revision"), chat.messages.map { it.text })
            assertEquals(1, chat.versions.getValue(answer.id).selected)
        }
    }

    @Test fun translatedRevisionCanCollapseAndClearWithoutChangingItsSourceOrOtherRevisions() {
        val vm = open()
        screen.onNodeWithContentDescription(label(R.string.turn_next)).performClick()
        val heading = screen.activity.getString(R.string.turn_translation_in, "English")
        screen.onNodeWithText("Saved translation").performScrollTo().assertIsDisplayed()
        screen.onNodeWithText(heading).performClick()
        screen.onNodeWithText("Saved translation").assertDoesNotExist()
        screen.onNodeWithText(heading).performClick()
        screen.onNodeWithText("Saved translation").assertIsDisplayed()
        screen.onNodeWithContentDescription(label(R.string.turn_translate)).performClick()
        confirm(R.string.turn_translation_clear)
        screen.onNodeWithText("Saved translation").assertDoesNotExist()
        screen.onNodeWithText("Last revision").assertIsDisplayed()
        screen.runOnIdle {
            val chat = vm.state.value.current
            assertEquals(2, chat.versions.getValue(answer.id).others.size)
            assertNull(chat.messages.last().translation)
        }
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    @Config(shadows = [HeadlessMagnifier::class])
    fun selectCopyCopiesOnlyTheLongPressedWordAndCanThenCopyTheEntireReply() {
        open()
        more()
        tap(R.string.turn_select_copy)
        val body = screen.onNode(hasText(answer.text) and hasAnyAncestor(hasTestTag("turn-action-sheet")))
        val layouts = mutableListOf<TextLayoutResult>()
        body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val word = layouts.single().getBoundingBox(answer.text.indexOf("revision") + 2).center
        body.performTouchInput { longClick(word) }
        body.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft) }
        val clipboard = screen.activity.getSystemService(ClipboardManager::class.java)
        screen.runOnIdle { assertEquals("revision", clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        confirm(R.string.turn_copy_all)
        screen.runOnIdle { assertEquals(answer.text, clipboard.primaryClip!!.getItemAt(0).text.toString()) }
    }

    @Test fun sourceCopyAndWebPreviewUseOnlyTheChosenReply() {
        val vm = open()
        val source = "# Fixture heading\n\n<b>literal markup</b> [sample](https://example.invalid)\n"
        screen.runOnIdle { vm.editMessage(answer.id, source) }
        more()
        tap(R.string.turn_select_copy)
        screen.onNodeWithText(source).assertIsDisplayed()
        confirm(android.R.string.ok)
        more()
        tap(R.string.turn_copy_markdown_source)
        screen.runOnIdle {
            val clipboard = screen.activity.getSystemService(ClipboardManager::class.java)
            assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        more()
        tap(R.string.turn_web_preview)
        fun preview() = WindowInspector.getGlobalWindowViews().asSequence()
            .flatMap { it.allViews }.filterIsInstance<WebView>().firstOrNull()
        awaitHost { preview() != null }
        val web = screen.runOnIdle {
            val view = requireNotNull(preview())
            assertFalse(view.settings.javaScriptEnabled)
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            assertTrue(view.settings.blockNetworkLoads)
            val loaded = shadowOf(view).lastLoadData
            assertEquals("text/html", loaded.mimeType)
            val html = Base64.decode(loaded.data, Base64.DEFAULT).decodeToString()
            assertTrue(html.contains("<h1>Fixture heading</h1>"))
            assertTrue(html.contains("&lt;b&gt;literal markup&lt;/b&gt;"))
            assertFalse(html.contains("example.invalid"))
            assertFalse(html.contains(question.text))
            view
        }
        confirm(android.R.string.ok)
        screen.runOnIdle { assertTrue(shadowOf(web).wasDestroyCalled()) }
    }

    @Test fun totalOnlyHistoryAndRevisionsShowCountsButIgnoreTapsUntilThereIsADetail() {
        val legacy = answer.copy(totalTokens = 99_999)
        val modern = answer.copy(text = "Structured total", usage = TokenUsage(totalTokens = 100_000))
        val zero = answer.copy(text = "Zero total", usage = TokenUsage(totalTokens = 0), timing = ReplyTiming(null, null, null))
        val empty = answer.copy(text = "No statistics", usage = TokenUsage(), timing = ReplyTiming(null, null, null))
        val detailed = answer.copy(text = "Measured cache", usage = TokenUsage(totalTokens = 123_456, cachedInputTokens = 0))
        open(initial = usageChat(legacy, modern, zero, empty, detailed))
        val next = screen.onNodeWithContentDescription(label(R.string.turn_next))
        for (text in listOf("99999 tokens", "100.00k tokens", "0 tokens")) {
            val count = screen.onNodeWithText(text)
            count.assertIsDisplayed().assert(!hasClickAction())
            count.performTouchInput { click() }
            screen.onNodeWithTag("turn-usage-panel").assertDoesNotExist()
            next.performClick()
        }
        screen.onNodeWithText(empty.text).assertIsDisplayed()
        screen.onNodeWithText(label(R.string.turn_usage)).assertDoesNotExist()
        screen.onNodeWithText("0 tokens").assertDoesNotExist()
        next.performClick()
        screen.onNodeWithText("123.46k tokens").assertHasClickAction().performTouchInput { click() }
        assertUsageRows(R.string.turn_total to "123.46k", R.string.turn_cached to "0")
    }

    @Test fun partialUsageShowsMeasuredZerosAndOmitsAnUnmeasurableRate() {
        val reply = answer.copy(usage = TokenUsage(outputTokens = 0), timing = ReplyTiming(0, null, 0))
        open(initial = usageChat(reply))
        confirm(R.string.turn_usage)
        assertUsageRows(R.string.turn_output to "0", R.string.turn_ttft to "0 ms", R.string.turn_elapsed to "0 ms")
    }

    @Test fun zeroOutputOverPositiveElapsedTimeShowsAZeroRate() {
        val reply = answer.copy(usage = TokenUsage(outputTokens = 0), timing = ReplyTiming(null, null, 1005))
        open(initial = usageChat(reply))
        confirm(R.string.turn_usage)
        assertUsageRows(R.string.turn_output to "0", R.string.turn_tps to "0.0 tok/s", R.string.turn_elapsed to "1.01 s")
    }

    @Test fun englishUsageBriefFormatsTokenCountsAndAllThreeDurations() = checkUsageBrief("First visible reply text")

    @Test @Config(qualifiers = "zh-rCN-h915dp")
    fun chineseUsageBriefFormatsTokenCountsAndAllThreeDurations() = checkUsageBrief("回复首字")

    private fun checkUsageBrief(firstBodyLabel: String) {
        val reply = answer.copy(usage = TokenUsage(inputTokens = 123_456, cachedInputTokens = 100_000,
            outputTokens = 150_000, reasoningTokens = 99_999, totalTokens = 273_456),
            timing = ReplyTiming(firstTokenMs = 999, firstBodyMs = 1000, elapsedMs = 1250))
        open(initial = usageChat(reply))
        assertEquals(firstBodyLabel, label(R.string.turn_first_body))
        screen.onNodeWithText("273.46k tokens").assertHasClickAction().performTouchInput { click() }
        assertUsageRows(R.string.turn_total to "273.46k", R.string.turn_input to "123.46k",
            R.string.turn_cached to "100.00k", R.string.turn_output to "150.00k", R.string.turn_reasoning to "99999",
            R.string.turn_tps to "120000.0 tok/s", R.string.turn_ttft to "999 ms",
            R.string.turn_first_body to "1.00 s", R.string.turn_elapsed to "1.25 s")
    }

    @Test fun expandingAThoughtAndTwoToolStepsPreservesTheirOrderAroundVisibleText() {
        val reply = answer.copy(text = "🙂Done", thought = "Review both entries", tool = "Chosen offline option",
            toolCalls = listOf(ReplyToolCall("catalog_lookup", ToolCallState.Requested, 0),
                ReplyToolCall("file_search", ToolCallState.Completed, 2),
                ReplyToolCall("archived_lookup", ToolCallState.Incomplete)))
        val fixture = Conversation(title = "Ordered steps", messages = listOf(question, reply), modelRoutes = emptyMap())
        val vm = open(initial = fixture)
        screen.onNodeWithText("Chosen offline option").assertIsDisplayed()
            .assert(!hasAnyAncestor(hasTestTag("message-thought") or hasTestTag("message-tool-chain")))
        val thought = screen.onNodeWithText(label(R.string.turn_thinking))
        thought.performClick()
        screen.onNodeWithText(reply.thought).assertIsDisplayed()
        val search = screen.onNodeWithText("file_search")
        search.performClick()
        val recordedOffset = screen.activity.getString(R.string.turn_tool_position, 1)
        screen.onNodeWithText(recordedOffset).assertIsDisplayed()
        val topToBottom = listOf(reply.thought, "catalog_lookup", "🙂", "file_search", recordedOffset, "Done",
            label(R.string.turn_tool_position_unknown), "archived_lookup")
            .map { screen.onNodeWithText(it).getUnclippedBoundsInRoot() }
        topToBottom.zipWithNext().forEach { (previous, next) -> assertTrue(previous.bottom <= next.top) }
        thought.performClick()
        screen.onNodeWithText(reply.thought).assertDoesNotExist()
        screen.onNodeWithText(recordedOffset).assertIsDisplayed()
        screen.runOnIdle { assertEquals(fixture, vm.state.value.current) }
    }

    @Test fun toolBeforeTextPersistsTwoDifferentLatenciesAndDisplaysInclusiveOutput() {
        val vm = open()
        screen.runOnIdle { vm.openConversation(recorded.id) }
        screen.onNodeWithText("catalog_lookup").assertIsDisplayed()
        screen.onNodeWithText(label(R.string.turn_tool_requested)).assertIsDisplayed()
        val tool = screen.onNodeWithText("catalog_lookup")
        val body = screen.onNodeWithText("Answer")
        assertTrue(tool.getUnclippedBoundsInRoot().bottom <= body.getUnclippedBoundsInRoot().top)
        tool.performClick()
        screen.onNodeWithText(label(R.string.turn_tool_before_body)).assertIsDisplayed()
        tool.performClick()
        screen.onNodeWithText(label(R.string.turn_tool_before_body)).assertDoesNotExist()
        screen.onNodeWithText("30 tokens").performClick()
        assertUsageRows(R.string.turn_input to "20", R.string.turn_cached to "0", R.string.turn_output to "10",
            R.string.turn_reasoning to "4", R.string.turn_ttft to "12 ms", R.string.turn_first_body to "140 ms",
            R.string.turn_elapsed to "200 ms", R.string.turn_tps to "50.0 tok/s")
    }

    /** Start at the same send entry point as the composer. Only the provider frames and clock are fixtures. */
    private fun recordedChat() = runBlocking {
        var clock = 1000L
        val repository = object : GenerationRepository {
            override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String) = flow {
                val wire = ResponsesEventAccumulator(request)
                emit(wire.message)
                suspend fun frame(at: Long, type: String, json: String) {
                    clock = 1000L + at
                    wire.accept(json, type).deliver { emit(it) }
                }
                frame(4, "response.created", "{}")
                frame(12, "response.output_item.added", """{"item":{"id":"c","type":"function_call","name":"catalog_lookup"}}""")
                frame(45, "response.reasoning_text.delta", """{"output_index":1,"delta":"Working"}""")
                frame(80, "response.output_text.delta", """{"output_index":2,"delta":" "}""")
                frame(140, "response.output_text.done", """{"output_index":2,"text":"Answer"}""")
                frame(200, "response.completed", """{"response":{"usage":{"input_tokens":20,"output_tokens":10,
                    "input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":4}}}}""")
            }
        }
        val session = ConversationSession(this, repository, {}, monotonicMs = { clock })
        session.setDraft("Timing question")
        session.send("Fixture model", emptyMap(), null, null, remoteModel = RemoteModel("fixture", "chat"))
        session.state.first { !it.isGenerating }.current
    }
}

// The host has no Android display surface for the floating selection lens. Compose still performs
// word hit-testing and selection and writes the result to Android's ClipboardManager.
@Implements(Magnifier::class)
class HeadlessMagnifier {
    @Implementation protected fun show(x: Float, y: Float) = Unit
    @Implementation protected fun show(x: Float, y: Float, lensX: Float, lensY: Float) = Unit
    @Implementation protected fun dismiss() = Unit
}
