package io.hydrion.hub.ui.composer

import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.provider.CalendarContract
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.drawToBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.localtools.McpDefinition
import io.hydrion.hub.localtools.mcpFile
import io.hydrion.hub.model.*
import io.hydrion.hub.ui.HydrionApp
import io.hydrion.hub.ui.messages.MessageStyleStore
import io.hydrion.hub.ui.messages.MessageTone
import io.hydrion.hub.ui.settings.assistantFeatureDirectory
import io.hydrion.hub.provider.*
import androidx.compose.ui.semantics.SemanticsActions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w407dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostToolPanelTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private lateinit var owner: ViewModelStoreOwner
    private lateinit var app: Application
    private lateinit var vm: HubViewModel
    private lateinit var files: Path
    private var chatStream: ChatStream = { _, _, _ -> error("No model request expected") }

    @Before fun start() {
        val host = screen.activity
        files = Files.createTempDirectory(host.cacheDir.toPath(), "panel-")
        app = object : Application() {
            init { attachBaseContext(host.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files.toFile()
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(files.fileName.toString() + name, mode)
        }
        mountPanelHost()
    }

    private fun mountPanelHost() {
        owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        screen.runOnIdle {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HubViewModel(app) { protocol, request, reply -> chatStream(protocol, request, reply) } as T
            }
            vm = ViewModelProvider(owner, factory)[HubViewModel::class.java]
        }
        awaitUi { vm.workspaceLoad.value == WorkspaceLoad.Ready }
        screen.activityRule.scenario.onActivity { activity ->
            vm.setDraft("Panel draft")
            activity.setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { HydrionApp() } }
        }
    }
    @After fun close() { screen.activityRule.scenario.onActivity { owner.viewModelStore.clear() } }

    private fun label(id: Int) = screen.activity.getString(id)
    private fun act(id: String) = screen.onNodeWithTag("panel-action:" + id)
    private fun tap(id: String) {
        awaitUi { screen.onAllNodes(hasTestTag("panel-action:" + id) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        act(id).performScrollTo().performClick()
    }
    private fun enable(id: String) {
        val tag = "panel-switch:" + id
        awaitUi { screen.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        screen.onNodeWithTag(tag).performScrollTo().assertIsOff().performClick().assertIsOn()
    }
    private fun dialogButton(id: Int) = screen.onNode(hasText(label(id)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    private fun awaitUi(test: () -> Boolean) = screen.waitUntil(10000) {
        shadowOf(Looper.getMainLooper()).idle()
        test()
    }
    private fun waitForText(text: String) = awaitUi { screen.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun open() {
        screen.onNodeWithContentDescription("打开工具箱").assertIsEnabled().performClick()
        screen.onNodeWithTag("chat-tools-page").assertIsDisplayed()
    }

    private fun saveRuntimeName(name: String) {
        val field = hasSetTextAction() and hasText(label(R.string.runtime_name))
        screen.onNode(field).performTextReplacement(name)
        screen.onNode(hasText(label(R.string.settings_save)) and hasClickAction()).performScrollTo().performClick()
        awaitUi { screen.onAllNodes(field).fetchSemanticsNodes().isEmpty() }
        waitForText(name)
    }

    @Test @Config(qualifiers = "zh-rCN-w407dp-h915dp")
    fun emptyChatHintFollowsTheAssistantSelectedInTheDrawer() {
        val other = screen.runOnIdle {
            vm.setDraft("")
            assertTrue(vm.addAssistant("月笺"))
            vm.state.value.assistants.last()
        }
        fun hint(value: String) = screen.onNode(hasAnyAncestor(hasTestTag("composer-capsule")) and hasText(value),
            useUnmergedTree = true)
        hint("Message HydrionHub").assertIsDisplayed()
        screen.onNodeWithContentDescription("打开侧栏").performClick()
        screen.onNodeWithContentDescription(label(R.string.drawer_assistants)).performClick()
        screen.onNodeWithTag("assistant-row-" + other.id).performClick()
        awaitUi { vm.state.value.current.assistantId == other.id }
        hint("Message 月笺").assertIsDisplayed()
        hint("Message HydrionHub").assertDoesNotExist()
        val editor = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
        editor.performTextReplacement("一段草稿")
        open()
        screen.onNodeWithTag("tools-search-field").assertDoesNotExist()
        hint("Message 月笺").assertDoesNotExist()
        screen.onNodeWithTag("composer-panel-dismiss").performClick()
        editor.assertTextContains("一段草稿").performTextClearance()
        hint("Message 月笺").assertIsDisplayed()
        val blank = vm.state.value.copy(assistants = listOf(other.copy(name = "  ")))
        assertEquals(LocalAssistant.Default.name, blank.composerAssistantName)
        assertEquals(LocalAssistant.Default.name, blank.copy(assistants = emptyList()).composerAssistantName)
    }

    @Test fun emptyEnvironmentCanBeBoundThenBrowsedFromTheSamePanelWithConsent() {
        open()
        act("Terminal").assertIsNotEnabled()
        act("Files").assertIsNotEnabled()
        screen.onNodeWithTag("chat-tools-group:local").performScrollTo().performClick()
        waitForText(label(R.string.panel_missing_app))
        for (id in listOf("Create", "Browser")) {
            screen.onNodeWithTag("panel-switch:" + id).assertIsNotEnabled()
            act(id).assertIsNotEnabled().assertTextContains(label(R.string.panel_missing_app))
        }
        tap("Environment")
        screen.onNodeWithText(label(R.string.env_none)).assertIsDisplayed()
        dialogButton(R.string.env_edit)
        screen.onNodeWithText(label(R.string.settings_environments)).assertIsDisplayed()
        waitForText(label(R.string.container_add))
        screen.onNode(hasText(label(R.string.container_add)) and hasClickAction()).performClick()
        saveRuntimeName("Fixture container")
        screen.onNodeWithContentDescription(label(R.string.env_add)).performClick()
        saveRuntimeName("Fixture environment")
        screen.onNodeWithContentDescription(label(R.string.settings_back)).performClick()
        tap("Environment")
        waitForText("Fixture environment")
        dialogButton(android.R.string.ok)

        // Reopen the shipped panel with a new Activity, ViewModel and catalog reader on the same private files.
        screen.activityRule.scenario.onActivity { owner.viewModelStore.clear() }
        screen.activityRule.scenario.recreate()
        mountPanelHost()
        open()
        tap("Environment")
        waitForText("Fixture environment")
        val store = vm.environments
        val env = runBlocking { store.catalog().environments.single() }
        val root = files.resolve("proot-v1/workspaces").resolve(env.workspaceId)
        Files.write(Files.createDirectory(root.resolve("docs")).resolve("guide.txt"), "Synthetic fixture".toByteArray())
        screen.onNode(hasText("Fixture environment") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        awaitUi { vm.state.value.current.environmentId == env.id }

        tap("Files")
        dialogButton(android.R.string.cancel)
        screen.onNodeWithTag("panel-directory-path").assertDoesNotExist()
        tap("Files")
        dialogButton(R.string.panel_allow_once)
        waitForText("docs")
        act("folder:docs").performClick()
        waitForText("guide.txt")
        screen.onNodeWithTag("panel-directory-path").assertTextEquals("/workspace/docs")
        screen.onNodeWithContentDescription(label(R.string.settings_back)).performClick()
        tap("Path")
        dialogButton(R.string.panel_allow_once)
        waitForText("docs")
        act("folder:docs").performClick()
        waitForText("guide.txt")
        dialogButton(R.string.panel_use_folder)
        awaitUi { runBlocking { store.catalog().environments.single().guestCwd == "/workspace/docs" } }
        tap("Terminal")
        waitForText(label(R.string.env_missing_tools))
        screen.onNodeWithText(label(R.string.panel_terminal_unavailable)).assertIsDisplayed()
        dialogButton(android.R.string.ok)
        tap("Permissions")
        dialogButton(R.string.panel_deny)
        act("Files").assertIsNotEnabled()
        act("Path").assertIsNotEnabled()
        val saved = screen.runOnIdle { decodeWorkspace(encodeWorkspace(vm.backupSnapshot())) }
        assertTrue(saved.unsent!!.panelActionsDenied)
        assertEquals(env.id, saved.unsent.environmentId)
    }

    private fun installCalendarAndBrowser() {
        val manager = shadowOf(screen.activity.packageManager)
        val component = ComponentName("fixture.systemapps", "fixture.systemapps.ManualActivity")
        manager.addActivityIfNotPresent(component).exported = true
        fun register(action: String, configure: IntentFilter.() -> Unit) {
            val filter = IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT); configure() }
            manager.addIntentFilterForActivity(component, filter)
        }
        register(Intent.ACTION_INSERT) { addDataScheme("content"); addDataType("vnd.android.cursor.dir/event") }
        register(Intent.ACTION_VIEW) { addDataScheme("content"); addDataAuthority("com.android.calendar", null) }
        register(Intent.ACTION_VIEW) { addDataScheme("https"); addCategory(Intent.CATEGORY_BROWSABLE) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w401dp-h872dp-night-376dpi")
    fun chatToolboxKeepsHeaderAndControlRailsAlignedWhenScrolled() {
        installCalendarAndBrowser()
        val catalog = mcpFile(screen.activity.assistantFeatureDirectory(), vm.state.value.current.assistantId)
        val server = runBlocking {
            vm.environments.saveContainer(null, "Panel container", null)
            vm.environments.createEnvironment(vm.environments.catalog().containers.single().id, "Panel environment")
            val added = catalog.update { it.add(listOf(McpDefinition("Panel local", null))) }.servers.single()
            catalog.update { it.trust(added.id, true) }.servers.single()
        }
        open()
        tap("Environment")
        screen.onNode(hasText("Panel environment") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        awaitUi { vm.state.value.current.environmentId != null }
        act("Environment").assertTextContains("Panel environment")
        val group = screen.onNodeWithTag("chat-tools-group:environment")
        group.assertTextEquals(label(R.string.panel_work))
        act("Path").assertTextContains("/workspace")
        act("Permissions").assertTextContains(label(R.string.panel_permission_ask_detail)).assertTextContains(label(R.string.panel_ask))

        fun bounds(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        fun near(expected: Float, actual: Dp) = assertEquals(expected, actual.value, .8f)
        val settings = bounds("panel-action:Settings")
        val files = bounds("panel-action:Files")
        val terminal = bounds("panel-action:Terminal")
        near(30f, files.left - settings.left)
        near(30f, terminal.left - files.left)
        near(0f, terminal.top - settings.top)
        tap("Settings")
        screen.onNodeWithText(label(R.string.settings_environments)).assertIsDisplayed()
        screen.onNodeWithContentDescription(label(R.string.settings_back)).performClick()

        group.performClick().assertTextContains("Panel environment")
        val page = bounds("chat-tools-page")
        val heading = bounds("chat-tools-heading")
        val title = bounds("chat-tools-title")
        near(0f, (title.left + title.right - page.left - page.right) / 2)
        near(34f, heading.bottom - heading.top)
        near(4f, page.top - heading.bottom)
        val localTag = "chat-tools-group:local"
        val localHeader = bounds(localTag)
        near(32f, localHeader.bottom - localHeader.top)
        near(14f, localHeader.top - bounds("chat-tools-section:image").bottom)
        screen.onNodeWithTag(localTag).performClick()
        val toggle = screen.onNodeWithTag("panel-switch:Time")
        toggle.assertIsOff().performClick().assertIsOn()
        act("Time").assertIsEnabled()
        screen.onNodeWithTag("panel-switch:AskUser").assertIsNotEnabled()
        screen.onNodeWithTag(localTag).assertTextContains("1/5")
        val timeText = screen.onNodeWithText(label(R.string.panel_time), useUnmergedTree = true).getUnclippedBoundsInRoot()
        near(42f, timeText.left - page.left)
        val hit = bounds("panel-switch:Time")
        val track = bounds("panel-track:Time")
        near(48f, hit.right - hit.left)
        near(48f, hit.bottom - hit.top)
        near(12f, page.right - track.right)
        val count = screen.onNodeWithText("1/5", useUnmergedTree = true).getUnclippedBoundsInRoot()
        near(0f, count.right - track.right)
        val create = bounds("panel-switch:Create")
        val calendar = bounds("panel-switch:Calendar")
        near(56f, calendar.top - create.top)
        near(0f, calendar.right - hit.right)
        toggle.performScrollTo()
        val painted = paintedSwitchSize("Time")
        val density = screen.density.density
        assertEquals(44f * density, painted.first.toFloat(), 3f)
        assertEquals(26f * density, painted.second.toFloat(), 3f)

        val mcp = act(server.id)
        mcp.performScrollTo().assertIsOff()
        val tools = screen.onNodeWithText("1 tool", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val serverTrack = bounds("panel-track:" + server.id)
        near(8f, serverTrack.left - tools.right)
        near(0f, track.right - serverTrack.right)
        mcp.performClick()
        awaitUi { screen.onAllNodes(hasTestTag("panel-action:" + server.id) and isOn()).fetchSemanticsNodes().isNotEmpty() }
        screen.onNodeWithTag("chat-tools-group:mcp").assertTextContains("1/1")
        near(0f, (bounds("chat-tools-title").left + bounds("chat-tools-title").right - title.left - title.right) / 2)
        screen.onNodeWithTag("composer-panel-dismiss").performClick()
        assertEquals(Draft(text = "Panel draft"), vm.state.value.draft)
        println("Tool panel: density=$density, fontScale=" + screen.density.fontScale +
            ", viewport width=" + (page.right - page.left) + ", row pitch=" + (calendar.top - create.top) +
            ", painted track=$painted px, header=" + (localHeader.bottom - localHeader.top) +
            ", label inset=" + (timeText.left - page.left) + ", actions pitch=" + (files.left - settings.left))
    }

    // Measure the actual enabled track in the rendered window, including its rounded ends.
    private fun paintedSwitchSize(id: String): Pair<Int, Int> {
        val bounds = screen.onNodeWithTag("panel-track:" + id, useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInWindow
        return screen.runOnIdle {
            val image = screen.activity.window.decorView.drawToBitmap()
            val left = bounds.left.roundToInt()
            val midY = bounds.center.y.roundToInt()
            val fill = image.getPixel((bounds.left + bounds.width / 4).roundToInt(), midY)
            assertNotEquals(image.getPixel(left - 3, midY), fill)
            val pixels = android.graphics.Rect()
            for (y in bounds.top.roundToInt() - 3 .. bounds.bottom.roundToInt() + 3) {
                for (x in left - 3 .. bounds.right.roundToInt() + 3) {
                    if (image.getPixel(x, y) == fill) pixels.union(x, y, x + 1, y + 1)
                }
            }
            pixels.width() to pixels.height()
        }
    }

    @Test fun toolSectionsShareOneOpaqueSurfaceInDarkAndLightThemes() {
        open()
        screen.onNodeWithTag("chat-tools-card:environment", true).assertDoesNotExist()
        screen.onNodeWithTag("chat-tools-group:environment").performScrollTo().assertIsDisplayed()
        fun checkSurface(id: String, light: Boolean = false) {
            val header = screen.onNodeWithTag("chat-tools-group:$id", true).performScrollTo()
            header.assert(hasAnyAncestor(hasTestTag("chat-tools-section:$id")))
            screen.onNodeWithTag("chat-tools-card:$id", true).assertDoesNotExist()
            val box = header.fetchSemanticsNode().boundsInWindow
            val x = box.left.roundToInt()
            val y = box.center.y.roundToInt()
            screen.runOnIdle {
                val image = screen.activity.window.decorView.drawToBitmap()
                val fill = image.getPixel(x + 2, y)
                if (light) assertTrue(android.graphics.Color.luminance(fill) > .6f)
                else assertEquals(android.graphics.Color.rgb(29, 29, 29), fill)
                assertEquals("Group flows into the outer gutter", fill, image.getPixel(x - 4, y))
                val top = box.top.roundToInt() + 1
                val outer = image.getPixel(x - 4, top)
                val inner = image.getPixel(x + 1, top)
                // A dithered gradient can move a channel by one level across adjacent pixels.
                for (shift in listOf(0, 8, 16)) {
                    assertEquals("Header feather crosses the flat section and gutter",
                        ((outer ushr shift) and 255).toFloat(), ((inner ushr shift) and 255).toFloat(), 1f)
                }
            }
        }
        for (id in listOf("local", "mcp", "web", "image")) checkSurface(id)
        screen.onNodeWithTag("chat-tools-group:local").performScrollTo().performClick()
        screen.onNodeWithTag("panel-switch:Time", true).assert(hasAnyAncestor(hasTestTag("chat-tools-section:local")))
        enable("Time")
        screen.onNodeWithTag("chat-tools-group:local").performScrollTo().performClick()
        screen.onNodeWithTag("panel-switch:Time").assertDoesNotExist()
        checkSurface("local")
        screen.onNodeWithTag("chat-tools-group:image").performScrollTo().performClick()
        screen.onNodeWithTag("chat-tools-option:image:" + CUSTOM_IMAGE_TOOL, true).assert(hasAnyAncestor(hasTestTag("chat-tools-section:image")))
        val styles = MessageStyleStore.shared(screen.activity)
        val original = styles.style.value
        try {
            screen.runOnIdle { styles.update { it.copy(tone = MessageTone.Light) } }
            checkSurface("image", light = true)
            for (id in listOf("web", "mcp", "local")) checkSurface(id, light = true)
            screen.onNodeWithTag("chat-tools-group:local").performScrollTo().performClick()
            fun textInk(text: String): Int {
                val layouts = mutableListOf<TextLayoutResult>()
                screen.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                return layouts.single().layoutInput.style.color.toArgb()
            }
            val selectedInk = textInk(label(R.string.panel_time))
            screen.onNodeWithTag("panel-switch:Time").performScrollTo().assertIsOn().performClick().assertIsOff()
            val plainInk = textInk(label(R.string.panel_time))
            val secondaryInk = textInk(label(R.string.panel_local))
            assertNotEquals("Enabled control keeps its accent", selectedInk, plainInk)
            val rowLuminance = android.graphics.Color.luminance(plainInk)
            val secondaryLuminance = android.graphics.Color.luminance(secondaryInk)
            assertTrue("Light row=$rowLuminance, secondary=$secondaryLuminance", rowLuminance < secondaryLuminance && secondaryLuminance < .25f)
        } finally {
            screen.runOnIdle { styles.update { original } }
        }
        screen.onNodeWithTag("composer-panel-dismiss").performClick()
        assertEquals("Panel draft", vm.state.value.draft.text)
    }

    @Test fun askCardsKeepDraftAndDeliverOnceOnlyToTheirOriginalTurn() {
        val peer = QuestionPeer()
        chatStream = { protocol, request, reply -> when (protocol) {
            ChatProtocol.ResponsesHttp -> peer.transport.stream(request, reply)
            ChatProtocol.ChatCompletionsHttp -> peer.transport.streamChatCompletions(request, reply)
            else -> error("Unexpected transport")
        } }
        val model = ProviderModel("ask-model", "Fixture", profile = ModelProfile(abilities = setOf("tools")))
        fun provider(id: String, protocol: ChatProtocol) = newProvider(Mode.Chat).copy(id = id, name = id,
            protocol = protocol.label, baseUrl = "https://$id.invalid/v1", models = listOf(model))
        val a = provider("questions-a", ChatProtocol.ResponsesHttp)
        val b = provider("questions-b", ChatProtocol.ChatCompletionsHttp)
        fun choose(p: Provider) = screen.runOnIdle { vm.providers.configure(Mode.Chat, RemoteModel(p.id, model.id), emptyMap()) }
        screen.runOnIdle { for (p in listOf(a, b)) vm.providers.save(p, "fixture-only", null) }
        choose(a)
        fun localSwitch() = screen.onNodeWithTag("panel-switch:AskUser")
        fun openFromComposer() {
            // With no software IME the empty composer is folded. A real draft reveals its toolbar.
            if (screen.onAllNodesWithContentDescription("打开工具箱").fetchSemanticsNodes().isEmpty())
                screen.onNodeWithContentDescription("消息输入框").performTextReplacement("Unsent panel draft")
            open()
        }
        fun localPanel() {
            openFromComposer()
            if (screen.onAllNodesWithTag("panel-switch:AskUser").fetchSemanticsNodes().isEmpty())
                screen.onNodeWithTag("chat-tools-group:local").performScrollTo().performClick()
        }
        fun back() = screen.onNodeWithTag("composer-panel-dismiss").performClick()
        fun send(text: String) {
            screen.onNodeWithContentDescription("消息输入框").performTextReplacement(text)
            screen.onNodeWithContentDescription("发送消息").assertIsEnabled().performClick()
        }
        fun newChat() {
            screen.onNodeWithContentDescription("打开侧栏").performClick()
            screen.onNodeWithTag("drawer-new-chat").performClick()
        }
        fun visit(title: String) {
            screen.onNodeWithContentDescription("打开侧栏").performClick()
            screen.onNode(hasText(title) and hasAnyAncestor(hasTestTag("drawer-conversation-list"))).performScrollTo().performClick()
            awaitUi { vm.state.value.current.title == title }
        }
        fun form() = screen.onNodeWithTag("ask-form")
        fun option(label: String) = screen.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag("ask-form")))
        fun submit() = screen.onNodeWithTag("ask-submit")
        fun expectQuestion(prompt: String) { waitForText("Choose for $prompt"); form().assertExists(); submit().assertIsNotEnabled() }
        fun checkExchange(prompt: String, host: String, choice: String, completions: Boolean) {
            val requests = peer.sent.filter { it.prompt == prompt }
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.host == host })
            val tool = requests.first().body.getJSONArray("tools")
            assertEquals(1, tool.length())
            val definition = tool.getJSONObject(0)
            assertEquals(ASK_USER, (if (completions) definition.getJSONObject("function") else definition).getString("name"))
            val finished = requests.last()
            assertEquals(choice, finished.answer!!.getJSONObject("answers").getJSONObject("pick").getString("value"))
            val input = finished.body.getJSONArray(if (completions) "messages" else "input")
            val original = input.getJSONObject(input.length() - 2)
            val result = input.getJSONObject(input.length() - 1)
            if (completions) {
                assertEquals("shared-call", original.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
                assertEquals("tool", result.getString("role"))
                assertEquals("shared-call", result.getString("tool_call_id"))
            } else {
                val call = (0 until input.length() - 1).map(input::getJSONObject).single { it.optString("type") == "function_call" }
                assertEquals("function_call_output", result.getString("type"))
                assertEquals("shared-call", call.getString("call_id"))
                assertEquals("shared-call", result.getString("call_id"))
            }
        }

        localPanel(); localSwitch().performScrollTo().assertIsOff().performClick().assertIsOn(); back()
        send("Chat A"); expectQuestion("Chat A")
        assertEquals(3, vm.state.value.current.messages.last().replySteps.size)
        // The real unanswered form is before both tail steps. Its action must still be reachable.
        screen.onNodeWithText("Show 1 more step").assertDoesNotExist()
        form().performScrollTo().assertIsDisplayed()
        option("Blue").performClick().assertIsSelected()
        val aId = vm.state.value.current.id
        val replyId = vm.state.value.current.messages.last().id
        newChat(); choose(b)
        send("Chat B"); expectQuestion("Chat B")
        assertEquals(listOf(0, 0), vm.questions.pending.value.keys.map { it.slot })
        assertEquals(2, vm.questions.pending.value.keys.distinctBy { it.turn.chatId }.size)
        option("Green").performClick()
        visit("Chat A")
        option("Blue").assertIsSelected()
        val stale = submit().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        visit("Chat B")
        screen.runOnIdle { stale() }
        assertTrue(peer.sent.none { it.answer != null })
        option("Green").assertIsSelected()
        screen.onNodeWithTag("ask-cancel").performScrollTo().performClick()
        awaitUi { vm.state.value.current.messages.last().status == Generation.Stopped }
        assertEquals(ToolCallState.Incomplete, vm.state.value.current.messages.last().toolCalls.single().state)
        assertEquals(1, peer.sent.count { it.prompt == "Chat B" })
        assertEquals(1, vm.questions.pending.value.size)

        visit("Chat A"); option("Blue").assertIsSelected(); submit().performScrollTo().performClick()
        screen.runOnIdle { stale() }
        waitForText("Finished Chat A")
        val finishedA = vm.state.value.current
        assertEquals(aId, finishedA.id)
        assertEquals(listOf(Role.User, Role.Assistant), finishedA.messages.map { it.role })
        assertEquals(replyId, finishedA.messages.last().id)
        assertEquals(ToolCallState.Completed, finishedA.messages.last().toolCalls.single().state)
        assertEquals(listOf(ReplyStep.Tool(0), ReplyStep.Thought(1, 0, 0, 6), ReplyStep.Thought(2, 0, 8, 14),
            ReplyStep.Thought(3, 0, 14, 19)), finishedA.messages.last().replySteps)
        screen.onNodeWithText("Show 2 more steps").assertExists()
        checkExchange("Chat A", "questions-a.invalid", "Blue", false)

        visit("Chat B"); send("B stop"); expectQuestion("B stop")
        option("Blue").performClick()
        val late = submit().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        screen.onNodeWithContentDescription("停止生成").performClick()
        awaitUi { vm.state.value.current.messages.last().status == Generation.Stopped }
        send("B again"); expectQuestion("B again")
        screen.runOnIdle { late() }
        submit().assertIsNotEnabled()
        assertEquals(1, peer.sent.count { it.prompt == "B stop" })
        assertEquals(1, peer.sent.count { it.prompt == "B again" })
        option("Green").performClick(); submit().performScrollTo().performClick()
        waitForText("Finished B again")
        checkExchange("B again", "questions-b.invalid", "Green", true)
        val continued = vm.state.value.current.messages.last()
        assertEquals("BeforeAfter", continued.thought)
        assertEquals(listOf(ReplyStep.Thought(0, 0, 0, 6), ReplyStep.Tool(0), ReplyStep.Thought(1, 0, 6, 11)), continued.replySteps)

        send("Revoke"); expectQuestion("Revoke"); option("Blue").performClick()
        openFromComposer(); tap("Permissions"); dialogButton(R.string.panel_deny); back()
        awaitUi { vm.state.value.current.messages.last().status == Generation.Error }
        assertEquals(label(R.string.ask_disabled), vm.state.value.current.messages.last().error)
        assertEquals(1, peer.sent.count { it.prompt == "Revoke" })
        send("Denied"); awaitUi { vm.state.value.current.messages.last().status == Generation.Error }
        assertFalse(peer.sent.single { it.prompt == "Denied" }.body.has("tools"))
        openFromComposer(); tap("Permissions"); dialogButton(R.string.panel_ask); back()
        localPanel(); localSwitch().performScrollTo().assertIsOn().performClick(); back()
        send("Off"); awaitUi { vm.state.value.current.messages.last().status == Generation.Error }
        assertFalse(peer.sent.single { it.prompt == "Off" }.body.has("tools"))
        assertTrue(vm.questions.pending.value.isEmpty())
        assertEquals(2, peer.sent.count { it.answer != null })
    }

    @Test fun localToolsRequireAManualHandoffAndRespectDenial() {
        installCalendarAndBrowser()
        // Manual actions must remain reachable even when this provider cannot use chat tool modes.
        val model = vm
        screen.runOnIdle {
            model.providers.save(deepSeekProvider, "", null)
            model.providers.configure(Mode.Chat, deepSeekProvider.models.first().id, emptyMap())
        }
        open()
        screen.onNodeWithTag("chat-tools-option:web:模型内置搜索").assertIsNotEnabled()
        val beforeModes = screen.runOnIdle { vm.state.value.draft }
        screen.onNodeWithTag("chat-tools-track:web", true).performScrollTo().assertIsNotEnabled().performTouchInput { swipeRight() }
        screen.onNodeWithTag("chat-tools-group:image").performScrollTo().performClick()
        screen.onNodeWithTag("chat-tools-track:image", true).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        screen.onNodeWithTag("chat-tools-group:image").performScrollTo().performClick()
        assertEquals(beforeModes, screen.runOnIdle { vm.state.value.draft })
        screen.onNodeWithTag("chat-tools-group:local").performScrollTo().performClick()
        act("AskUser").assertIsNotEnabled()
        act("Time").assertIsNotEnabled()
        screen.onNodeWithTag("chat-tools-group:local").assertTextContains("0/5")
        for (id in listOf("Time", "Create", "Calendar", "Browser")) enable(id)
        val assistant = screen.runOnIdle { decodeWorkspace(encodeWorkspace(model.backupSnapshot())).assistants.single() }
        assertEquals(ManualPanelTool.entries.toSet() - ManualPanelTool.AskUser, assistant.manualPanelTools)
        screen.onNodeWithTag("chat-tools-group:local").assertTextContains("4/5")
        tap("Time")
        screen.onNodeWithTag("panel-current-time").assertIsDisplayed()
        dialogButton(android.R.string.ok)
        tap("Create")
        dialogButton(android.R.string.cancel)
        val host = shadowOf(screen.activity)
        assertNull(host.nextStartedActivity)
        tap("Create")
        dialogButton(R.string.panel_open_app)
        val create = host.nextStartedActivity
        assertEquals(Intent.ACTION_INSERT, create.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, create.data)
        tap("Calendar")
        dialogButton(R.string.panel_open_app)
        val view = host.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("time", view.data!!.pathSegments.first())
        tap("Browser")
        val input = screen.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        input.performTextReplacement("file:///example")
        screen.onNodeWithText(label(R.string.panel_open_app)).assertIsNotEnabled()
        input.performTextReplacement("HTTPS://fixture.invalid/guide")
        assertNull(host.nextStartedActivity)
        dialogButton(R.string.panel_open_app)
        assertEquals("https://fixture.invalid/guide", host.nextStartedActivity.dataString)
        tap("Permissions")
        dialogButton(R.string.panel_deny)
        for (id in listOf("Create", "Calendar", "Browser")) act(id).assertIsNotEnabled()
        assertNull(host.nextStartedActivity)
        tap("Permissions")
        dialogButton(R.string.panel_ask)
        act("Create").assertIsEnabled()
        assertNull(host.nextStartedActivity)
    }

    @Test fun configuredMcpAndManagementRemainScopedToTheCurrentAssistant() {
        val owner = vm.state.value.current.assistantId
        val root = screen.activity.assistantFeatureDirectory()
        val file = mcpFile(root, owner)
        val other = screen.runOnIdle {
            assertTrue(vm.addAssistant("Other assistant"))
            vm.state.value.assistants.last().id
        }
        val server = runBlocking {
            mcpFile(root, other).update { it.add(listOf(McpDefinition("Other assistant fixture", null))) }
            val catalog = file.update { it.add(listOf(McpDefinition("Fixture local", null), McpDefinition("Fixture remote", "https://fixture.invalid/mcp"))) }
            file.update { it.trust(catalog.servers.first().id, true) }.servers.first()
        }
        open()
        screen.onNodeWithTag("tools-search-field").assertDoesNotExist()
        waitForText("Fixture local")
        val selection = act(server.id)
        selection.performScrollTo().assertIsOff().performClick()
        awaitUi { runBlocking { file.read().servers.first().enabled } }
        awaitUi { screen.onAllNodes(hasTestTag("panel-action:" + server.id) and isOn()).fetchSemanticsNodes().isNotEmpty() }
        selection.assertIsOn()
        waitForText("Fixture remote")
        screen.onNodeWithText(label(R.string.mcp_config_only)).performScrollTo().assertIsDisplayed()
        tap("Mcp")
        waitForText(label(R.string.mcp_import))
        screen.onNode(hasText("Fixture remote") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        screen.onNodeWithText("Other assistant fixture").assertDoesNotExist()
        screen.onNodeWithContentDescription(label(R.string.settings_back)).performClick()
        act(server.id).performScrollTo().assertIsOn()
        screen.onNodeWithTag("composer-panel-dismiss").performClick()
        assertEquals(Draft(text = "Panel draft"), vm.state.value.draft)
    }
}
