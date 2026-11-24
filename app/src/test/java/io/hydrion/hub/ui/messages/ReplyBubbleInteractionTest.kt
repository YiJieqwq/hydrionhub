package io.hydrion.hub.ui.messages

import android.app.Application
import android.os.Looper
import android.content.Context
import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import java.nio.file.Files
import android.util.Base64
import android.view.inspector.WindowInspector
import android.webkit.WebView
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

    private fun open(): HubViewModel {
        val host = screen.activity
        val directory = Files.createTempDirectory(host.cacheDir.toPath(), "bubble-history-").toFile()
        val app = object : Application() {
            init { attachBaseContext(host.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
        }
        recorded = recordedChat()
        HistoryFile(app).save(SavedWorkspace(listOf(original, recorded)))
        val vm = screen.runOnIdle {
            ViewModelProvider(host, ViewModelProvider.AndroidViewModelFactory(app))["bubble-fixture", HubViewModel::class.java]
        }
        awaitHost { vm.workspaceLoad.value != WorkspaceLoad.Reading }
        assertEquals(WorkspaceLoad.Ready, vm.workspaceLoad.value)
        screen.activityRule.scenario.onActivity { activity ->
            vm.openConversation(original.id)
            assertEquals(answer.text, vm.state.value.current.messages.last().text)
            activity.setContent {
                MaterialTheme {
                    ProvideMessageStyle { MessageList(vm, vm.state.collectAsState().value, "", null, 0) }
                }
            }
        }
        screen.onNodeWithTag("message-timeline").performScrollToKey(answer.id)
        screen.onNodeWithText(answer.text).assertIsDisplayed()
        return vm
    }

    private fun label(id: Int) = screen.activity.getString(id)
    private fun more() = screen.onNodeWithContentDescription(label(R.string.turn_more_reply)).performClick()
    private fun tap(id: Int) = screen.onNode(hasText(label(id)) and hasClickAction()).performScrollTo().performClick()
    private fun confirm(id: Int) = screen.onNode(hasText(label(id)) and hasClickAction()).performClick()

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

    @Test fun unknownHistoricalUsageOpensFromTheReplyAndShowsMissingForEveryMetric() {
        open()
        confirm(R.string.turn_usage)
        screen.onAllNodesWithText(label(R.string.turn_unrecorded)).assertCountEquals(8)
        screen.onNodeWithText(label(R.string.turn_first_body)).assertIsDisplayed()
        screen.onNodeWithText(label(R.string.turn_ttft)).assertIsDisplayed()
        screen.onNodeWithText("0 ms").assertDoesNotExist()
    }

    @Test fun toolBeforeTextPersistsTwoDifferentLatenciesAndDisplaysInclusiveOutput() {
        val vm = open()
        screen.runOnIdle { vm.openConversation(recorded.id) }
        screen.onNodeWithText("catalog_lookup").assertIsDisplayed()
        screen.onNodeWithText(label(R.string.turn_tool_requested)).assertIsDisplayed()
        screen.onNodeWithText("Answer").assertIsDisplayed()
        val chip = screen.activity.getString(R.string.turn_tokens, 30L)
        screen.onNodeWithText(chip).performClick()
        fun value(labelId: Int, text: String) {
            screen.onNodeWithText(label(labelId), useUnmergedTree = true).onParent()
                .assert(anyValue(text))
        }
        value(R.string.turn_input, "20")
        value(R.string.turn_cached, "0")
        value(R.string.turn_output, "10")
        value(R.string.turn_reasoning, "4")
        value(R.string.turn_ttft, "12 ms")
        value(R.string.turn_first_body, "140 ms")
        value(R.string.turn_elapsed, "200 ms")
        value(R.string.turn_tps, "50.0 tok/s")
    }

    private fun anyValue(value: String) = hasAnyDescendant(hasText(value))

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
