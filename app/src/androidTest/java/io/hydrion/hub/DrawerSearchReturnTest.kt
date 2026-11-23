package io.hydrion.hub

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.ConversationSession
import io.hydrion.hub.model.Draft
import io.hydrion.hub.model.FakeGenerationRepository
import io.hydrion.hub.model.LocalAssistant
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Role
import io.hydrion.hub.model.SavedWorkspace
import io.hydrion.hub.ui.sidebar.ConversationDrawer
import io.hydrion.hub.ui.sidebar.DrawerActions
import io.hydrion.hub.ui.sidebar.WorkspaceDrawer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerSearchReturnTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Test fun returningFromAnotherAssistantKeepsTheSelectedResultInViewAndTheOriginalDraft() {
        val owner = LocalAssistant.Default
        val guest = LocalAssistant("guest", "Guest")
        val history = List(64) { index ->
            Conversation(id = "thread-$index", title = "Record $index", updatedAt = 1000L - index,
                assistantId = if (index % 2 == 0) guest.id else owner.id,
                messages = listOf(Message(role = Role.User, text = "Orchid map")))
        }
        val target = history[48]
        val sourceDraft = Draft("A question in progress")
        val targetDraft = Draft("A reply in progress")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = ConversationSession(scope, FakeGenerationRepository(), persist = {})
        val actions = DrawerActions({ false }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {},
            { _, _ -> false }, { _, _ -> false }, { false }, {})

        fun control(label: Int) = screen.onNodeWithContentDescription(screen.activity.getString(label))
        fun row(chat: Conversation) = hasClickAction() and hasText(chat.title)
        fun awaitRow(chat: Conversation) {
            screen.waitUntil(timeoutMillis = 5_000) { screen.onAllNodes(row(chat)).fetchSemanticsNodes().size == 1 }
            screen.onNode(row(chat)).assertIsDisplayed()
        }
        val field = screen.onNode(hasSetTextAction() and hasContentDescription(screen.activity.getString(R.string.drawer_global_search)))
        try {
            val scratchId = screen.runOnIdle {
                session.restore(SavedWorkspace(history = history, assistants = listOf(owner, guest),
                    drafts = mapOf(target.id to targetDraft)))
                session.setDraft(sourceDraft.text)
                session.state.value.current.id
            }
            screen.setContent {
                val state by session.state.collectAsState()
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val navigation = rememberCoroutineScope()
                MaterialTheme {
                    WorkspaceDrawer(state.current.mode, drawer, onClose = { navigation.launch { drawer.close() } },
                        drawerContent = { width, visible ->
                            ConversationDrawer(width, MaterialTheme.colorScheme.surface,
                                allHistory = { state.history }, drawerVisible = visible,
                                currentId = state.current.id, assistants = state.assistants,
                                assistantId = state.current.assistantId, assistantsReady = true,
                                onSelectAssistant = { id -> navigation.launch { drawer.close(); session.selectAssistant(id) } },
                                onRenameAssistant = { _, _ -> false }, onNew = {}, onOpen = {},
                                onSettings = {}, onRename = {}, onPin = {}, onDelete = {}, actions = actions,
                                onHit = { hit -> navigation.launch { drawer.close(); session.openConversation(hit.id) } },
                                onAssistantSettings = {})
                        }) {
                        Button(onClick = { navigation.launch { drawer.open() } }) { Text("Show drawer") }
                    }
                }
            }

            screen.onNodeWithText("Show drawer").performClick()
            val topics = screen.onNode(hasSetTextAction() and hasContentDescription(screen.activity.getString(R.string.drawer_search)))
            topics.assertIsDisplayed()
            screen.onNode(row(history[1])).assertIsDisplayed()
            val switch = control(R.string.drawer_switch_global)
            assertFalse(topics.fetchSemanticsNode().boundsInRoot.contains(switch.fetchSemanticsNode().boundsInRoot.center))
            switch.performTouchInput { click(center) }
            field.assertIsDisplayed()
            screen.onNodeWithText(screen.activity.getString(R.string.drawer_global_search), useUnmergedTree = true).assertIsDisplayed()
            control(R.string.drawer_switch_topics).assertIsDisplayed()
            screen.onNodeWithText(screen.activity.getString(R.string.drawer_global_submit_hint)).assertIsDisplayed()
            topics.assertDoesNotExist()
            screen.onNode(row(history[1])).assertDoesNotExist()
            field.performClick().performTextReplacement("Orchid")
            field.performImeAction()
            awaitRow(history.first())
            screen.onNodeWithTag("drawer-conversation-list").performScrollToNode(row(target))
            screen.onNode(row(target)).assertIsDisplayed().performClick()
            screen.waitUntil(timeoutMillis = 5_000) { session.state.value.current.id == target.id }
            screen.runOnIdle {
                assertEquals(guest.id, session.state.value.current.assistantId)
                assertEquals(targetDraft, session.state.value.draft)
            }

            screen.onNodeWithText("Show drawer").performClick()
            field.assertTextContains("Orchid")
            awaitRow(target)
            screen.onNode(row(target)).assertIsSelected()

            control(R.string.drawer_clear_search).performClick()
            field.performClick().performTextReplacement("map")
            field.performImeAction()
            awaitRow(history.first())
            screen.onNode(row(history.first())).assertIsNotSelected()
            control(R.string.drawer_switch_topics).performClick()
            control(R.string.drawer_assistants).performClick()
            screen.onNodeWithTag("assistant-row-default").performClick()
            screen.waitUntil(timeoutMillis = 5_000) { session.state.value.current.id == scratchId }
            screen.runOnIdle {
                assertEquals(owner.id, session.state.value.current.assistantId)
                assertEquals(sourceDraft, session.state.value.draft)
            }
        } finally {
            scope.cancel()
        }
    }
}
