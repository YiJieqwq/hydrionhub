package io.hydrion.hub

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import io.hydrion.hub.ui.sidebar.DrawerActions
import io.hydrion.hub.ui.sidebar.DrawerHistoryPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    @After fun releaseScope() { scope.cancel() }

    private fun showHistory() {
        val actions = DrawerActions(hasDraft = session::hasDraft, pin = session::setPinned,
            move = session::moveConversations, duplicate = session::duplicateConversation,
            delete = { clearRequests += it }, copyAssistant = session::copyAssistant,
            groupAssistant = session::groupAssistant, canRemoveAssistant = session::canRemoveAssistant,
            removeAssistant = session::removeEmptyAssistant, setArchived = { id, archived ->
                changes += id to archived
                session.setArchived(id, archived)
            })
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
