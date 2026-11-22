package io.hydrion.hub

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import io.hydrion.hub.model.LocalAssistant
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.ui.sidebar.DrawerActions
import io.hydrion.hub.ui.sidebar.ConversationDrawer
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushDrawerTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val actions = DrawerActions({ false }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, { _, _ -> false }, { _, _ -> false }, { false }, {})

    private fun node(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    @Test fun headerAndFooterStayOnSingleRowsInRegularAndNarrowDrawers() {
        val width = mutableStateOf(320.dp)
        screen.runOnUiThread { screen.activity.setContent {
            MaterialTheme {
                ConversationDrawer(width.value, MaterialTheme.colorScheme.surface, emptyList(), "",
                    assistants = listOf(LocalAssistant.Default), assistantId = LocalAssistant.Default.id,
                    assistantsReady = true, onSelectAssistant = {}, onRenameAssistant = { _, _ -> false },
                    onNew = {}, onOpen = {}, onSettings = {}, onRename = {}, onPin = {}, onDelete = {}, actions = actions, onHit = {}, onAssistantSettings = {})
            }
        } }
        val search = screen.onNode(hasContentDescription(screen.activity.getString(R.string.drawer_search)) and hasSetTextAction())
        val history = screen.onNodeWithContentDescription(screen.activity.getString(R.string.drawer_history))
        val density = screen.activity.resources.displayMetrics.density
        val rtl = screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        for (drawerWidth in listOf(320.dp, 196.dp)) {
            screen.runOnIdle { width.value = drawerWidth }
            val sheet = node("drawer-sheet").boundsInRoot
            val chat = node("drawer-new-chat").boundsInRoot
            val avatar = node("drawer-settings").boundsInRoot
            assertEquals(search.fetchSemanticsNode().boundsInRoot.center.y,
                history.fetchSemanticsNode().boundsInRoot.center.y, 1f)
            assertEquals(chat.center.y, avatar.center.y, 1f)
            val gap = if (rtl) chat.left - avatar.right else avatar.left - chat.right
            assertTrue(gap >= 8f * density - 1f)
            assertEquals(48f * density, avatar.width, 1f)
            assertTrue(chat.width >= 48f * density - 1f)
            if (drawerWidth == 320.dp) {
                assertTrue(chat.width >= 113f * density - 1f)
                assertEquals(32f * density, if (rtl) sheet.right - chat.right else chat.left - sheet.left, 1f)
                assertEquals(25f * density, if (rtl) avatar.left - sheet.left else sheet.right - avatar.right, 1f)
            }
        }
    }

    @Test fun swipingTheEmptyFieldSwitchesScopeWithoutClosingTheDrawerAndHistoryReturnsToIt() {
        fun field(res: Int) = screen.onNode(hasContentDescription(screen.activity.getString(res)) and hasSetTextAction())
        screen.onNodeWithContentDescription("打开侧栏").performClick()
        val topics = field(R.string.drawer_search)
        val global = field(R.string.drawer_global_search)
        topics.performTouchInput { swipeLeft() }
        global.assertIsDisplayed().performTouchInput { swipeRight() }
        topics.assertIsDisplayed().performClick().performTextReplacement("query")
        topics.performTouchInput { swipeLeft() }
        topics.assertTextContains("query")
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.drawer_history)).performClick()
        screen.onNodeWithTag("drawer-history-page").assertIsDisplayed()
        screen.onNode(hasContentDescription(screen.activity.getString(R.string.settings_back)) and hasAnyAncestor(isDialog())).performClick()
        topics.assertIsDisplayed().assertTextContains("query")
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.drawer_assistants)).performClick()
        screen.onNodeWithTag("assistant-row-default").assertIsDisplayed()
    }

    @Test fun selectingAnInlineAssistantCollapsesThePickerAndScopesTheTopics() {
        val alt = LocalAssistant("writing", "Writing")
        val chats = listOf(Conversation(title = "First topic"), Conversation(title = "Second topic", assistantId = alt.id))
        screen.runOnUiThread { screen.activity.setContent {
            var current by remember { mutableStateOf(LocalAssistant.Default.id) }
            MaterialTheme {
                ConversationDrawer(300.dp, MaterialTheme.colorScheme.surface, chats, "",
                    assistants = listOf(LocalAssistant.Default, alt), assistantId = current,
                    onSelectAssistant = { current = it }, onRenameAssistant = { _, _ -> false }, assistantsReady = true,
                    onNew = {}, onOpen = {}, onSettings = {}, onRename = {}, onPin = {}, onDelete = {}, actions = actions, onHit = {}, onAssistantSettings = {})
            }
        } }
        val disclosure = screen.onNodeWithContentDescription(screen.activity.getString(R.string.drawer_assistants))
        screen.onNodeWithText("First topic").assertIsDisplayed()
        screen.onNodeWithText("Second topic").assertDoesNotExist()
        disclosure.performClick()
        screen.onNodeWithTag("assistant-row-default").assertIsSelected()
        screen.onNodeWithTag("assistant-row-writing").performClick()
        screen.onNodeWithTag("assistant-row-writing").assertDoesNotExist()
        screen.onNodeWithText("Writing").assertIsDisplayed()
        screen.onNodeWithText("Second topic").assertIsDisplayed()
        screen.onNodeWithText("First topic").assertDoesNotExist()
        disclosure.performClick()
        screen.onNodeWithTag("assistant-row-writing").assertIsSelected()
        screen.onNodeWithTag("drawer-settings").assertIsDisplayed()
        screen.onNodeWithTag("drawer-new-chat").assertIsDisplayed()
    }

    @Test fun longPressOnInactiveAssistantOpensItsSettingsWithoutSelectingIt() {
        val secondary = LocalAssistant("other-record", "Other assistant")
        val opened = mutableListOf<String>()
        val selected = mutableListOf<String>()
        screen.runOnUiThread { screen.activity.setContent { MaterialTheme {
            ConversationDrawer(320.dp, MaterialTheme.colorScheme.surface, emptyList(), "",
                listOf(LocalAssistant.Default, secondary), LocalAssistant.Default.id, { selected += it }, { _, _ -> false }, true,
                {}, {}, {}, {}, {}, {}, actions, {}, { opened += it })
        } } }
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.drawer_assistants)).performClick()
        screen.onNodeWithTag("assistant-row-other-record").performTouchInput { longClick() }
        screen.onNodeWithText(screen.activity.getString(R.string.assistant_detail)).performClick()
        screen.runOnIdle {
            assertEquals(listOf(secondary.id), opened)
            assertTrue(selected.isEmpty())
        }
    }

    @Test fun longPressSeedsSelectionAndCancelLeavesHistoryUntouched() {
        val owner = LocalAssistant.Default
        val ids = listOf(Conversation(title = "Alpha"), Conversation(title = "Beta"))
        val changes = mutableListOf<Set<String>>()
        val f = DrawerActions({ it == ids[0].id }, { selected, _ -> changes += selected },
            { _, _ -> }, { _, _ -> }, { changes += it }, { _, _ -> false }, { _, _ -> false }, { false }, {})
        screen.runOnUiThread { screen.activity.setContent { MaterialTheme {
            ConversationDrawer(300.dp, MaterialTheme.colorScheme.surface, ids, ids[0].id,
                listOf(owner), owner.id, {}, { _, _ -> false }, true, {}, {}, {}, {}, {}, {}, f, {}, {})
        } } }
        fun label(id: Int) = screen.activity.getString(id)
        screen.onNodeWithText("Alpha").performTouchInput { longClick() }
        screen.onNodeWithText(label(R.string.drawer_select)).performTouchInput { click() }
        screen.onNodeWithText("Beta").performTouchInput { click() }
        screen.onNodeWithText(label(R.string.drawer_pin)).performTouchInput { click() }
        screen.runOnIdle { assertEquals(listOf(ids.map { it.id }.toSet()), changes) }
        screen.onNodeWithText(label(R.string.drawer_delete)).performTouchInput { click() }
        screen.onNodeWithText(label(R.string.settings_cancel)).performTouchInput { click() }
        screen.onNodeWithContentDescription(label(R.string.settings_cancel)).performTouchInput { click() }
        screen.onNodeWithTag("drawer-new-chat").assertIsDisplayed()
        screen.runOnIdle { assertEquals(1, changes.size) }
    }

    @Test fun dragMovesTheFullWidthChatAndOnlyTheExposedStripDismissesTheDrawer() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val draft = "尚未发送的草稿"
        val (conversation, reply) = screen.runOnIdle {
            vm.newConversation()
            vm.setMode(Mode.Chat)
            vm.setDraft("侧栏位置")
            vm.send("Hydrion")
            vm.stop()
            val reply = vm.state.value.current.messages.last().id
            vm.editMessage(reply, (1..70).joinToString("\n") { "第 $it 行" })
            vm.setDraft(draft)
            vm.state.value.current.id to reply
        }
        try {
            val timeline = screen.onNodeWithTag("message-timeline", useUnmergedTree = true)
            val root = screen.onRoot()
            timeline.performScrollToIndex(0)
            val scrollBy = timeline.fetchSemanticsNode().size.height * .5f
            timeline.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scrollBy) }
            val replyTop = node("message-turn-$reply").positionInRoot.y
            val chat = node("drawer-workspace")
            val origin = chat.positionInRoot
            val width = chat.size.width
            val rtl = screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val sign = if (rtl) -1f else 1f
            // Sample solid menu chrome away from the glyph, border and system bars.
            val menu = node("header-drawer-capsule").boundsInRoot
            val sampleX = menu.center.x
            val sampleY = (menu.top + menu.height * .77f).roundToInt()
            val originalPixel = root.captureToImage().asAndroidBitmap().getPixel(sampleX.roundToInt(), sampleY)

            // Hold the finger still so both sides can be sampled during the very same drag.
            root.performTouchInput {
                down(Offset(width * (if (rtl) .96f else .04f), center.y))
                val step = minOf(width * .12f, 50.dp.toPx()) * sign
                moveBy(Offset(step, 0f))
                moveBy(Offset(step, 0f))
            }
            try {
                val sheet = node("drawer-sheet")
                val middle = node("drawer-workspace")
                val distance = sign * (middle.positionInRoot.x - origin.x)
                assertTrue("Drag should expose part of the sheet", distance > 1 && distance < sheet.size.width - 1)
                val boundary = if (rtl) sheet.positionInRoot.x else sheet.positionInRoot.x + sheet.size.width
                val chatEdge = middle.positionInRoot.x + if (rtl) width else 0
                assertEquals("No gap or overlap", boundary, chatEdge, 1f)
                assertEquals(width, middle.size.width)
                assertEquals(origin.y, middle.positionInRoot.y, 1f)
            } finally {
                root.performTouchInput {
                    moveTo(Offset(width * (if (rtl) .08f else .92f), center.y))
                    up()
                }
            }

            screen.onNodeWithTag("drawer-settings").assertIsDisplayed()
            val sheet = node("drawer-sheet")
            val open = node("drawer-workspace")
            val openX = open.positionInRoot.x
            assertEquals(width, open.size.width)
            assertEquals(sheet.size.width.toFloat(), sign * (openX - origin.x), 1f)
            val cap = 348f * screen.activity.resources.displayMetrics.density
            assertEquals(minOf(width * .80f, cap), sheet.size.width.toFloat(), 1f)
            val closeLabel = screen.activity.getString(androidx.compose.ui.R.string.close_drawer)
            screen.onNode(hasContentDescription(closeLabel) and hasClickAction()).assertIsDisplayed()
            screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction()).assertDoesNotExist()
            val translatedX = (sampleX + openX - origin.x).roundToInt()
            if (translatedX in 0 until width) {
                val tinted = root.captureToImage().asAndroidBitmap().getPixel(translatedX, sampleY)
                val expected = androidx.core.graphics.ColorUtils.blendARGB(originalPixel, 0xFFF5F5F5.toInt(), .12f)
                for (shift in listOf(0, 8, 16)) {
                    assertEquals((expected ushr shift and 255).toFloat(), (tinted ushr shift and 255).toFloat(), 2f)
                }
            }
            screen.onNodeWithText("HydrionHub").performTouchInput { click(center) }
            assertEquals(openX, node("drawer-workspace").positionInRoot.x, 1f)

            root.performTouchInput {
                val strip = (width - sheet.size.width) / 2f
                click(Offset(if (rtl) strip else width - strip, center.y))
            }
            screen.onNodeWithContentDescription("打开侧栏").assertIsDisplayed()
            assertEquals(origin, node("drawer-workspace").positionInRoot)
            assertEquals(replyTop, node("message-turn-$reply").positionInRoot.y, 1f)
            screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction()).assertTextContains(draft)
            screen.runOnIdle { assertEquals(conversation, vm.state.value.current.id) }
        } finally {
            screen.runOnIdle { vm.deleteConversation(conversation) }
        }
    }
}
