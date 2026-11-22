package io.hydrion.hub.provider

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.ConversationSession
import io.hydrion.hub.model.FakeGenerationRepository
import io.hydrion.hub.model.LocalAssistant
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.SavedWorkspace
import io.hydrion.hub.ui.LocalPickerComposerBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatModelInheritancePickerTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Test fun recoverMissingPinThenPinTheInheritedModelAndResumeFollowing() {
        val catalog = demoCatalog()
        val store = ProviderStore(object : ProviderCatalogStorage {
            override fun initialCatalog() = catalog
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) = Unit
        })
        val inherited = RemoteModel("demo-chat", "demo-reason")
        val global = RemoteModel("demo-chat", "demo-quick")
        val missing = RemoteModel("removed-provider", "removed-model")
        val assistant = LocalAssistant.Default.copy(chatModel = inherited)
        val chat = Conversation(id = "fixture-chat", modelRoutes = mapOf(Mode.Chat to missing))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = ConversationSession(scope, FakeGenerationRepository(), persist = {},
            newRoutes = store::newThreadRoutes, showRoutes = store::showRoutes)
        val picker = ChatModelPickerState()
        val followTag = "chat-model-follow-default"
        fun openList() = screen.onNodeWithTag("chat-model-select").assertIsDisplayed().performClick()
        fun option(route: RemoteModel) = "chat-model-option:${route.providerId}/${route.modelId}"
        fun choose(route: RemoteModel) {
            val tag = option(route)
            screen.onNodeWithTag("chat-model-results").performScrollToNode(hasTestTag(tag))
            screen.onNodeWithTag(tag).assertIsSelected().performClick()
        }
        try {
            screen.runOnIdle {
                store.setDefault(Mode.Chat, global)
                store.setPerChatModel(true)
                store.onModelPicked = session::selectModel
                store.onAssistantModelPicked = { route ->
                    session.updateAssistant(assistant.id, assistant.name, "", "", route)
                }
                session.restore(SavedWorkspace(history = listOf(chat), assistants = listOf(assistant)))
                session.openConversation(chat.id)
                assertNull(store.catalog.value.selectedEntry(Mode.Chat))
                picker.open()
            }
            screen.setContent {
                val state by session.state.collectAsState()
                val pin = state.current.modelRoutes?.get(Mode.Chat)
                MaterialTheme {
                    if (picker.modelListNavigation) {
                        ChatModelBrowsePage(store, picker, pin, suspended = false, hideForReturn = false,
                            composerPadding = 0.dp, onEditModel = { _, _ -> })
                    } else BoxWithConstraints(Modifier.fillMaxSize()) {
                        val anchor = IntRect(0, constraints.maxHeight * 3 / 4, constraints.maxWidth, constraints.maxHeight)
                        CompositionLocalProvider(LocalPickerComposerBounds provides anchor) {
                            ChatModelPicker(store, picker, pin, suspended = false, hideForReturn = false,
                                modelBrowseProgress = { 0f }, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            openList()
            screen.onNodeWithTag(followTag).performClick()
            screen.runOnIdle {
                assertNull(session.state.value.current.modelRoutes?.get(Mode.Chat))
                assertEquals(inherited, store.catalog.value.selectedEntry(Mode.Chat)?.route)
            }
            // A parameter-only edit leaves the chat following its assistant.
            screen.onNodeWithTag("chat-model-fast").performClick()
            openList()
            screen.onNodeWithTag(followTag).assertIsNotEnabled()
            choose(inherited)
            screen.runOnIdle { assertEquals(inherited, session.state.value.current.modelRoutes?.get(Mode.Chat)) }
            openList()
            screen.onNodeWithTag(followTag).performClick()
            screen.runOnIdle {
                assertNull(session.state.value.current.modelRoutes?.get(Mode.Chat))
                session.updateAssistant(assistant.id, assistant.name, "", "", null)
                store.setPerChatModel(false)
                assertEquals(global, store.catalog.value.selectedEntry(Mode.Chat)?.route)
            }
            openList()
            screen.onNodeWithTag(followTag).assertDoesNotExist()
            choose(global)
            screen.runOnIdle {
                assertEquals(global, session.state.value.assistants.single().chatModel)
                assertNull(session.state.value.current.modelRoutes?.get(Mode.Chat))
            }
        } finally {
            scope.cancel()
        }
    }
}
