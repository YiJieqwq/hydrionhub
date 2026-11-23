package io.hydrion.hub

import androidx.activity.ComponentActivity
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
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
import io.hydrion.hub.ui.sidebar.DrawerHistoryPage
import io.hydrion.hub.ui.sidebar.WorkspaceDrawer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchivedHistoryPageTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    private val owner = LocalAssistant.Default
    private val guest = LocalAssistant("guest", "Research")
    private val target = Conversation(id = "target", title = "Release notes", pinned = true,
        messages = listOf(Message(id = "question", role = Role.User, text = "Synthetic question")))
    private val active = Conversation(id = "active", title = "Current conversation")
    private val namesake = target.copy(id = "other", assistantId = guest.id, archived = true)
    private val unrelated = Conversation(id = "unrelated", title = "Schedule", archived = true)
    private val draft = Draft("Continue the release notes")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = ConversationSession(scope, FakeGenerationRepository(), persist = {})
    private val changes = mutableListOf<Pair<String, Boolean>>()
    private val clearRequests = mutableListOf<Set<String>>()

    private val actions = DrawerActions(hasDraft = session::hasDraft, pin = session::setPinned,
        move = session::moveConversations, duplicate = session::duplicateConversation,
        delete = { clearRequests += it }, copyAssistant = session::copyAssistant,
        groupAssistant = session::groupAssistant, canRemoveAssistant = session::canRemoveAssistant,
        removeAssistant = session::removeEmptyAssistant, setArchived = { id, archived ->
            changes += id to archived
            session.setArchived(id, archived)
        })

    @After fun releaseScope() { scope.cancel() }

    private fun showHistory() {
        screen.runOnIdle {
            session.restore(SavedWorkspace(history = listOf(active, target, namesake, unrelated),
                drafts = mapOf(target.id to draft), assistants = listOf(owner, guest),
                lastThreads = mapOf(owner.id to active.id)))
        }
        screen.setContent {
            val state by session.state.collectAsState()
            MaterialTheme {
                DrawerHistoryPage(history = state.history, currentId = state.current.id, assistant = owner,
                    multiple = true, actions = actions, onBack = {}, onOpen = { error("Unexpected chat navigation") },
                    onRename = {}, onPin = session::togglePin, assistants = state.assistants)
            }
        }
    }

    private fun label(id: Int) = screen.activity.getString(id)
    private fun row(chat: Conversation) = screen.onNodeWithTag("history-row-" + chat.id)
    private fun control(id: Int) = screen.onNodeWithContentDescription(label(id))
    private fun button(id: Int) = screen.onNode(hasText(label(id)) and hasClickAction())
    private fun confirm() = screen.onNodeWithTag("history-confirm-archive-action").performClick()
    private fun filterArchived() = screen.onNodeWithTag("history-archive-filter").performClick()

    private fun requestArchive() {
        row(target).performTouchInput { longClick(center) }
        button(R.string.history_archive).performClick()
    }

    @Test fun archiveAndRestoreTheSameChatAcrossSearchAndAssistantScopes() {
        showHistory()
        requestArchive()
        button(R.string.settings_cancel).performClick()
        row(target).assertIsDisplayed()
        screen.runOnIdle { assertTrue(changes.isEmpty()) }

        requestArchive()
        confirm()
        row(target).assertDoesNotExist()
        row(active).assertIsDisplayed()
        filterArchived()
        row(target).assertIsDisplayed()
        row(namesake).assertDoesNotExist()
        row(unrelated).assertIsDisplayed()

        control(R.string.drawer_search).performClick()
        val field = screen.onNode(hasSetTextAction())
        field.performTextReplacement("release")
        row(unrelated).assertDoesNotExist()
        control(R.string.drawer_all_owners).performClick()
        row(target).assertIsDisplayed()
        row(namesake).assertIsDisplayed()
        screen.onNodeWithText(screen.activity.getString(R.string.history_archive_owner, guest.name)).assertIsDisplayed()

        screen.onNodeWithTag("history-restore-" + target.id).performClick()
        button(R.string.settings_cancel).performClick()
        row(target).assertIsDisplayed()
        screen.runOnIdle { assertEquals(listOf(target.id to true), changes) }

        row(target).performClick()
        confirm()
        field.assertTextContains("release")
        row(target).assertDoesNotExist()
        row(namesake).assertIsDisplayed()
        filterArchived()
        row(target).assertIsDisplayed()
        row(namesake).assertDoesNotExist()
        row(active).assertDoesNotExist()
        screen.runOnIdle {
            assertEquals(listOf(target.id to true, target.id to false), changes)
            assertEquals(target, session.state.value.history.single { it.id == target.id })
            assertEquals(namesake, session.state.value.history.single { it.id == namesake.id })
            assertEquals(draft, session.snapshot().drafts[target.id])
            assertTrue(clearRequests.isEmpty())
        }
    }

    @Test fun archivingCurrentChatKeepsMatchingRowsInTheOpenHistoryPageAfterTheDrawerCloses() {
        val next = Conversation(id = "next", title = "Release plan", updatedAt = 30)
        val guestMatch = Conversation(id = "guest-match", title = "Release review", assistantId = guest.id, updatedAt = 20)
        val other = Conversation(id = "schedule", title = "Schedule", updatedAt = 10)
        screen.runOnIdle {
            session.restore(SavedWorkspace(history = listOf(target, next, guestMatch, other),
                assistants = listOf(owner, guest), lastThreads = mapOf(owner.id to target.id)))
            assertEquals(target.id, session.state.value.current.id)
        }
        lateinit var background: DrawerState
        screen.setContent {
            val state by session.state.collectAsState()
            val drawer = rememberDrawerState(DrawerValue.Open)
            val navigation = rememberCoroutineScope()
            background = drawer
            LaunchedEffect(state.current.id) {
                if (state.current.id != target.id) drawer.close()
            }
            MaterialTheme {
                WorkspaceDrawer(state.current.mode, drawer, onClose = { navigation.launch { drawer.close() } },
                    drawerContent = { width, visible ->
                        ConversationDrawer(width, MaterialTheme.colorScheme.surface, { state.history }, state.current.id,
                            state.assistants, state.current.assistantId, {}, { _, _ -> false }, true,
                            onNew = {}, onOpen = {}, onSettings = {}, onRename = {}, onPin = session::togglePin,
                            onDelete = {}, actions = actions, onHit = {}, onAssistantSettings = {}, drawerVisible = visible)
                    }) {}
            }
        }

        control(R.string.drawer_history).performClick()
        val inPage = hasAnyAncestor(hasTestTag("drawer-history-page"))
        screen.onNode(inPage and hasContentDescription(label(R.string.drawer_search))).performClick()
        val field = screen.onNode(inPage and hasSetTextAction())
        field.performTextReplacement("Release")
        control(R.string.drawer_all_owners).performClick()
        row(other).assertDoesNotExist()
        requestArchive()
        confirm()

        screen.runOnIdle {
            assertEquals(next.id, session.state.value.current.id)
            assertEquals(DrawerValue.Closed, background.currentValue)
            assertEquals(listOf(target.id to true), changes)
        }
        screen.onNodeWithTag("drawer-history-page").assertIsDisplayed()
        field.assertTextContains("Release")
        screen.onNode(inPage and hasText(label(R.string.drawer_no_matches))).assertDoesNotExist()
        row(target).assertDoesNotExist()
        row(next).assertIsDisplayed()
        row(guestMatch).assertIsDisplayed()
        row(other).assertDoesNotExist()
        filterArchived()
        row(target).assertIsDisplayed()
        row(next).assertDoesNotExist()
        filterArchived()
        screen.onNode(inPage and hasContentDescription(label(R.string.drawer_clear_search))).performClick()
        screen.onNode(inPage and hasText(label(R.string.drawer_empty))).assertDoesNotExist()
        row(next).assertIsDisplayed()
        row(guestMatch).assertIsDisplayed()
        screen.onNode(inPage and SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasTestTag("history-row-" + other.id))
        row(other).assertIsDisplayed()
    }

    @Test fun movingTheTargetOutOfScopeDismissesAnOpenArchiveConfirmation() {
        showHistory()
        requestArchive()
        screen.onNodeWithTag("history-confirm-archive-action").assertIsDisplayed()
        screen.runOnIdle { session.moveConversations(setOf(target.id), guest.id) }
        screen.onNodeWithTag("history-confirm-archive-action").assertDoesNotExist()
        row(target).assertDoesNotExist()

        control(R.string.drawer_all_owners).performClick()
        row(target).assertIsDisplayed()
        screen.runOnIdle {
            assertTrue(changes.isEmpty())
            assertEquals(target.copy(assistantId = guest.id), session.state.value.history.single { it.id == target.id })
            assertEquals(draft, session.snapshot().drafts[target.id])
        }
    }

    @Test fun clearingAnAssistantsActiveHistoryPreservesItsArchivedAndPinnedChats() {
        showHistory()
        control(R.string.drawer_clear_history).performClick()
        button(R.string.drawer_delete).performClick()
        screen.runOnIdle { assertEquals(listOf(setOf(active.id)), clearRequests) }
        filterArchived()
        control(R.string.drawer_clear_history).assertDoesNotExist()
        row(unrelated).assertIsDisplayed()
    }
}
