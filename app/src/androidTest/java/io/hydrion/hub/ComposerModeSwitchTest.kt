package io.hydrion.hub

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.Role
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerModeSwitchTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val editable = hasContentDescription("消息输入框") and hasSetTextAction()
    private val editor get() = screen.onNode(editable)
    private val vm get() = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
    private fun tab(mode: Mode) = hasText(mode.name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun field() = screen.onAllNodes(editable)
        .fetchSemanticsNodes().singleOrNull()

    // Geometry distinguishes a visible sheet from one that is still mounted offscreen.
    private fun sheetExposure(): Pair<Float, Int>? {
        val sheet = screen.onAllNodesWithTag("drawer-sheet", useUnmergedTree = true).fetchSemanticsNodes().singleOrNull() ?: return null
        val viewportWidth = screen.runOnUiThread { screen.activity.window.decorView.width }
        val left = sheet.positionInRoot.x
        val width = sheet.size.width
        return (minOf(viewportWidth.toFloat(), left + width) - maxOf(0f, left)).coerceAtLeast(0f) to width
    }

    private fun imeInsets() = screen.runOnUiThread {
        val view = screen.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(view)
        val ime = WindowInsetsCompat.Type.ime()
        val nav = WindowInsetsCompat.Type.navigationBars()
        Triple(insets?.isVisible(ime) == true, insets?.getInsets(ime)?.bottom ?: 0,
            insets?.getInsets(nav)?.bottom ?: 0)
    }

    private fun imeVisible(): Boolean {
        val (visible, bottom, nav) = imeInsets()
        return visible && bottom > nav
    }

    private fun imeHidden(): Boolean {
        val (visible, bottom, nav) = imeInsets()
        return !visible && bottom <= nav
    }

    private fun await(stage: String, timeoutMs: Long = 10_000, ready: () -> Boolean) {
        try {
            screen.waitUntil(timeoutMs, ready)
        } catch (failure: ComposeTimeoutException) {
            val state = vm.state.value
            val cfg = field()?.config
            throw AssertionError("Timed out awaiting $stage (mode=${state.current.mode}, " +
                "focus=${cfg?.getOrNull(SemanticsProperties.Focused)}, selection=${cfg?.getOrNull(SemanticsProperties.TextSelectionRange)}, " +
                "IME visibility/bottom/nav=${imeInsets()}, sheet px=${sheetExposure()})", failure)
        }
    }

    private fun selectTab(mode: Mode) {
        val source = vm.state.value.current
        screen.onNode(tab(mode)).performTouchInput { click(center) }
        await("tab ${source.mode}->$mode") {
            val current = vm.state.value.current
            current.id == source.id && current.mode == mode &&
                screen.onAllNodes(tab(mode) and isSelected()).fetchSemanticsNodes().size == 1
        }
        screen.onNode(tab(mode)).assertIsSelected()
    }

    private fun focusAndShowIme() {
        // A route change can expose the editor before the IME has finished moving its hit target.
        var previous = Rect.Zero
        var since = SystemClock.uptimeMillis()
        await("editable field stationary before touch") {
            val box = field()?.boundsInRoot ?: return@await false
            val now = SystemClock.uptimeMillis()
            if (box != previous) {
                previous = box
                since = now
            }
            !box.isEmpty && now - since >= 48
        }
        editor.performTouchInput { click(center) }
        await("editor touch -> focused and IME visible", 15_000) {
            field()?.config?.getOrNull(SemanticsProperties.Focused) == true && imeVisible()
        }
        editor.assertIsFocused()
    }

    private fun hideIme() {
        screen.runOnUiThread {
            val window = screen.activity.window
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        await("IME closed", 15_000, ::imeHidden)
    }

    private fun assertToolbar(mode: Mode) {
        screen.onNodeWithContentDescription("打开工具箱").assertIsDisplayed()
        val dictate = screen.onNodeWithTag("chat-composer-dictate")
        val terminal = screen.onNodeWithContentDescription("工作区终端")
        if (mode == Mode.Chat) {
            dictate.assertIsDisplayed()
            terminal.assertDoesNotExist()
        } else {
            dictate.assertDoesNotExist()
            terminal.assertIsDisplayed()
        }
    }

    @Test fun emptyHeaderSwitchesBothWaysRepeatedlyWithoutCreatingHistory() {
        val store = vm
        val before = screen.runOnIdle { store.state.value }
        check(!before.modeLocked && before.draft.text.isEmpty())
        try {
            repeat(3) { round ->
                for (mode in listOf(Mode.Image, Mode.Chat)) {
                    if (round > 0) {
                        focusAndShowIme()
                        assertToolbar(if (mode == Mode.Image) Mode.Chat else Mode.Image)
                    }
                    selectTab(mode)
                    val hint = if (mode == Mode.Chat) "Chat with AI" else "描述想生成的图片"
                    screen.onNodeWithText(hint, useUnmergedTree = true).assertIsDisplayed()
                    screen.runOnIdle {
                        val current = store.state.value
                        assertEquals(mode, current.current.mode)
                        assertEquals(before.current.id, current.current.id)
                        assertEquals(before.draft, current.draft)
                        assertTrue(current.current.messages.isEmpty())
                        assertFalse(current.modeLocked)
                    }
                }
            }
        } finally {
            screen.runOnIdle { store.setMode(before.current.mode) }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun headerSwitchesFromFocusedEditorAndParkedToolbarWithoutLosingDraft() {
        val store = vm
        val before = screen.runOnIdle { store.state.value }
        check(!before.modeLocked)
        val text = "local draft\nsecond line"
        try {
            focusAndShowIme()
            editor.performTextReplacement(text)
            val selection = TextRange(6, 11)
            editor.performTextInputSelection(selection)
            for (mode in listOf(Mode.Image, Mode.Chat)) {
                selectTab(mode)
                await("tab preserved editing/selection and IME in $mode", 15_000) {
                    val current = store.state.value
                    val cfg = field()?.config
                    current.current.id == before.current.id && current.draft.text == text &&
                        cfg?.getOrNull(SemanticsProperties.Focused) == true &&
                        cfg.getOrNull(SemanticsProperties.TextSelectionRange) == selection &&
                        cfg.getOrNull(SemanticsProperties.EditableText)?.text == text && imeVisible()
                }
                editor.assertIsFocused().assertTextEquals(text)
                assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
                assertTrue("A mode change keeps the active IME", imeVisible())
                screen.runOnIdle { assertEquals(mode, store.state.value.current.mode) }
            }

            hideIme()
            assertToolbar(Mode.Chat)
            for (mode in listOf(Mode.Image, Mode.Chat)) {
                selectTab(mode)
                assertToolbar(mode)
                editor.assertTextEquals(text)
                assertTrue("Switching parked tools keeps the keyboard down", imeHidden())
                screen.runOnIdle {
                    assertEquals(before.current.id, store.state.value.current.id)
                    assertEquals(text, store.state.value.draft.text)
                }
            }
        } finally {
            screen.runOnIdle { store.setDraft(before.draft.text); store.setMode(before.current.mode) }
        }
    }

    @Test fun drawerSwitchesBetweenEmptyHistoriesAndPreservesEachDraftAndModeLock() {
        val store = vm
        val suffix = UUID.randomUUID().toString().take(8)
        val created = mutableListOf<String>()
        fun seed(mode: Mode): String = screen.runOnIdle {
            store.newConversation()
            store.setMode(mode)
            val title = "${mode.name} $suffix"
            store.setDraft(title)
            store.send("Hydrion", remoteModel = null)
            store.stop()
            val id = store.state.value.current.id
            created += id
            store.setDraft("parked $title")
            id
        }
        try {
            val chat = seed(Mode.Chat)
            val image = seed(Mode.Image)
            // A sent history has no tabs. Deleting the last turn leaves an empty, still-locked history.
            screen.onNode(tab(Mode.Chat)).assertDoesNotExist()
            screen.runOnIdle {
                store.setMode(Mode.Chat)
                assertEquals(Mode.Image, store.state.value.current.mode)
                for (id in listOf(chat, image)) {
                    store.openConversation(id)
                    val prompt = store.state.value.current.messages.single { it.role == Role.User }
                    store.deleteMessage(prompt.id)
                    assertTrue(store.state.value.current.messages.isEmpty())
                    assertTrue(store.state.value.modeLocked)
                }
            }
            repeat(2) { round ->
                for ((mode, id) in listOf(Mode.Chat to chat, Mode.Image to image)) {
                    if (round == 0) focusAndShowIme() else hideIme()
                    val body = screen.onNodeWithTag("drawer-workspace", useUnmergedTree = true)
                    val closedX = body.fetchSemanticsNode().positionInRoot.x
                    val sourceMode = screen.runOnIdle { store.state.value.current.mode }
                    val title = "${mode.name} $suffix"
                    val row = hasText(title) and hasContentDescription(mode.name) and hasClickAction()
                    screen.onNodeWithContentDescription("打开侧栏").performTouchInput { click(center) }
                    await("drawer fully open $sourceMode -> $mode row ${id.take(8)}") {
                        val (visible, width) = sheetExposure() ?: return@await false
                        width > 0 && visible >= width - 1f && screen.onNode(row).isDisplayed()
                    }
                    if (sourceMode == Mode.Image) {
                        assertEquals("Image's modal drawer leaves the editor in place", closedX,
                            body.fetchSemanticsNode().positionInRoot.x, 1f)
                    }
                    screen.onNode(row).performTouchInput { click(center) }
                    await("drawer tap selects ${id.take(8)} $mode and its draft") {
                        val current = store.state.value
                        current.current.id == id && current.current.mode == mode && current.draft.text == "parked $title"
                    }
                    await("drawer closed and destination editor accessible") {
                        (sheetExposure()?.first ?: 0f) <= 1f &&
                            field()?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "parked $title"
                    }
                    editor.assertTextEquals("parked $title")
                    assertToolbar(mode)
                    screen.onNode(tab(Mode.Chat)).assertDoesNotExist()
                    screen.onNode(tab(Mode.Image)).assertDoesNotExist()
                    screen.runOnIdle {
                        val current = store.state.value
                        assertEquals(id, current.current.id)
                        assertEquals(mode, current.current.mode)
                        assertTrue(current.current.messages.isEmpty())
                        assertTrue(current.modeLocked)
                    }
                }
            }
        } finally {
            screen.runOnIdle { created.forEach(store::deleteConversation) }
        }
    }
}
