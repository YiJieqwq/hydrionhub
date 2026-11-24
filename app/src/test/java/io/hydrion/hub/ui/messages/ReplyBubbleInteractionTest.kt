package io.hydrion.hub.ui.messages

import android.app.Application
import android.os.Looper
import android.content.Context
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color.parseColor
import android.graphics.drawable.RippleDrawable
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
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
import androidx.core.view.drawToBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.hydrion.hub.localtools.LocalMcpFixture
import io.hydrion.hub.MainActivity
import org.json.JSONObject
import io.hydrion.hub.R
import io.hydrion.hub.model.*
import io.hydrion.hub.ui.profile.LocalProfileStore
import io.hydrion.hub.ui.theme.HydrionTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
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
    private lateinit var inputMode: InputModeManager
    private var previousStyle: MessageStyle? = null
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
                HydrionTheme {
                    inputMode = LocalInputModeManager.current
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

    private fun setStyle(style: MessageStyle) = screen.runOnIdle {
        val store = MessageStyleStore.shared(screen.activity)
        if (previousStyle == null) previousStyle = store.style.value
        store.update { style }
    }

    @After fun restoreAppearance() {
        previousStyle?.let { saved -> screen.runOnIdle { MessageStyleStore.shared(screen.activity).update { saved } } }
    }

    private fun snapshot(tag: String, sheet: Boolean = false): Bitmap {
        val bounds = screen.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        return screen.runOnIdle {
            val view = if (sheet) WindowInspector.getGlobalWindowViews().last() else screen.activity.window.decorView
            val window = view.drawToBitmap()
            val width = bounds.width.roundToInt()
            val height = bounds.height.roundToInt()
            val top = if (sheet) window.height - height else bounds.top.roundToInt()
            Bitmap.createBitmap(window, bounds.left.roundToInt(), top, width, height)
        }
    }

    private fun textLayout(text: String) = layoutOf(screen.onNodeWithText(text, useUnmergedTree = true))

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val layout = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        return layout.single()
    }

    private fun textInk(text: String) = textLayout(text).layoutInput.style.color.toArgb()

    // The midpoints are on straight edges, clear of rounded corners, padding and glyphs.
    private fun assertFlatCard(tag: String, fill: Int) {
        val bitmap = snapshot(tag)
        val midX = bitmap.width / 2
        val midY = bitmap.height / 2
        for ((x, y) in listOf(midX to 0, midX to 5, 0 to midY, (bitmap.width - 1) to midY, midX to (bitmap.height - 1))) {
            val pixel = bitmap.getPixel(x, y)
            assertEquals("$tag at ($x, $y)", Integer.toHexString(fill), Integer.toHexString(pixel))
        }
    }

    private fun checkThoughtAndToolCards(tone: MessageTone) {
        val body = "[Guide](https://example.invalid)"
        val reply = answer.copy(text = body, thought = "Review the checklist", status = Generation.Error, error = "Offline warning",
            toolCalls = listOf(ReplyToolCall("file_search", ToolCallState.Completed, body.length, result = "One public match."),
                ReplyToolCall("catalog_lookup", ToolCallState.Requested, body.length)))
        val custom = MessageCardStyle("#E9DFC4", "#E31176", "#23465A")
        val style = MessageStyle(tone = tone, light = MessagePalette(custom, defaultMessagePalette(MessageTone.Light).user),
            dark = MessagePalette(custom, defaultMessagePalette(MessageTone.Dark).user), assistantBubble = true)
        val dark = tone == MessageTone.Dark
        val fill = parseColor(if (dark) "#242628" else custom.background)
        val ink = parseColor(if (dark) "#FFFFFF" else custom.text)
        val thought = label(R.string.turn_thinking)
        val tool = "file_search"
        val position = screen.activity.getString(R.string.turn_tool_position, body.length)
        open(initial = usageChat(reply))
        setStyle(if (dark) MessageStyle(tone = MessageTone.Dark) else style)
        val warning = textInk("Offline warning")
        assertNotEquals(ink, warning)
        fun checkCards() {
            assertFlatCard("message-thought", fill)
            assertFlatCard("message-tool-chain", fill)
            assertEquals(ink, textInk(thought))
            assertEquals(ink, textInk(tool))
            val statusInk = if (dark) ink else Color(ink).copy(alpha = .65f).toArgb()
            assertEquals(statusInk, textInk(label(R.string.turn_tool_done)))
            val card = screen.onNodeWithTag("message-tool-chain").fetchSemanticsNode().boundsInWindow
            val second = screen.onNodeWithText("catalog_lookup", useUnmergedTree = true).onParent().fetchSemanticsNode().boundsInWindow
            val scale = screen.density.density
            val joinY = (second.top - card.top + scale).roundToInt()
            val lineX = (20 * scale).roundToInt()
            val bitmap = snapshot("message-tool-chain")
            assertTrue("The two tool steps must stay connected", (lineX - 1..lineX + 1).any { bitmap.getPixel(it, joinY) != fill })
        }
        checkCards()
        screen.onNodeWithText(thought).performClick()
        screen.onNodeWithText(tool).performClick()
        screen.onNodeWithText(position).assertIsDisplayed()
        screen.onNodeWithContentDescription(label(R.string.turn_close_details)).performClick()
        checkCards()
        val detailInk = if (dark) ink else Color(ink).copy(alpha = .8f).toArgb()
        for ((tag, text) in listOf("message-thought" to reply.thought, "message-tool-chain" to "One public match.")) {
            assertEquals(detailInk, textInk(text))
            val card = screen.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
            val detail = screen.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            val pixels = snapshot(tag)
            val right = (detail.right - card.left).roundToInt() - 1
            assertEquals("Expanded content edge", fill, pixels.getPixel(right, (detail.center.y - card.top).roundToInt()))
        }
        if (dark) {
            for (material in CardMaterial.entries) {
                setStyle(style.copy(material = material))
                checkCards()
                assertEquals(ink, textInk(reply.thought))
                assertEquals(ink, textInk("One public match."))
            }
        }
        screen.onNodeWithText(thought).performClick()
        screen.onNodeWithText(reply.thought).assertDoesNotExist()
        screen.onNodeWithText(position).assertDoesNotExist()
        checkCards()
        setStyle(style)
        val bodyCard = snapshot("message-assistant-body")
        assertEquals(parseColor(custom.border), bodyCard.getPixel(bodyCard.width / 2, 0))
        val link = textLayout("Guide").layoutInput.text
        assertTrue(link.spanStyles.any { it.item.textDecoration == TextDecoration.Underline && it.item.color.toArgb() != ink })
        assertEquals(warning, textInk("Offline warning"))
    }

    @Test @Config(qualifiers = "en-rUS-w393dp-h880dp-xhdpi")
    fun darkThoughtAndToolCardsKeepTheirExactFillAndInkWhenToggled() = checkThoughtAndToolCards(MessageTone.Dark)

    @Test @Config(qualifiers = "en-rUS-w393dp-h880dp-xhdpi")
    fun lightThoughtAndToolCardsKeepTheirPaletteWithoutAnOutline() = checkThoughtAndToolCards(MessageTone.Light)

    @Test @Config(qualifiers = "zh-rCN-w393dp-h880dp-xhdpi")
    fun appearancePagePreviewsTheChatThoughtCardInBothStates() {
        setStyle(MessageStyle(tone = MessageTone.Dark, material = CardMaterial.Frosted))
        screen.onNodeWithContentDescription("打开侧栏").performClick()
        screen.onNodeWithTag("drawer-settings").performClick()
        screen.onNodeWithText(label(R.string.settings_style)).performScrollTo().performClick()
        val tag = "message-thought"
        val thinking = label(R.string.turn_thinking)
        screen.onNodeWithTag(tag).performScrollTo()
        assertFlatCard(tag, parseColor("#242628"))
        screen.onNodeWithText(thinking).performClick()
        screen.onNodeWithText("这里展示思考内容。").performScrollTo().assertIsDisplayed()
        assertFlatCard(tag, parseColor("#242628"))
        assertEquals(Color.White.toArgb(), textInk("这里展示思考内容。"))
    }

    @Test @Config(qualifiers = "en-rUS-w393dp-h880dp-xhdpi")
    fun phoneMenusShowTheActionsForEachRoleInBothTones() {
        open(initial = usageChat(answer))
        val actions = hasAnyAncestor(hasTestTag("turn-action-sheet")) and hasClickAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        for (tone in listOf(MessageTone.Dark, MessageTone.Light)) {
            setStyle(MessageStyle(tone = tone))
            for ((entry, count) in listOf(R.string.turn_more to 7, R.string.turn_more_reply to 8)) {
                screen.onNodeWithContentDescription(label(entry)).performScrollTo().performClick()
                val rows = screen.onAllNodes(actions).assertCountEquals(count)
                repeat(count) { i ->
                    val bounds = rows[i].getUnclippedBoundsInRoot()
                    assertEquals(44.dp, bounds.bottom - bounds.top)
                }
                val sheet = snapshot("turn-action-sheet", sheet = true)
                val expected = if (tone == MessageTone.Dark) Color.Black else Color.White
                assertEquals(expected.toArgb(), sheet.getPixel(4, sheet.height / 2))
                val edit = screen.onNodeWithText(label(R.string.turn_edit))
                if (entry == R.string.turn_more) edit.assertDoesNotExist() else edit.assertIsDisplayed()
                tap(R.string.turn_copy_markdown_source)
            }
        }
    }

    @Test fun userFooterPencilStillEditsThePrompt() {
        open(initial = usageChat(answer))
        screen.onNodeWithContentDescription(label(R.string.turn_edit)).performScrollTo().performClick()
        screen.onNode(hasSetTextAction() and hasText(question.text)).performTextReplacement("Edited prompt")
        confirm(R.string.settings_save)
        screen.onNodeWithText("Edited prompt").assertIsDisplayed()
        more()
        tap(R.string.turn_edit)
        screen.onNode(hasSetTextAction() and hasText(answer.text)).assertIsDisplayed()
    }

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

    @Test @Config(qualifiers = "en-rUS-w407dp-h915dp-xxhdpi")
    fun englishMessagesOnPhone() = checkMessageGeometry(narrow = false, chinese = false)

    @Test @Config(qualifiers = "zh-rCN-w407dp-h915dp-xxhdpi")
    fun chineseMessagesOnPhone() = checkMessageGeometry(narrow = false, chinese = true)

    @Test @Config(qualifiers = "en-rUS-w320dp-h915dp-xxhdpi")
    fun englishMessagesOnNarrowScreen() = checkMessageGeometry(narrow = true, chinese = false)

    @Test @Config(qualifiers = "zh-rCN-w320dp-h915dp-xxhdpi")
    fun chineseMessagesOnNarrowScreen() = checkMessageGeometry(narrow = true, chinese = true)

    private fun checkMessageGeometry(narrow: Boolean, chinese: Boolean) {
        val short = if (chinese) "给我一份灯塔巡检清单。" else "Review the plan."
        val long = if (chinese) "请逐项核对交付清单，再确认下一步安排，并把变更告知团队。"
            else "Review the plan and confirm the next milestone with the team."
        val first = question.copy(text = short, createdAt = 1738555506000L)
        val second = question.copy(id = "long", text = long, createdAt = first.createdAt + 1000)
        val last = answer.copy(model = "Beacon QA", text = "OK", createdAt = first.createdAt + 2000)
        open(initial = Conversation(messages = listOf(first, second, last)))
        val density = screen.activity.resources.displayMetrics.density
        val locale = screen.activity.resources.configuration.locales[0]
        val profile = LocalProfileStore.shared(screen.activity)
        val previous = profile.profile.value.name
        val name = if (chinese) "用户" else "User"

        // Logical sizes from the pinned Android reference widget with the same Roboto/Noto fonts.
        // At 3x, the Redmi fixture occupies 583–584 physical pixels depending on edge rounding.
        val shortWidth = if (chinese) 194.5f else 136.6f
        val longWidth = if (chinese) { if (narrow) 225.5f else 287.5f } else { if (narrow) 219.94f else 279.39f }
        fun near(expectedDp: Float, actualPx: Float) = assertEquals(expectedDp * density, actualPx, 2f)
        try {
            runBlocking { profile.save(name, null, false) }
            for (msg in listOf(first, second, last)) {
                screen.onNodeWithTag("message-timeline").performScrollToKey(msg.id)
                val turn = hasAnyAncestor(hasTestTag("message-turn-${msg.id}"))
                fun node(matcher: SemanticsMatcher) = screen.onNode(turn and matcher, useUnmergedTree = true)
                val user = msg.role == Role.User
                val avatar = node(hasTestTag(if (user) "turn-user-avatar" else "turn-assistant-avatar")).fetchSemanticsNode().boundsInWindow
                val byline = node(hasText(if (user) name else msg.model))
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).format(java.util.Date(msg.createdAt))
                val time = node(hasText(stamp))
                val bylineBox = byline.fetchSemanticsNode().boundsInWindow
                val timeBox = time.fetchSemanticsNode().boundsInWindow
                near(32f, avatar.width)
                near(32f, avatar.height)
                near(8f, if (user) avatar.left - bylineBox.right else bylineBox.left - avatar.right)
                near(0f, if (user) bylineBox.right - timeBox.right else bylineBox.left - timeBox.left)
                val baselineGap = (timeBox.top + layoutOf(time).firstBaseline) - (bylineBox.top + layoutOf(byline).firstBaseline)
                val expectedGap = if (!user) 16.89f else if (chinese) 17.87f else 18.89f
                near(expectedGap, baselineGap)
                if (user) {
                    val bubble = node(hasTestTag("message-user-bubble")).fetchSemanticsNode().boundsInWindow
                    val lines = if (msg == first) 1 else if (narrow) 3 else 2
                    assertEquals(lines, textLayout(msg.text).lineCount)
                    near(if (msg == first) shortWidth else longWidth, bubble.width)
                    near(24f + 22f * lines, bubble.height)
                    near(0f, avatar.right - bubble.right)
                }
            }
        } finally {
            runBlocking { profile.save(previous, null, false) }
        }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h915dp-xxhdpi")
    fun editingAWrappedRightToLeftBubbleRewrapsAndKeepsItsRightEdge() {
        val originalText = "مرحبا بكم في فريق العمل. ".repeat(3).trim()
        open(fontScale = 1.3f, initial = Conversation(messages = listOf(question.copy(text = originalText))))
        val bubble = screen.onNodeWithTag("message-user-bubble")
        val before = bubble.fetchSemanticsNode().boundsInWindow
        val node = screen.onNodeWithText(originalText, useUnmergedTree = true)
        val layout = layoutOf(node)
        assertTrue(layout.lineCount > 1)
        val textLeft = node.fetchSemanticsNode().positionInWindow.x
        for (line in 0 until layout.lineCount) {
            assertTrue(textLeft + layout.getLineLeft(line) >= before.left)
            assertTrue(textLeft + layout.getLineRight(line) <= before.right)
        }
        bubble.performClick()
        screen.onNode(hasSetTextAction() and hasText(originalText)).performTextReplacement("好")
        confirm(R.string.settings_save)
        screen.onNodeWithText("好", useUnmergedTree = true).assertIsDisplayed()
        val after = bubble.fetchSemanticsNode().boundsInWindow
        assertEquals(before.right, after.right)
        assertTrue(after.width < before.width)
        assertTrue(after.height < before.height)
        assertEquals(1, textLayout("好").lineCount)
    }

    @Test @Config(qualifiers = "zh-rCN-w407dp-h915dp-xxhdpi")
    fun onlyTheStockUserBubbleGetsThePhoneFillForEachTone() {
        val prompt = question.copy(text = "给我一份灯塔巡检清单。")
        open(initial = Conversation(messages = listOf(prompt)))
        fun fill() = snapshot("message-user-bubble").let { it.getPixel(it.width / 2, it.height / 5) }
        for (tone in MessageTone.entries) {
            val stock = MessageStyle(tone = tone)
            val palette = stock.palette
            val expected = parseColor(if (tone == MessageTone.Dark) "#161F22" else "#ECF3F5")
            setStyle(stock)
            val bounds = screen.onNodeWithTag("message-user-bubble").getUnclippedBoundsInRoot()
            assertEquals(194.5f, (bounds.right - bounds.left).value, .5f)
            assertEquals(46.dp, bounds.bottom - bounds.top)
            assertEquals("Stock $tone", expected, fill())
            setStyle(stock.withPalette(tone, palette.copy(assistant = palette.assistant.copy(text = "#FF5599"))))
            assertEquals(expected, fill())

            setStyle(stock.copy(material = CardMaterial.Default))
            assertEquals(parseColor(if (tone == MessageTone.Dark) "#303030" else "#E3E4E7"), fill())
            val custom = stock.withPalette(tone, palette.copy(user = palette.user.copy(background = "#D98A59", text = "#130F0A")))
            setStyle(custom)
            assertEquals(parseColor("#D98A59"), fill())
            assertEquals(parseColor("#130F0A"), textInk(prompt.text))
            setStyle(custom.copy(material = CardMaterial.Frosted))
            val frosted = fill()
            assertNotEquals(expected, frosted)
            assertNotEquals(parseColor("#D98A59"), frosted)
            setStyle(stock)
            assertEquals(expected, fill())
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w407dp-h915dp-xxhdpi")
    fun purplePresetsApplyInSolidAndFrostedFromTheSettingsScreen() {
        setStyle(MessageStyle())
        screen.onNodeWithContentDescription("打开侧栏").performClick()
        screen.onNodeWithTag("drawer-settings").performClick()
        screen.onNodeWithText(label(R.string.settings_style)).performScrollTo().performClick()
        fun choose(text: String) = screen.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
        fun pixel(): Int {
            screen.onNodeWithTag("message-user-bubble").performScrollTo()
            val image = snapshot("message-user-bubble")
            return image.getPixel(image.width / 2, image.height / 5)
        }
        for ((tone, purple, automatic) in listOf(
            Triple("深色", "#020562", "#161F22"), Triple("浅色", "#E5EBFF", "#ECF3F5")
        )) {
            choose(tone)
            choose("纯色")
            val baseline = pixel()
            screen.onNode(hasClickAction() and hasText("背景色") and hasText(automatic)).assertExists()
            if (tone == "浅色") choose("磨砂玻璃")
            choose("背景色")
            screen.onNodeWithContentDescription("使用 $purple").performScrollTo().performClick()
            choose("纯色")
            val solid = pixel()
            choose("磨砂玻璃")
            val frosted = pixel()
            choose("纯色")
            val returned = pixel()
            assertEquals("Automatic $tone", parseColor(automatic), baseline)
            assertEquals("Solid $tone (frosted pixel ${Integer.toHexString(frosted)})", parseColor(purple), solid)
            assertTrue("Frosted retains the purple pigment: ${Integer.toHexString(frosted)}",
                android.graphics.Color.blue(frosted) - android.graphics.Color.red(frosted) > 10)
            assertEquals(solid, returned)
            screen.onNode(hasClickAction() and hasText("背景色") and hasText(purple)).assertExists()
            val selected = if (tone == "深色") MessageTone.Dark else MessageTone.Light
            assertTrue(selected in MessageStyleStore.shared(screen.activity).style.value.chosenUserPalettes)
        }
        choose("深色")
        choose("默认")
        assertEquals(parseColor("#303030"), pixel())
        choose("纯色")
        assertEquals(parseColor("#020562"), pixel())
        open(initial = Conversation(messages = listOf(question)))
        val saved = MessageStyleStore.shared(screen.activity).style.value
        for (tone in MessageTone.entries) {
            val chosen = saved.copy(tone = tone)
            setStyle(chosen.copy(material = CardMaterial.Solid))
            assertEquals(parseColor(chosen.palette.user.background), pixel())
            setStyle(chosen.copy(material = CardMaterial.Frosted))
            val glass = pixel()
            assertTrue(android.graphics.Color.blue(glass) - android.graphics.Color.red(glass) > 10)
        }
    }

    @Test fun olderSavedPresetAndCustomColorsWinWithoutChangingStoredPreferencesOnRead() {
        val host = screen.activity.applicationContext
        val isolated = object : android.content.ContextWrapper(host) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = host.getSharedPreferences("old-palette-$name", mode)
        }
        val legacy = MessageStyle().let { it.copy(light = it.light.copy(user = it.light.user.copy(background = "#934BD1"))) }
        val fields = legacy.toPreferences(includeChoices = false)
        val prefs = isolated.getSharedPreferences(AppRestoreStore.current(isolated).preference("message_style"), Context.MODE_PRIVATE)
        prefs.edit().apply { fields.forEach { (key, value) -> putString(key, value) } }.commit()
        val store = MessageStyleStore(isolated)
        val restored = store.style.value
        assertEquals(fields, prefs.all)
        open(initial = Conversation(messages = listOf(question)))
        for (tone in MessageTone.entries) {
            val picked = restored.copy(tone = tone)
            val expected = parseColor(picked.palette.user.background)
            setStyle(picked)
            val solid = snapshot("message-user-bubble")
            assertEquals(expected, solid.getPixel(solid.width / 2, solid.height / 5))
            setStyle(picked.copy(material = CardMaterial.Frosted))
            val glass = snapshot("message-user-bubble")
            val pixel = glass.getPixel(glass.width / 2, glass.height / 5)
            assertTrue(android.graphics.Color.blue(pixel) - android.graphics.Color.green(pixel) > 25)
            setStyle(picked)
            val returned = snapshot("message-user-bubble")
            assertEquals(expected, returned.getPixel(returned.width / 2, returned.height / 5))
        }
        store.update { MessageStyle() }
        assertEquals(MessageStyle(), MessageStyleStore(isolated).style.value)
        store.update { it.withPalette(MessageTone.Dark, it.dark, userSelected = true) }
        val reopened = MessageStyleStore(isolated).style.value
        assertEquals(setOf(MessageTone.Dark), reopened.chosenUserPalettes)
        assertFalse(reopened.usesAutomaticUserTint)
        assertTrue(reopened.copy(tone = MessageTone.Light).usesAutomaticUserTint)
    }

    @Test fun aCustomTimelineAccentStillTintsTheUneditedUserBubble() {
        val accent = Color.Green
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                HydrionTheme {
                    MaterialTheme(
                        colorScheme = MaterialTheme.colorScheme.copy(primary = accent)
                    ) {
                        MessageCanvas(MessageStyle(), Modifier.fillMaxWidth()) { UserBubble(question, "", Mode.Chat) }
                    }
                }
            }
        }
        val shot = snapshot("message-user-bubble")
        val sampled = shot.getPixel(shot.width / 2, shot.height / 5)
        assertEquals(0, android.graphics.Color.red(sampled))
        assertEquals(0, android.graphics.Color.blue(sampled))
        assertEquals(255 * .15f, android.graphics.Color.green(sampled).toFloat(), 1f)
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

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test @Config(qualifiers = "en-rUS-w407dp-h915dp-xxhdpi")
    fun stepTouchesKeepCardPixelsWhileFocusAndOtherButtonsStillGiveFeedback() {
        val reply = answer.copy(thought = "Review the checklist",
            toolCalls = listOf(ReplyToolCall("file_search", ToolCallState.Completed, answer.text.length, result = "One public match.")))
        open(initial = usageChat(reply))
        fun button(text: String) = screen.onNode(hasText(text) and hasClickAction())
        val thought = button(label(R.string.turn_thinking))
        val tool = button("file_search")
        val summary = button("One public match.")
        // A software window capture can omit the RenderThread ripple even when the drawable is active.
        fun nativeRipplePressed() = screen.runOnIdle {
            screen.activity.window.decorView.allViews.any {
                val ripple = it.background as? RippleDrawable
                ripple != null && android.R.attr.state_pressed in ripple.state
            }
        }
        fun press(node: SemanticsNodeInteraction, tag: String, release: Boolean = true) {
            val resting = snapshot(tag)
            node.assertHasClickAction().performTouchInput { down(center) }
            screen.mainClock.advanceTimeBy(240)
            val held = snapshot(tag)
            val ripple = nativeRipplePressed()
            node.performTouchInput { if (release) up() else cancel() }
            assertFalse("Touch started a ripple on $tag", ripple)
            assertTrue("Touch changed the $tag pixels", resting.sameAs(held))
        }

        for (tone in MessageTone.entries) {
            setStyle(MessageStyle(tone = tone))
            press(thought, "message-thought")
            screen.onNodeWithText(reply.thought).assertIsDisplayed()
            press(thought, "message-thought", release = false)
            screen.onNodeWithText(reply.thought).assertIsDisplayed()
            press(tool, "message-tool-chain")
            screen.onNodeWithTag("turn-action-sheet").assertIsDisplayed()
            screen.onNodeWithContentDescription(label(R.string.turn_close_details)).performClick()
            press(summary, "message-tool-chain")
            screen.onNodeWithTag("turn-action-sheet").assertIsDisplayed()
            screen.onNodeWithContentDescription(label(R.string.turn_close_details)).performClick()
            summary.assertIsDisplayed()
            press(thought, "message-thought")
            screen.onNodeWithText(reply.thought).assertDoesNotExist()
        }

        val copy = screen.onNodeWithContentDescription(label(R.string.turn_copy_reply))
        copy.performTouchInput { down(center) }
        screen.mainClock.advanceTimeBy(240)
        val copyRipple = nativeRipplePressed()
        copy.performTouchInput { cancel() }
        assertTrue("The ordinary reply button must still show its press", copyRipple)

        val resting = snapshot("message-thought")
        screen.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        screen.runOnIdle { assertEquals(InputMode.Keyboard, inputMode.inputMode) }
        thought.performSemanticsAction(SemanticsActions.RequestFocus)
        thought.assertIsFocused()
        screen.mainClock.advanceTimeBy(200)
        assertFalse("Keyboard focus must remain visible", resting.sameAs(snapshot("message-thought")))
        thought.performKeyInput { pressKey(Key.Enter) }
        screen.onNodeWithText(reply.thought).assertIsDisplayed()
        tool.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        tool.performKeyInput { pressKey(Key.Enter) }
        screen.onNodeWithTag("turn-action-sheet").assertIsDisplayed()
        screen.onNodeWithContentDescription(label(R.string.turn_close_details)).performClick()
        summary.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        summary.performKeyInput { pressKey(Key.Enter) }
        screen.onNodeWithTag("turn-action-sheet").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "en-rUS-w407dp-h915dp-xxhdpi")
    fun expandedStepsKeepTheirInsetsConnectorAndRecordedToolDetails() {
        val thought = "Check the inventory\nbefore handoff."
        val first = "inventory_lookup"
        val last = "catalog_lookup"
        val reply = answer.copy(text = "Done", thought = thought, toolCalls = listOf(
            ReplyToolCall(first, ToolCallState.Completed, 0, arguments = "{}", result = "Found a lamp."),
            ReplyToolCall(last, ToolCallState.Completed, 0, result = "Second result.")))
        val conversation = Conversation(messages = listOf(reply))
        val vm = open(initial = conversation)
        val saved = screen.runOnIdle { vm.state.value.current }
        setStyle(MessageStyle(tone = MessageTone.Dark))
        fun header(text: String) = screen.onNode(hasText(text) and hasClickAction())
        fun rect(text: String) = screen.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val card = screen.onNodeWithTag("message-thought")
        val title = label(R.string.turn_thinking)
        val position = label(R.string.turn_tool_before_body)
        val density = screen.density.density
        val original = card.fetchSemanticsNode().boundsInWindow
        fun checkBody(headerText: String, bodyText: String, next: String?) {
            val headerBox = header(headerText).fetchSemanticsNode().boundsInWindow
            val body = rect(bodyText)
            val bounds = card.fetchSemanticsNode().boundsInWindow
            assertEquals(4 * density, body.top - headerBox.bottom, 1f)
            assertEquals(rect(headerText).left, body.left, 1f)
            assertEquals(40 * density, body.left - bounds.left, 1f)
            val bottom = if (next != null) header(next).fetchSemanticsNode().boundsInWindow.top else bounds.bottom - 4 * density
            assertEquals(8 * density, bottom - body.bottom, 1f)
            val bitmap = snapshot("message-thought")
            val x = (20 * density).roundToInt()
            val fill = parseColor("#242628")
            for (y in headerBox.bottom.roundToInt() until bottom.roundToInt()) {
                val row = y - bounds.top.roundToInt()
                val hasRail = (x - 1..x + 1).any { bitmap.getPixel(it, row) != fill }
                assertEquals("Connector at row $row", next != null, hasRail)
            }
        }
        header(title).performClick()
        checkBody(title, thought, first)
        assertEquals(12.5f, textLayout(thought).layoutInput.style.fontSize.value)
        checkBody(first, "Found a lamp.", last)
        assertEquals(12f, textLayout("Found a lamp.").layoutInput.style.fontSize.value)
        header(first).performClick()
        val sheet = hasAnyAncestor(hasTestTag("turn-action-sheet"))
        screen.onNode(sheet and hasText(first), useUnmergedTree = true).assertIsDisplayed()
        screen.onNode(sheet and hasText(label(R.string.turn_tool_done)), useUnmergedTree = true).assertIsDisplayed()
        screen.onNode(sheet and hasText(position), useUnmergedTree = true).assertIsDisplayed()
        screen.onNode(sheet and hasText("{}"), useUnmergedTree = true).assertIsDisplayed()
        screen.onNode(sheet and hasText("Found a lamp."), useUnmergedTree = true).assertIsDisplayed()
        screen.onNodeWithContentDescription("Close details").performClick()
        header(title).performClick()
        screen.onNodeWithText(thought).assertDoesNotExist()
        checkBody(last, "Second result.", null)
        header(last).performClick()
        screen.onNode(sheet and hasText("Second result."), useUnmergedTree = true).assertIsDisplayed()
        screen.onNodeWithContentDescription("Close details").performClick()
        assertEquals(original, card.fetchSemanticsNode().boundsInWindow)
        screen.runOnIdle { assertEquals(saved, vm.state.value.current) }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h915dp-xxhdpi")
    fun narrowToolSummaryOpensTheFullSelectableRecord() {
        val body = "Follow up."
        val name = "inventory_lookup_" + "checkpoint_".repeat(8)
        val summary = "A complete, selectable result for the inventory team. ".repeat(3).trimEnd()
        val reply = answer.copy(text = body, toolCalls = listOf(ReplyToolCall(name, ToolCallState.Completed, body.length, result = summary)))
        open(width = 174.dp, fontScale = 1.8f, initial = Conversation(messages = listOf(reply)))
        val inline = textLayout(summary)
        assertEquals(4, inline.lineCount)
        assertTrue(inline.isLineEllipsized(3))
        screen.onNode(hasText(summary) and hasClickAction()).performClick()
        val sheet = hasAnyAncestor(hasTestTag("turn-action-sheet"))
        for (value in listOf(name, summary)) {
            val node = screen.onNode(sheet and hasText(value), useUnmergedTree = true)
            node.performScrollTo().assertIsDisplayed()
            assertFalse(layoutOf(node).hasVisualOverflow)
        }
        screen.onNodeWithContentDescription("Close details").performClick()
        screen.onNodeWithText(summary).assertIsDisplayed()
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
        screen.onNodeWithContentDescription(label(R.string.turn_close_details)).performClick()
        val topToBottom = listOf(reply.thought, "catalog_lookup", "🙂", "file_search", "Done",
            label(R.string.turn_tool_position_unknown), "archived_lookup")
            .map { screen.onNodeWithText(it).getUnclippedBoundsInRoot() }
        topToBottom.zipWithNext().forEach { (previous, next) -> assertTrue(previous.bottom <= next.top) }
        thought.performClick()
        screen.onNodeWithText(reply.thought).assertDoesNotExist()
        screen.onNodeWithText("archived_lookup").performClick()
        val sheet = hasAnyAncestor(hasTestTag("turn-action-sheet"))
        screen.onNode(sheet and hasText(label(R.string.turn_tool_incomplete)), useUnmergedTree = true).assertIsDisplayed()
        for (absent in listOf(R.string.turn_tool_arguments, R.string.turn_tool_result, R.string.turn_tool_offset_label))
            screen.onNode(sheet and hasText(label(absent)), useUnmergedTree = true).assertDoesNotExist()
        screen.onNodeWithContentDescription(label(R.string.turn_close_details)).performClick()
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
        screen.onNodeWithContentDescription(label(R.string.turn_close_details)).performClick()
        screen.onNodeWithText(label(R.string.turn_tool_before_body)).assertDoesNotExist()
        screen.onNodeWithText("30 tokens").performClick()
        assertUsageRows(R.string.turn_input to "20", R.string.turn_cached to "0", R.string.turn_output to "10",
            R.string.turn_reasoning to "4", R.string.turn_ttft to "12 ms", R.string.turn_first_body to "140 ms",
            R.string.turn_elapsed to "200 ms", R.string.turn_tps to "50.0 tok/s")
    }

    @Test @Config(qualifiers = "en-rUS-w407dp-h915dp-xxhdpi")
    fun shortThinkingCardFitsItsContentAndReversesOrSnapsWithSystemMotion() {
        val reply = answer.copy(thought = "Short thought.")
        open(initial = Conversation(messages = listOf(reply)))
        setStyle(MessageStyle())
        val card = screen.onNodeWithTag("message-thought")
        val header = screen.onNode(hasText(label(R.string.turn_thinking)) and hasClickAction())
        fun height() = card.getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        fun widthOf(node: SemanticsNodeInteraction) = node.getUnclippedBoundsInRoot().let { it.right - it.left }
        val clock = screen.mainClock
        fun toggle() {
            header.performSemanticsAction(SemanticsActions.OnClick)
            clock.advanceTimeByFrame()
            screen.waitForIdle() // Android measures the new content before the first size animation frame.
        }
        fun systemScale(scale: Float) = org.robolectric.util.ReflectionHelpers.callStaticMethod<Any?>(
            android.animation.ValueAnimator::class.java, "setDurationScale",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, scale))
        val oldScale = android.animation.ValueAnimator.getDurationScale()
        val closed = height()
        val width = widthOf(screen.onNodeWithTag("message-timeline"))
        assertTrue(widthOf(card) < width * .8f)
        clock.autoAdvance = false
        try {
            systemScale(1f)
            toggle()
            clock.advanceTimeBy(112)
            val during = height()
            clock.advanceTimeBy(320)
            val expanded = height()
            assertTrue("closed=$closed during=$during expanded=$expanded", during > closed && during < expanded)
            assertTrue("Expanded thought uses the message column", widthOf(card) > width * .8f)
            toggle()
            clock.advanceTimeBy(96)
            assertTrue(height() in (closed + 1f)..(expanded - 1f))
            toggle()
            clock.advanceTimeBy(360)
            assertEquals(expanded, height(), .5f)
            systemScale(0f)
            toggle()
            clock.advanceTimeByFrame()
            assertEquals(closed, height(), .5f)
            toggle()
            clock.advanceTimeByFrame()
            assertEquals(expanded, height(), .5f)
        } finally {
            systemScale(oldScale)
            clock.autoAdvance = true
        }
    }

    @Test @Config(qualifiers = "en-rUS-w407dp-h915dp-xxhdpi")
    fun largeTextChatKeepsSixHeadingLevelsAndTheRecordedSheetScale() {
        val headings = (1..6).map { "Heading $it" }
        val body = headings.mapIndexed { i, name -> "#".repeat(i + 1) + " " + name }.joinToString("\n\n") +
            "\n\nParagraph body\n\n```kotlin\nplain_sample\n```"
        val reply = answer.copy(text = body, toolCalls = listOf(ReplyToolCall("lookup", ToolCallState.Completed, 0,
            arguments = "{}", result = "A sample result.")))
        open(fontScale = 1.5f, initial = Conversation(messages = listOf(reply)))
        fun size(text: String) = textLayout(text).layoutInput.style.fontSize.value
        assertEquals(15.7f, size("Paragraph body"), .01f)
        for ((name, reference) in headings.zip(listOf(22.765f, 19.939f, 17.741f, 15.7f, 14.758f, 13.816f)))
            assertEquals(name, reference, size(name), .002f)
        assertEquals(13f, size("plain_sample"), .01f)
        assertEquals(12f, size("A sample result."), .01f)
        assertEquals(1.5f, textLayout("Paragraph body").layoutInput.density.fontScale, .01f)
        screen.onNode(hasText("lookup") and hasClickAction()).performClick()
        val sheet = hasAnyAncestor(hasTestTag("turn-action-sheet"))
        fun sheetSize(value: String) = layoutOf(screen.onNode(sheet and hasText(value), useUnmergedTree = true)).layoutInput.style.fontSize.value
        assertEquals(15f, sheetSize("lookup"), .01f)
        assertEquals(12f, sheetSize("Arguments"), .01f)
        assertEquals(12f, sheetSize("A sample result."), .01f)
    }

    @Test fun reopenedReplyOpensTheMatchingResultForEachSameNamedCallWithOneTap() = runBlocking {
        val peer = LocalMcpFixture()
        fun rpc(id: Int?, method: String, params: JSONObject = JSONObject()) =
            peer.receive(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        rpc(1, "initialize", JSONObject("""{"protocolVersion":"2025-11-25","clientInfo":{"name":"fixture","version":"1"},"capabilities":{}}"""))
        rpc(null, "notifications/initialized")
        val name = rpc(2, "tools/list")!!.getJSONObject("result").getJSONArray("tools").getJSONObject(0).getString("name")
        val inputs = listOf(JSONObject(), JSONObject().put("mode", "unexpected"))
        val outputs = inputs.mapIndexed { i, args -> rpc(i + 3, "tools/call", JSONObject().put("name", name).put("arguments", args))!! }
        val first = outputs[0].getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        val second = outputs[1].getJSONObject("error").getString("message")
        val repo = object : GenerationRepository {
            override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String) = flow {
                val stream = ResponsesEventAccumulator(request)
                suspend fun event(type: String, data: JSONObject) { stream.accept(data.toString(), type).deliver { emit(it) } }
                suspend fun item(index: Int, stage: String, call: JSONObject) =
                    event("response.output_item.$stage", JSONObject().put("output_index", index).put("item", call))
                for (i in inputs.indices) item(i, "added", JSONObject().put("id", "item-$i").put("type", "mcp_call")
                    .put("name", name).put("arguments", inputs[i].toString()).put("status", "in_progress"))
                item(1, "done", JSONObject().put("id", "item-1").put("type", "mcp_call").put("error", second).put("status", "failed"))
                item(0, "done", JSONObject().put("id", "item-0").put("type", "mcp_call").put("output", first).put("status", "completed"))
                event("response.output_text.done", JSONObject().put("output_index", 2).put("text", "Two calls recorded."))
                event("response.completed", JSONObject().put("response", JSONObject().put("status", "completed")))
            }
        }
        val session = ConversationSession(this, repo, {})
        session.setDraft("Check two local fixture requests.")
        session.send("Fixture", emptyMap(), null, null, remoteModel = RemoteModel("fixture", "chat"))
        val saved = session.state.first { !it.isGenerating }.current
        val vm = open(initial = saved)
        val steps = screen.onAllNodes(hasText(name) and hasClickAction())
        steps.assertCountEquals(2)
        assertTrue(steps[0].getUnclippedBoundsInRoot().top < steps[1].getUnclippedBoundsInRoot().top)
        val sheet = hasAnyAncestor(hasTestTag("turn-action-sheet"))
        for (i in inputs.indices) {
            screen.runOnIdle { vm.openConversation(recorded.id) }
            screen.onNodeWithText("Answer").assertIsDisplayed()
            screen.runOnIdle { vm.openConversation(saved.id) }
            steps[i].performClick()
            val expected = if (i == 0) first else second
            for (text in listOf(name, inputs[i].toString(2), expected,
                label(if (i == 0) R.string.turn_tool_done else R.string.turn_tool_failed))) {
                screen.onNode(sheet and hasText(text), useUnmergedTree = true).assertIsDisplayed()
            }
            screen.onNode(sheet and hasText(if (i == 0) second else first), useUnmergedTree = true).assertDoesNotExist()
            screen.onNodeWithContentDescription(label(R.string.turn_close_details)).performClick()
        }
        assertEquals(saved.messages.last().toolCalls, vm.state.value.current.messages.last().toolCalls)
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
