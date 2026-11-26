package io.hydrion.hub.ui.messages

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.model.*
import io.hydrion.hub.provider.ChatProtocol
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.ui.HydrionApp
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.After
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
class ChatRevisionFlowTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var vm: HubViewModel
    private val question = "First question"
    private val older = "Earlier answer"
    private val newer = "Newer answer"
    private val laterQ = "Later original question"
    private val laterA = "Later original answer"
    private val currentModel = ProviderModel("current", "Current model", profile = ModelProfile(externalId = "current-wire"))
    private val olderModel = ProviderModel("older", "Earlier model", profile = ModelProfile(externalId = "earlier-wire"))
    private val currentRoute = RemoteModel("fixture", currentModel.id)

    private fun awaitHost(condition: () -> Boolean) = screen.waitUntil(15_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun open(protocol: ChatProtocol, stream: ((Message) -> Flow<Message>)? = null) {
        val host = screen.activity
        val directory = Files.createTempDirectory(host.cacheDir.toPath(), "revision-flow-").toFile()
        val app = object : Application() {
            init { attachBaseContext(host.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(directory.name + name, mode)
        }
        val provider = Provider(currentRoute.providerId, Mode.Chat, "Fixture", protocol.label, "https://fixture.invalid/v1",
            listOf(currentModel, olderModel))
        val q = Message(id = "q", role = Role.User, text = question)
        val a = Message(id = "a", role = Role.Assistant, text = older, replyTo = q.id, model = olderModel.name,
            remoteModel = RemoteModel(provider.id, olderModel.id),
            parameters = mapOf("model" to "earlier-wire", CHAT_PROTOCOL_PARAMETER to protocol.name))
        val q2 = Message(id = "q2", role = Role.User, text = laterQ)
        val a2 = Message(id = "a2", role = Role.Assistant, text = laterA, replyTo = q2.id)
        val chat = Conversation(id = "source", title = "Garden (3)", messages = listOf(q, a, q2, a2),
            contextStart = q.id, modelRoutes = mapOf(Mode.Chat to currentRoute))
            .appendVersion(a, a.copy(text = newer, remoteModel = currentRoute, model = currentModel.name,
                parameters = mapOf("model" to "current-wire", CHAT_PROTOCOL_PARAMETER to protocol.name)))
        HistoryFile(app).save(SavedWorkspace(listOf(chat, Conversation(title = "Garden (1)", archived = true)),
            drafts = mapOf(chat.id to Draft(text = "Parked original draft"))))
        // Exercise the shipped UI, provider validation and both JSON encoders. Only the socket boundary is replaced.
        screen.runOnIdle {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = HubViewModel(app) { _, request, pending ->
                    requests.add(request.body.toString())
                    stream?.invoke(pending) ?: flowOf(pending.copy(status = Generation.Completed, text = "Fixture response " + requests.size))
                } as T
            }
            vm = ViewModelProvider(owner, factory)[HubViewModel::class.java]
        }
        awaitHost { vm.workspaceLoad.value != WorkspaceLoad.Reading }
        assertEquals(WorkspaceLoad.Ready, vm.workspaceLoad.value)
        screen.activityRule.scenario.onActivity { activity ->
            vm.providers.save(provider, "offline-fixture", currentModel.id)
            vm.openConversation(chat.id)
            activity.setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { HydrionApp() } }
        }
    }

    @After fun close() { screen.runOnIdle { owner.viewModelStore.clear() } }

    private fun bubble(id: String, control: Int) {
        screen.onNodeWithTag("message-timeline").performScrollToKey(id)
        screen.onNode(hasContentDescription(screen.activity.getString(control)) and hasAnyAncestor(hasTestTag("message-turn-" + id)))
            .performScrollTo().assertIsEnabled().performClick()
    }

    private fun action(id: Int) = screen.onNode(hasText(screen.activity.getString(id)) and hasClickAction())

    private fun send(text: String) {
        screen.onNodeWithContentDescription("消息输入框").performClick().performTextReplacement(text)
        screen.onNodeWithContentDescription("发送消息").assertIsEnabled().performClick()
    }

    @Test fun sentReplyKeepsAdjacentFunctionsTogetherAsFramesArriveAndBodySeparatesTheNextGroup() {
        val appendCalls = CompletableDeferred<Unit>()
        val secondCall = CompletableDeferred<Unit>()
        val nextThought = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        open(ChatProtocol.ResponsesHttp) { pending -> flow {
            val fixture = if (pending.prompt == "Check inventory") "continuous-functions" else "deepseek-reasoning-text"
            val wire = requireNotNull(javaClass.getResource("/responses/$fixture.sse")).readText()
            readResponsesEvents(wire.reader().buffered(), pending) { part ->
                emit(part)
                if (part.thought == "Check the lamp." && part.toolCalls.isEmpty()) appendCalls.await()
                if (part.toolCalls.size == 1) secondCall.await()
                if (part.toolCalls.size == 2 && part.thought == "Check the lamp.") nextThought.await()
                if (part.thought.endsWith("Compare both.") && part.text.isEmpty()) finish.await()
            }
        } }
        send("Check inventory")
        awaitHost { vm.state.value.current.messages.last().thought == "Check the lamp." }
        val replyId = vm.state.value.current.messages.last().id
        val turn = hasAnyAncestor(hasTestTag("message-turn-$replyId"))
        val card = hasTestTag("message-thought")
        val title = screen.activity.getString(R.string.turn_thinking)
        val thoughts = screen.onAllNodes(turn and hasText(title) and hasClickAction())
        // Compose collections retain the IDs from their first fetch. Query each live frame afresh.
        fun cards() = screen.onAllNodes(turn and card)
        cards().assertCountEquals(1)
        screen.onNodeWithText("Show 1 more step").assertDoesNotExist()
        val single = cards()[0].getUnclippedBoundsInRoot()
        thoughts[0].performScrollTo().performClick()
        screen.onNodeWithText("Check the lamp.").assertDoesNotExist()
        screen.runOnIdle { appendCalls.complete(Unit) }
        awaitHost { vm.state.value.current.messages.last().toolCalls.size == 1 }
        screen.onNode(turn and hasText("ping_a")).assertExists()
        screen.onNodeWithText("Show 1 more step").assertDoesNotExist()
        screen.runOnIdle { secondCall.complete(Unit) }
        awaitHost { vm.state.value.current.messages.last().toolCalls.size == 2 }
        screen.onNode(turn and hasText(title)).assertDoesNotExist()
        screen.onNode(turn and hasText("ping_a")).assertExists()
        screen.onNode(turn and hasText("ping_b")).assertExists()
        screen.onNodeWithText("Show 1 more step").performScrollTo().performClick()
        screen.runOnIdle { nextThought.complete(Unit) }
        awaitHost { vm.state.value.current.messages.last().thought.endsWith("Compare both.") }
        cards().assertCountEquals(1)
        screen.onNodeWithText("Collapse").performScrollTo().performClick()
        screen.onNode(turn and hasText("ping_a")).assertDoesNotExist()
        screen.onNode(turn and hasText("ping_b")).assertExists()
        screen.onAllNodes(turn and hasText(title) and hasClickAction()).assertCountEquals(1)
        screen.onNodeWithText("Show 2 more steps").performScrollTo().performClick()
        val rows = screen.onAllNodes(turn and hasAnyAncestor(card) and hasClickAction() and
            (hasText(title) or hasText("ping_a") or hasText("ping_b")))
        rows.assertCountEquals(4)
        listOf(title, "ping_a", "ping_b", title).forEachIndexed { i, label ->
            rows[i].assertTextContains(label)
            if (i > 0) assertTrue(rows[i - 1].getUnclippedBoundsInRoot().top < rows[i].getUnclippedBoundsInRoot().top)
        }
        screen.onNodeWithText("Compare both.").assertExists()
        thoughts[0].performScrollTo().performClick()
        screen.onNodeWithText("Check the lamp.").assertExists()
        thoughts[1].performScrollTo().performClick()
        screen.onNodeWithText("Compare both.").assertDoesNotExist()
        screen.onNodeWithText("Check the lamp.").assertExists()
        rows[1].performScrollTo().performClick()
        val sheet = hasAnyAncestor(hasTestTag("turn-action-sheet"))
        screen.onNode(sheet and hasText(screen.activity.getString(R.string.turn_tool_running))).assertIsDisplayed()
        screen.runOnIdle { finish.complete(Unit) }
        awaitHost { !vm.state.value.isGenerating }
        val completed = vm.state.value.current.messages.last()
        assertEquals(completed.error, Generation.Completed, completed.status)
        assertEquals("Visible divider.Finished.", completed.text)
        assertEquals(listOf(0, 0, "Visible divider.".length),
            completed.replySteps.filterIsInstance<ReplyStep.Thought>().map { it.bodyOffset })
        screen.onNode(sheet and hasText("Lamp present")).assertIsDisplayed()
        screen.onNode(sheet and hasText("Book unavailable")).assertDoesNotExist()
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.turn_close_details)).performClick()
        cards().assertCountEquals(2)
        val divider = screen.onNode(turn and hasText("Visible divider.")).getUnclippedBoundsInRoot()
        val first = cards()[0].getUnclippedBoundsInRoot()
        val second = cards()[1].getUnclippedBoundsInRoot()
        assertTrue(first.bottom <= divider.top && divider.bottom <= second.top)
        assertEquals(single.left, first.left)
        assertEquals(single.right, first.right)
        assertEquals(first.left, second.left)
        assertEquals(first.right, second.right)
        screen.onNode(turn and hasText("ping_b") and hasClickAction()).performScrollTo().performClick()
        screen.onNode(sheet and hasText(screen.activity.getString(R.string.turn_tool_failed))).assertIsDisplayed()
        screen.onNode(sheet and hasText("Book unavailable")).assertIsDisplayed()
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.turn_close_details)).performClick()
        thoughts[2].performScrollTo().performClick()
        screen.onNodeWithText("Review the shelf.").assertExists()
        screen.onNodeWithText("Check the lamp.").assertExists()
        screen.onNodeWithText("Compare both.").assertDoesNotExist()

        send("New turn")
        awaitHost { !vm.state.value.isGenerating && vm.state.value.current.messages.last().text == "Monday." }
        val nextId = vm.state.value.current.messages.last().id
        val next = hasAnyAncestor(hasTestTag("message-turn-$nextId"))
        screen.onAllNodes(next and card).assertCountEquals(1)
        screen.onNode(next and hasText(title) and hasClickAction()).performScrollTo().performClick()
        screen.onNode(next and hasText("Checked the date.")).assertIsDisplayed()
        screen.onAllNodes(next and (hasText("ping_a") or hasText("Review the shelf."))).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "en-rUS-w393dp-h842dp-night-xhdpi")
    fun sentShellStepsReserveTimeAndShowOnlyRecordedExitAndOutput() {
        open(ChatProtocol.ResponsesHttp) { pending -> flow { playPaletteSteps(pending) { emit(it) } } }
        send("Check the monthly swatches")
        awaitHost { !vm.state.value.isGenerating && vm.state.value.current.messages.last().text == "The six monthly swatches are ready." }
        val reply = vm.state.value.current.messages.last()
        val scope = hasAnyAncestor(hasTestTag("message-turn-" + reply.id))
        fun text(label: String) = screen.onNode(scope and hasText(label), useUnmergedTree = true)
        fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        assertEquals(listOf(250L, null, 750L, 1250L), reply.replySteps.map { it.observedMs })
        val stored = encodeWorkspace(SavedWorkspace(listOf(vm.state.value.current)))
        val reopened = decodeWorkspace(stored).history.first().messages.last()
        assertEquals(reply.replySteps, reopened.replySteps)
        text("exit 2").assertDoesNotExist()
        text("exit 0").performScrollTo().assertIsDisplayed()
        val card = screen.onNode(scope and hasTestTag("message-thought")).getUnclippedBoundsInRoot()
        val fold = screen.onNode(scope and hasText("Show 2 more steps") and hasClickAction()).getUnclippedBoundsInRoot()
        val foldLabel = text("Show 2 more steps").getUnclippedBoundsInRoot()
        assertEquals(12.dp, fold.left - card.left)
        assertEquals(4.dp, fold.top - card.top)
        assertEquals(44.dp, foldLabel.left - card.left)
        assertEquals(4.dp, foldLabel.top - fold.top)
        assertEquals(4.dp, fold.bottom - foldLabel.bottom)
        val title = screen.onNode(scope and hasText("Shell: printf", substring = true), useUnmergedTree = true)
        val label = layout(title)
        assertEquals(2, label.lineCount)
        assertTrue(label.isLineEllipsized(1))
        val time = text("1.3s").getUnclippedBoundsInRoot()
        val titleBox = title.getUnclippedBoundsInRoot()
        assertEquals(foldLabel.left, titleBox.left)
        assertEquals(8.dp, time.left - titleBox.right)
        // To the right of the duration are the gap, disclosure icon and card inset.
        assertEquals(36.dp, card.right - time.right)
        val timeLayout = layout(text("1.3s"))
        val lastGlyph = timeLayout.getBoundingBox(timeLayout.layoutInput.text.lastIndex)
        assertEquals(1, timeLayout.lineCount)
        assertFalse(timeLayout.isLineEllipsized(0))
        assertTrue("Duration glyph $lastGlyph outside measured " + timeLayout.size,
            lastGlyph.right <= timeLayout.size.width && lastGlyph.bottom <= timeLayout.size.height)
        val stdoutField = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Standard output")
        val out = screen.onNode(scope and stdoutField, useUnmergedTree = true)
        screen.onNode(scope and hasText("Shell: printf", substring = true) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Completed, observed step duration 1.3s"))
        val stdout = "April: mint\nMay: amber\nJune: lilac\nJuly: blue\nAugust: pine\nSeptember: plum"
        val output = text(stdout)
        assertEquals(FontFamily.Monospace, layout(output).layoutInput.style.fontFamily)
        val header = screen.onNode(scope and hasText("Shell: printf", substring = true) and hasClickAction()).getUnclippedBoundsInRoot()
        val exit = text("exit 0").getUnclippedBoundsInRoot()
        val box = out.getUnclippedBoundsInRoot()
        val lines = output.getUnclippedBoundsInRoot()
        assertEquals(titleBox.left, exit.left)
        assertEquals(exit.left, box.left)
        assertEquals(4.dp, exit.top - header.bottom)
        assertEquals(4.dp, box.top - exit.bottom)
        assertEquals(12.dp, card.right - box.right)
        assertEquals(8.dp, lines.left - box.left)
        assertEquals(6.dp, lines.top - box.top)
        assertEquals(6.dp, box.bottom - lines.bottom)
        val panel = out.performScrollTo().fetchSemanticsNode().boundsInWindow
        val window = screen.runOnIdle { screen.activity.window.decorView.drawToBitmap() }
        assertEquals(Color.rgb(27, 32, 35), window.getPixel(panel.center.x.roundToInt(), panel.top.roundToInt() + 2))
        screen.onNode(scope and hasText(stdout) and hasClickAction()).performScrollTo().performClick()
        val sheet = hasAnyAncestor(hasTestTag("turn-action-sheet"))
        fun detail(label: String) = screen.onNode(sheet and hasText(label), useUnmergedTree = true)
        val call = reply.toolCalls.single { it.name == "workspace_shell" }
        val command = JSONObject(requireNotNull(call.arguments)).getString("command")
        val recorded = requireNotNull(call.result)
        assertEquals(stdout, JSONObject(recorded).getString("stdout"))
        val titleBounds = detail("Shell").assertIsDisplayed().getUnclippedBoundsInRoot()
        val exitBounds = detail("exit 0").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(exitBounds.left >= titleBounds.right && exitBounds.top < titleBounds.bottom)
        detail("bash").assertIsDisplayed()
        val styled = layout(detail(command).assert(!hasSetTextAction())).layoutInput.text
        assertEquals(command, styled.text)
        assertTrue(styled.spanStyles.any { command.substring(it.start, it.end) == "printf" })
        assertTrue(styled.spanStyles.map { it.item.color }.distinct().size >= 2)
        screen.onNode(sheet and hasScrollToIndexAction()).performScrollToNode(hasText(stdout))
        detail(stdout).assertIsDisplayed()
        detail("stderr").assertDoesNotExist()
        detail(recorded).assertDoesNotExist()
        screen.onNodeWithContentDescription("Close details").performClick()
        screen.onNodeWithText("Show 2 more steps").performScrollTo().performClick()
        text("0.3s").assertExists()
        text("0.8s").assertExists()
        text("exit 2").performScrollTo().assertIsDisplayed()
        assertNotEquals(layout(text("exit 0")).layoutInput.style.color, layout(text("exit 2")).layoutInput.style.color)
        screen.onAllNodes(scope and stdoutField, useUnmergedTree = true).assertCountEquals(1)
        screen.onNode(scope and hasText("Shell: read-palette Autumn") and hasClickAction()).performScrollTo().performClick()
        detail("exit 2").assertIsDisplayed()
        detail("stderr").assertExists()
        detail("No autumn swatch in this fixture.").assertExists()
        detail("stdout").assertDoesNotExist()
        screen.onNodeWithContentDescription("Close details").performClick()
        screen.onNodeWithText("Collapse").performScrollTo().performClick()
        text("exit 2").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en-rUS-w393dp-h842dp-xhdpi")
    fun sentReplyPaintsTheRecordedToolIconsAndKeepsDetailsClickable() {
        open(ChatProtocol.ResponsesHttp) { pending -> flow {
            val events = requireNotNull(javaClass.getResource("/responses/tool-card-icons.sse"))
            events.openStream().bufferedReader().use { readResponsesEvents(it, pending) { part -> emit(part) } }
        } }
        send("Show recorded tool calls")
        awaitHost { vm.state.value.current.messages.last().text == "已按顺序整理工具记录。" && !vm.state.value.isGenerating }
        val replyId = vm.state.value.current.messages.last().id
        val turn = hasAnyAncestor(hasTestTag("message-turn-" + replyId))
        fun row(name: String) = screen.onNode(turn and hasText(name) and hasClickAction())
        val expected = mapOf("shell" to R.drawable.lucide_terminal, "search_web" to R.drawable.lucide_earth,
            "web_search_call" to R.drawable.lucide_search, "fetch_url" to R.drawable.lucide_link,
            "read_file" to R.drawable.lucide_file_text, "clipboard_tool" to R.drawable.lucide_clipboard_check,
            "get_time_info" to R.drawable.lucide_clock, "catalog_lookup" to R.drawable.lucide_wrench)
        screen.onNodeWithText("Show 7 more steps").performScrollTo().performClick()
        // Align the expanded reply below the floating header before sampling actual window pixels.
        screen.onNodeWithTag("message-timeline").performScrollToKey(replyId)
        for ((name, icon) in expected) {
            assertRowIcon(name, row(name).assertIsDisplayed(), icon)
        }
        row("shell").performScrollTo().performClick()
        screen.onNode(hasAnyAncestor(hasTestTag("turn-action-sheet")) and hasText("shell")).assertIsDisplayed()
    }

    // Compare the rendered leading glyph with the resource silhouette independently of its tint.
    private fun assertRowIcon(name: String, row: SemanticsNodeInteraction, expected: Int) {
        val bounds = row.fetchSemanticsNode().boundsInWindow
        val density = screen.activity.resources.displayMetrics.density
        val size = (14 * density).roundToInt()
        val left = (bounds.left + 5 * density).roundToInt()
        val top = (bounds.center.y - size / 2f).roundToInt()
        val window = screen.runOnIdle { screen.activity.window.decorView.drawToBitmap() }
        val reference = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        requireNotNull(screen.activity.getDrawable(expected)).apply {
            setBounds(0, 0, size, size)
            draw(Canvas(reference))
        }
        val background = Color.red(window.getPixel(bounds.left.roundToInt(), top))
        val glyph = IntArray(size * size)
        window.getPixels(glyph, 0, size, left, top, size, size)
        val contrast = glyph.maxOf { abs(Color.red(it) - background) }
        assertTrue("$name glyph contrast $contrast", contrast > 48)
        var intersection = 0
        var union = 0
        for (y in 0 until size) for (x in 0 until size) {
            val painted = abs(Color.red(glyph[y * size + x]) - background) * 255 > contrast * 120
            val wanted = Color.alpha(reference.getPixel(x, y)) > 120
            if (painted && wanted) intersection++
            if (painted || wanted) union++
        }
        assertTrue("$name glyph overlap $intersection/$union at $bounds, contrast $contrast", union > 0 && intersection > union * .85)
    }

    private fun wire(index: Int, array: String, model: String): List<Pair<String, String>> {
        awaitHost { requests.size >= index && !vm.state.value.isGenerating }
        assertEquals(index, requests.size)
        val body = JSONObject(requests[index - 1])
        assertEquals(model, body.getString("model"))
        val turns = body.getJSONArray(array)
        return (0 until turns.length()).map { i -> turns.getJSONObject(i).let { it.getString("role") to it.getString("content") } }
    }

    @Test fun forkSelectedOlderReplyThenSendFromBranchAndFromOriginalUseDifferentSerializedPrefixes() {
        open(ChatProtocol.ChatCompletionsHttp)
        bubble("a", R.string.turn_previous)
        val source = vm.state.value.current
        assertEquals(older, source.messages[1].text)
        assertEquals(0, source.versions.getValue("a").selected)
        bubble("a", R.string.turn_more_reply)
        action(R.string.turn_fork).performScrollTo().assertIsDisplayed().performClick()
        val fork = vm.state.value.current
        assertNotEquals(source.id, fork.id)
        assertEquals("Garden (2)", fork.title)
        assertEquals(listOf(question, older), fork.messages.map(Message::text))
        assertTrue(fork.versions.isEmpty())
        assertTrue(fork.messages.none { it.id in source.messages.map(Message::id) })
        assertEquals(fork.messages.first().id, fork.contextStart)
        assertEquals(fork.contextStart, fork.messages.last().replyTo)
        send("Branch follow-up")
        val prefix = listOf("user" to question, "assistant" to older)
        assertEquals(prefix + ("user" to "Branch follow-up"), wire(1, "messages", "current-wire"))
        val branchAfterSending = vm.state.value.current
        assertEquals(source, vm.state.value.history.single { it.id == source.id })
        screen.runOnIdle { vm.openConversation(source.id) }
        assertEquals(source, vm.state.value.current)
        assertEquals("Parked original draft", vm.state.value.draft.text)
        send("Original follow-up")
        assertEquals(prefix + listOf("user" to laterQ, "assistant" to laterA, "user" to "Original follow-up"),
            wire(2, "messages", "current-wire"))
        assertEquals(branchAfterSending, vm.state.value.history.single { it.id == fork.id })
    }

    @Test fun editedOlderAnswerContinuesInANewSlotAndEachRetryUsesTheSelectedPrefixAndModel() {
        open(ChatProtocol.ResponsesHttp)
        bubble("a", R.string.turn_previous)
        bubble("a", R.string.turn_more_reply)
        action(R.string.turn_edit).performScrollTo().assertIsDisplayed().performClick()
        screen.onNode(hasSetTextAction() and hasText(older)).performTextReplacement("Edited answer")
        action(R.string.turn_save_continue).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf("user" to question, "assistant" to "Edited answer"), wire(1, "input", "current-wire"))
        val edited = vm.state.value.current
        assertEquals(listOf(question, "Edited answer", "Fixture response 1", laterQ, laterA), edited.messages.map(Message::text))
        assertEquals(listOf(older, newer), edited.versions.getValue("a").others.map(Message::text))
        val continuation = edited.messages[2]
        assertTrue(continuation.continuesAssistant)
        assertFalse(edited.versions.containsKey(continuation.id))
        assertEquals("Parked original draft", vm.state.value.draft.text)

        bubble("a", R.string.turn_previous)
        bubble("a", R.string.turn_previous)
        bubble(continuation.id, R.string.turn_retry)
        action(R.string.turn_retry).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf("user" to question, "assistant" to older), wire(2, "input", "current-wire"))

        bubble("a", R.string.turn_retry)
        screen.onNode(hasText("Fixture · Earlier model", substring = true)).assertIsDisplayed()
        action(R.string.turn_retry).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf("user" to question), wire(3, "input", "earlier-wire"))
        val saved = decodeWorkspace(encodeWorkspace(vm.backupSnapshot())).history.single { it.id == edited.id }
        assertEquals(listOf(question, "Fixture response 3", "Fixture response 2", laterQ, laterA), saved.messages.map(Message::text))
        assertEquals(3, saved.versions.getValue("a").selected)
        assertEquals(listOf(older, newer, "Edited answer"), saved.versions.getValue("a").others.map(Message::text))
        assertTrue(saved.messages[2].continuesAssistant)
        assertEquals(continuation, saved.versions.getValue(continuation.id).others.single())
    }
}
