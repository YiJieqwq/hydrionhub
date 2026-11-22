package io.hydrion.hub.ui.messages

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.R
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Draft
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.HistoryFile
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.LocalAssistant
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Role
import io.hydrion.hub.model.SavedWorkspace
import io.hydrion.hub.ui.sidebar.DrawerActions
import io.hydrion.hub.ui.sidebar.DrawerHistoryPage
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditedMessageBranchTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Test fun cancelThenSendInBranchAndOpenSourceHistoryCard() {
        val host = screen.activity
        val directory = Files.createTempDirectory(host.cacheDir.toPath(), "edited-branch-").toFile()
        val isolated = object : Application() {
            init { attachBaseContext(host.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
            override fun getNoBackupFilesDir() = File(directory, "no-backup")
            override fun getSharedPreferences(name: String, mode: Int) = host.getSharedPreferences(directory.name, mode)
        }
        // These messages have no remote model. Sending follows the local generation repository.
        val question = Message(role = Role.User, model = "Local fixture", text = "Initial question")
        val answer = Message(role = Role.Assistant, model = "Local fixture", text = "Original answer", replyTo = question.id)
        val later = question.copy(id = "later", text = "Later question")
        val source = Conversation(title = "Source fixture", messages = listOf(question, answer, later),
            contextStart = later.id, modelRoutes = emptyMap())
        val draft = Draft("Unsent source draft")
        val edited = "Independent question"
        fun label(id: Int) = host.getString(id)
        try {
            val disk = HistoryFile(isolated)
            disk.save(SavedWorkspace(history = listOf(source), drafts = mapOf(source.id to draft)))
            val vm = screen.runOnIdle {
                ViewModelProvider(host, ViewModelProvider.AndroidViewModelFactory(isolated))[HubViewModel::class.java]
            }
            screen.waitUntil(5_000) { vm.state.value.history.any { it.id == source.id } }
            val before = screen.runOnIdle { vm.openConversation(source.id); vm.state.value.current }
            var historyOpen by mutableStateOf(false)
            screen.setContent {
                val state by vm.state.collectAsState()
                val chat = state.current
                val target = chat.messages.first()
                var sheet by remember(chat.id) { mutableStateOf<TurnSheet?>(null) }
                MaterialTheme {
                    Column {
                        UserBubble(target, "", chat.mode)
                        ChatTurnActions(target, chat, vm, state.isGenerating) { _, action -> sheet = action }
                    }
                    sheet?.let { ChatTurnOverlay(target, chat, vm, state.isGenerating, it) { next -> sheet = next } }
                    if (historyOpen) {
                        val actions = remember(vm) {
                            DrawerActions(vm::hasDrawerDraft, vm::setPinned, vm::moveConversations, vm::duplicateConversation,
                                vm::deleteConversations, vm::copyAssistant, vm::groupAssistant, vm::canRemoveAssistant, vm::removeEmptyAssistant)
                        }
                        DrawerHistoryPage(state.history, chat.id, LocalAssistant.Default, false, actions,
                            onBack = { historyOpen = false }, onOpen = { id -> vm.openConversation(id); historyOpen = false },
                            onRename = {}, onPin = vm::togglePin)
                    }
                }
            }
            fun edit(text: String) {
                screen.onNodeWithContentDescription(label(R.string.turn_more)).performClick()
                screen.onNodeWithText(label(R.string.turn_edit)).performScrollTo().performClick()
                screen.onNode(hasSetTextAction()).performTextReplacement(text)
            }
            val storedHistory = disk.path.readBytes()
            edit("Discarded edit")
            screen.onNodeWithText(label(R.string.settings_cancel)).performClick()
            screen.runOnIdle {
                assertEquals(before, vm.state.value.history.single())
                assertEquals(draft, vm.state.value.draft)
            }

            assertArrayEquals(storedHistory, disk.path.readBytes())
            edit(edited)
            // The source context starts later. An independent prefix can still be answered.
            screen.onNodeWithText(label(R.string.turn_save_send)).assertDoesNotExist()
            screen.onNodeWithText(label(R.string.turn_send_in_branch)).performScrollTo().assertIsEnabled().performClick()
            screen.waitUntil(15_000) { vm.state.value.current.id != source.id && !vm.state.value.isGenerating }
            val branch = screen.runOnIdle { vm.state.value.current }
            assertEquals(host.getString(R.string.turn_fork_title, source.title), branch.title)
            assertEquals(listOf(Role.User, Role.Assistant), branch.messages.map { it.role })
            assertEquals(edited, branch.messages.first().text)
            assertEquals(branch.messages.first().id, branch.messages.last().replyTo)
            assertEquals(Generation.Completed, branch.messages.last().status)
            assertTrue(branch.messages.last().text.contains(edited))
            assertNull(branch.contextStart)
            screen.onNodeWithText(edited).assertIsDisplayed()

            screen.runOnIdle { historyOpen = true }
            val sourceCard = hasAnyAncestor(hasTestTag("history-row-${source.id}")) and hasText(source.title) and hasClickAction()
            screen.onNode(sourceCard).performClick()
            screen.onNodeWithText(question.text).assertIsDisplayed()
            screen.runOnIdle {
                assertEquals(before, vm.state.value.current)
                assertEquals(draft, vm.state.value.draft)
                assertEquals(branch, vm.state.value.history.single { it.id == branch.id })
            }
        } finally {
            screen.activityRule.scenario.close()
            host.deleteSharedPreferences(directory.name)
            directory.deleteRecursively()
        }
    }
}
