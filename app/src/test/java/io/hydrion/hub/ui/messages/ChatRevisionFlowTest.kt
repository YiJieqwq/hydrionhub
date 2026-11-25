package io.hydrion.hub.ui.messages

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.model.*
import io.hydrion.hub.provider.ChatProtocol
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.ui.HydrionApp
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatRevisionFlowTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var vm: HubViewModel
    private val question = "First question"
    private val older = "Earlier answer"
    private val newer = "Newer answer"
    private val laterQ = "Later original question"
    private val laterA = "Later original answer"
    private val currentModel = ProviderModel("current", "Current model", profile = ModelProfile(externalId = "current-wire"))
    private val olderModel = ProviderModel("older", "Earlier model", profile = ModelProfile(externalId = "earlier-wire"))
    private val currentRoute = RemoteModel("fixture", currentModel.id)

    private fun awaitHost(condition: () -> Boolean) = screen.waitUntil(15_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun open(protocol: ChatProtocol) {
        val host = screen.activity
        val directory = Files.createTempDirectory(host.cacheDir.toPath(), "revision-flow-").toFile()
        val app = object : Application() {
            init { attachBaseContext(host.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(directory.name + name, mode)
        }
        val provider = Provider(currentRoute.providerId, Mode.Chat, "Fixture", protocol.label, "https://fixture.invalid/v1",
            listOf(currentModel, olderModel))
        val q = Message(id = "q", role = Role.User, text = question)
        val a = Message(id = "a", role = Role.Assistant, text = older, replyTo = q.id, model = olderModel.name,
            remoteModel = RemoteModel(provider.id, olderModel.id),
            parameters = mapOf("model" to "earlier-wire", CHAT_PROTOCOL_PARAMETER to protocol.name))
        val q2 = Message(id = "q2", role = Role.User, text = laterQ)
        val a2 = Message(id = "a2", role = Role.Assistant, text = laterA, replyTo = q2.id)
        val chat = Conversation(id = "source", title = "Garden (3)", messages = listOf(q, a, q2, a2),
            contextStart = q.id, modelRoutes = mapOf(Mode.Chat to currentRoute))
            .appendVersion(a, a.copy(text = newer, remoteModel = currentRoute, model = currentModel.name,
                parameters = mapOf("model" to "current-wire", CHAT_PROTOCOL_PARAMETER to protocol.name)))
        HistoryFile(app).save(SavedWorkspace(listOf(chat, Conversation(title = "Garden (1)", archived = true)),
            drafts = mapOf(chat.id to Draft(text = "Parked original draft"))))
        // Exercise the shipped UI, provider validation and both JSON encoders. Only the socket boundary is replaced.
        screen.runOnIdle {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = HubViewModel(app) { _, request, pending ->
                    requests.add(request.body.toString())
                    flowOf(pending.copy(status = Generation.Completed, text = "Fixture response " + requests.size))
                } as T
            }
            vm = ViewModelProvider(owner, factory)[HubViewModel::class.java]
        }
        awaitHost { vm.workspaceLoad.value != WorkspaceLoad.Reading }
        assertEquals(WorkspaceLoad.Ready, vm.workspaceLoad.value)
        screen.activityRule.scenario.onActivity { activity ->
            vm.providers.save(provider, "offline-fixture", currentModel.id)
            vm.openConversation(chat.id)
            activity.setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { HydrionApp() } }
        }
    }

    @After fun close() { screen.runOnIdle { owner.viewModelStore.clear() } }

    private fun bubble(id: String, control: Int) {
        screen.onNodeWithTag("message-timeline").performScrollToKey(id)
        screen.onNode(hasContentDescription(screen.activity.getString(control)) and hasAnyAncestor(hasTestTag("message-turn-" + id)))
            .performScrollTo().assertIsEnabled().performClick()
    }

    private fun action(id: Int) = screen.onNode(hasText(screen.activity.getString(id)) and hasClickAction())

    private fun send(text: String) {
        screen.onNodeWithContentDescription("消息输入框").performClick().performTextReplacement(text)
        screen.onNodeWithContentDescription("发送消息").assertIsEnabled().performClick()
    }

    private fun wire(index: Int, array: String, model: String): List<Pair<String, String>> {
        awaitHost { requests.size >= index && !vm.state.value.isGenerating }
        assertEquals(index, requests.size)
        val body = JSONObject(requests[index - 1])
        assertEquals(model, body.getString("model"))
        val turns = body.getJSONArray(array)
        return (0 until turns.length()).map { i -> turns.getJSONObject(i).let { it.getString("role") to it.getString("content") } }
    }

    @Test fun forkSelectedOlderReplyThenSendFromBranchAndFromOriginalUseDifferentSerializedPrefixes() {
        open(ChatProtocol.ChatCompletionsHttp)
        bubble("a", R.string.turn_previous)
        val source = vm.state.value.current
        assertEquals(older, source.messages[1].text)
        assertEquals(0, source.versions.getValue("a").selected)
        bubble("a", R.string.turn_more_reply)
        action(R.string.turn_fork).performScrollTo().assertIsDisplayed().performClick()
        val fork = vm.state.value.current
        assertNotEquals(source.id, fork.id)
        assertEquals("Garden (2)", fork.title)
        assertEquals(listOf(question, older), fork.messages.map(Message::text))
        assertTrue(fork.versions.isEmpty())
        assertTrue(fork.messages.none { it.id in source.messages.map(Message::id) })
        assertEquals(fork.messages.first().id, fork.contextStart)
        assertEquals(fork.contextStart, fork.messages.last().replyTo)
        send("Branch follow-up")
        val prefix = listOf("user" to question, "assistant" to older)
        assertEquals(prefix + ("user" to "Branch follow-up"), wire(1, "messages", "current-wire"))
        val branchAfterSending = vm.state.value.current
        assertEquals(source, vm.state.value.history.single { it.id == source.id })
        screen.runOnIdle { vm.openConversation(source.id) }
        assertEquals(source, vm.state.value.current)
        assertEquals("Parked original draft", vm.state.value.draft.text)
        send("Original follow-up")
        assertEquals(prefix + listOf("user" to laterQ, "assistant" to laterA, "user" to "Original follow-up"),
            wire(2, "messages", "current-wire"))
        assertEquals(branchAfterSending, vm.state.value.history.single { it.id == fork.id })
    }

    @Test fun editedOlderAnswerContinuesInANewSlotAndEachRetryUsesTheSelectedPrefixAndModel() {
        open(ChatProtocol.ResponsesHttp)
        bubble("a", R.string.turn_previous)
        bubble("a", R.string.turn_more_reply)
        action(R.string.turn_edit).performScrollTo().assertIsDisplayed().performClick()
        screen.onNode(hasSetTextAction() and hasText(older)).performTextReplacement("Edited answer")
        action(R.string.turn_save_continue).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf("user" to question, "assistant" to "Edited answer"), wire(1, "input", "current-wire"))
        val edited = vm.state.value.current
        assertEquals(listOf(question, "Edited answer", "Fixture response 1", laterQ, laterA), edited.messages.map(Message::text))
        assertEquals(listOf(older, newer), edited.versions.getValue("a").others.map(Message::text))
        val continuation = edited.messages[2]
        assertTrue(continuation.continuesAssistant)
        assertFalse(edited.versions.containsKey(continuation.id))
        assertEquals("Parked original draft", vm.state.value.draft.text)

        bubble("a", R.string.turn_previous)
        bubble("a", R.string.turn_previous)
        bubble(continuation.id, R.string.turn_retry)
        action(R.string.turn_retry).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf("user" to question, "assistant" to older), wire(2, "input", "current-wire"))

        bubble("a", R.string.turn_retry)
        screen.onNode(hasText("Fixture · Earlier model", substring = true)).assertIsDisplayed()
        action(R.string.turn_retry).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf("user" to question), wire(3, "input", "earlier-wire"))
        val saved = decodeWorkspace(encodeWorkspace(vm.backupSnapshot())).history.single { it.id == edited.id }
        assertEquals(listOf(question, "Fixture response 3", "Fixture response 2", laterQ, laterA), saved.messages.map(Message::text))
        assertEquals(3, saved.versions.getValue("a").selected)
        assertEquals(listOf(older, newer, "Edited answer"), saved.versions.getValue("a").others.map(Message::text))
        assertTrue(saved.messages[2].continuesAssistant)
        assertEquals(continuation, saved.versions.getValue(continuation.id).others.single())
    }
}
