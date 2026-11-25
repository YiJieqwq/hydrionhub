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
import io.hydrion.hub.ui.settings.assistantFeatureDirectory
import io.hydrion.hub.provider.deepSeekProvider
import androidx.compose.ui.semantics.SemanticsProperties
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
                    HubViewModel(app) { _, _, _ -> error("No model request expected") } as T
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
        near(14f, localHeader.top - group.getUnclippedBoundsInRoot().bottom)
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
        near(0f, bounds("chat-tools-title").top - title.top)
        screen.onNodeWithTag("chat-tools-back").performClick()
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
        screen.onNodeWithTag("chat-tools-group:local").performScrollTo().performClick()
        act("AskUser").assertIsNotEnabled()
        act("Time").assertIsNotEnabled()
        screen.onNodeWithTag("chat-tools-group:local").assertTextContains("0/5")
        for (id in listOf("Time", "Create", "Calendar", "Browser")) enable(id)
        val assistant = screen.runOnIdle { decodeWorkspace(encodeWorkspace(model.backupSnapshot())).assistants.single() }
        assertEquals(ManualPanelTool.entries.toSet(), assistant.manualPanelTools)
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

    @Test fun searchFindsConfiguredMcpAndManagementIsScopedToTheCurrentAssistant() {
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
        val query = screen.onNodeWithTag("tools-search-field")
        query.performTextReplacement("Fixture local")
        waitForText("Fixture local")
        val selection = act(server.id)
        selection.assertIsOff().performClick()
        awaitUi { runBlocking { file.read().servers.first().enabled } }
        awaitUi { screen.onAllNodes(hasTestTag("panel-action:" + server.id) and isOn()).fetchSemanticsNodes().isNotEmpty() }
        selection.assertIsOn()
        query.performTextReplacement("Fixture remote")
        waitForText("Fixture remote")
        screen.onNodeWithText(label(R.string.mcp_config_only)).assertIsDisplayed()
        act(server.id).assertDoesNotExist()
        query.performTextReplacement("Manage servers")
        tap("Mcp")
        waitForText(label(R.string.mcp_import))
        screen.onNodeWithText("Fixture remote").assertExists()
        screen.onNodeWithText("Other assistant fixture").assertDoesNotExist()
        screen.onNodeWithContentDescription(label(R.string.settings_back)).performClick()
        assertEquals("Manage servers", query.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        screen.onNodeWithTag("chat-tools-back").performClick()
        assertEquals(Draft(text = "Panel draft"), vm.state.value.draft)
    }
}
