package io.hydrion.hub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.speech.SpeechRecognizer
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.HubState
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.Role
import io.hydrion.hub.ui.HydrionApp
import io.hydrion.hub.ui.composer.DictationService
import io.hydrion.hub.ui.composer.DictationSpeech
import io.hydrion.hub.ui.composer.DictationSpeechFactory
import io.hydrion.hub.ui.composer.DictationSpeechListener
import io.hydrion.hub.ui.composer.LocalDictationSpeechFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ComposerDictationDeviceTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val fake = FakeSpeechFactory()
    private lateinit var vm: HubViewModel
    private lateinit var before: HubState
    private lateinit var fixtureId: String
    private val localModel = RemoteModel("demo-chat", "demo-reason")
    private var previousModel: RemoteModel? = null
    private var previousOverrides: Map<String, String> = emptyMap()
    private var localOverrides: Map<String, String> = emptyMap()

    private val original = "left replace right"
    private val replacement = "left dictated right"
    private val selection = TextRange(5, 12)
    private val file = Attachment("dictation-fixture", "notes.txt", mimeType = "text/plain")
    private val editor get() = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
    private val bar get() = control("bar")
    private fun control(name: String) = screen.onNodeWithTag("chat-dictation-$name")
    private fun touch(node: SemanticsNodeInteraction) = node.performTouchInput { click(center) }

    @Before fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val result = instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}"
            )
            ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        screen.runOnUiThread {
            vm = ViewModelProvider(screen.activity)[HubViewModel::class.java]
            before = vm.state.value
            val catalog = vm.providers.catalog.value
            previousModel = catalog.active[Mode.Chat]
            previousOverrides = previousModel?.let { catalog.overrides[it] }.orEmpty()
            localOverrides = catalog.overrides[localModel].orEmpty()
            vm.newConversation()
            fixtureId = vm.state.value.current.id
            vm.setMode(Mode.Chat)
            vm.providers.configure(Mode.Chat, localModel, localOverrides)
            vm.setSearch("禁用")
            vm.setImageTool("禁用")
            vm.addAttachment(file)
            vm.setDraft(original)
            screen.activity.setContent {
                CompositionLocalProvider(LocalDictationSpeechFactory provides fake) { HydrionApp() }
            }
        }
        touch(editor.assertTextContains(original))
        screen.waitUntil(20_000) { imeVisible() }
        editor.performTextInputSelection(selection)
        assertEditor(original, selection)
    }

    @After fun restore() {
        if (!::fixtureId.isInitialized) return
        screen.runOnUiThread {
            vm.deleteConversation(fixtureId)
            previousModel?.let { vm.providers.configure(Mode.Chat, it, previousOverrides) }
            if (before.modeLocked) vm.openConversation(before.current.id) else {
                vm.setMode(before.current.mode)
                vm.restoreModelPickerDraft(vm.state.value.current.id, before.draft)
            }
        }
    }

    private fun imeVisible(): Boolean = screen.runOnUiThread {
        val view = screen.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(view)
        val ime = WindowInsetsCompat.Type.ime()
        view.hasWindowFocus() && insets != null && insets.isVisible(ime) &&
            insets.getInsets(ime).bottom > insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
    }

    private fun assertEditor(text: String, cursor: TextRange, previous: SemanticsNode? = null) {
        val node = editor.assertIsFocused().fetchSemanticsNode()
        assertEquals(text, node.config[SemanticsProperties.EditableText].text)
        assertEquals(cursor, node.config[SemanticsProperties.TextSelectionRange])
        previous?.let { assertEquals("The message field kept its identity", it.id, node.id) }
        assertTrue("The same editor still owns the soft keyboard", imeVisible())
    }

    private fun start(): FakeSession {
        val field = editor.fetchSemanticsNode()
        val count = screen.runOnUiThread { fake.sessions.size }
        touch(screen.onNodeWithTag("chat-composer-dictate").assertIsEnabled())
        bar.assertIsDisplayed()
        screen.onNodeWithTag("chat-dictation-waveform", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("The underlying BasicTextField remains attached", screen.runOnUiThread { field.layoutInfo.isAttached })
        assertTrue("Listening preserves the visible IME", imeVisible())
        return screen.runOnUiThread {
            assertEquals(count + 1, fake.sessions.size)
            fake.sessions.last().also { assertEquals(1, it.starts) }
        }
    }

    private fun unchanged() = screen.runOnUiThread {
        assertEquals(fixtureId, vm.state.value.current.id)
        assertEquals(original, vm.state.value.draft.text)
        assertEquals(listOf(file), vm.state.value.draft.attachments)
        assertTrue(vm.state.value.current.messages.isEmpty())
    }

    @Test fun cancelWhileWaitingDropsLateFinalAndPreservesEditorSelectionAndAttachment() {
        val field = editor.fetchSemanticsNode()
        val session = start()
        screen.runOnUiThread { session.events.rms(8f) }
        touch(control("send").assertIsEnabled())
        control("waiting").assertIsDisplayed()
        assertEquals(1, screen.runOnUiThread { session.stops })
        touch(control("cancel").assertIsEnabled())
        bar.assertDoesNotExist()
        screen.runOnUiThread { session.events.finalText("late words") }
        assertEditor(original, selection, field)
        unchanged()
        assertEquals(1, screen.runOnUiThread { session.destroys })
    }

    @Test fun squareOnlySavesAtSelectionAndToolsReturnsToThatDraft() {
        val field = editor.fetchSemanticsNode()
        val session = start()
        touch(control("save"))
        control("waiting").assertIsDisplayed()
        unchanged()
        screen.runOnUiThread { session.events.finalText("dictated") }
        bar.assertDoesNotExist()
        assertEditor(replacement, TextRange(13), field)
        screen.runOnUiThread {
            assertEquals(listOf(file), vm.state.value.draft.attachments)
            assertTrue(vm.state.value.current.messages.isEmpty())
        }
        touch(screen.onNodeWithContentDescription("打开工具箱").assertIsEnabled())
        screen.onNodeWithTag("chat-tools-page").assertIsDisplayed()
        bar.assertDoesNotExist()
        screen.onNodeWithTag("composer-panel-dismiss").performClick()
        assertEditor(replacement, TextRange(13), field)
    }

    @Test fun networkAndServerErrorsWhileSendingKeepDraftSelectionAndAttachment() {
        val field = editor.fetchSemanticsNode()
        for (code in listOf(SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_SERVER)) {
            val session = start()
            touch(control("send").assertIsEnabled())
            control("waiting").assertIsDisplayed()
            screen.runOnUiThread { session.events.error(code) }
            bar.assertDoesNotExist()
            screen.runOnUiThread {
                assertEquals(1, session.stops)
                assertEquals(1, session.destroys)
                session.events.finalText("late result for error $code")
            }
            assertEditor(original, selection, field)
            unchanged()
        }
    }

    @Test fun arrowRejectsBlankFinalAndSendsANonemptyFinalExactlyOnce() {
        val field = editor.fetchSemanticsNode()
        val blank = start()
        touch(control("send"))
        screen.runOnUiThread { blank.events.finalText("   ") }
        bar.assertDoesNotExist()
        assertEditor(original, selection, field)
        unchanged()

        val second = start()
        screen.runOnUiThread { second.events.end() }
        control("waiting").assertIsDisplayed()
        control("send").assertIsNotEnabled()
        control("save").assertIsNotEnabled()
        control("cancel").assertIsEnabled()
        screen.runOnUiThread { second.events.finalText("dictated") }
        control("waiting").assertDoesNotExist()
        control("send").assertIsEnabled()
        control("save").assertIsEnabled()
        unchanged()
        touch(control("send"))
        screen.waitUntil(15_000) { vm.state.value.current.messages.any { it.role == Role.User } }
        screen.runOnUiThread {
            second.events.finalText("duplicate callback")
            blank.events.finalText("stale callback")
            vm.stop()
        }
        screen.waitForIdle()
        screen.runOnUiThread {
            val user = vm.state.value.current.messages.single { it.role == Role.User }
            assertEquals(replacement, user.text)
            assertEquals(listOf(file), user.attachments)
            assertNull(user.remoteModel)
            assertEquals("", vm.state.value.draft.text)
            assertTrue(vm.state.value.draft.attachments.isEmpty())
        }
    }

    @Test fun backgroundCancelsRecognitionAndRestoresTheOriginalDraftWhenResumed() {
        val field = editor.fetchSemanticsNode()
        val session = start()
        val activity = screen.activityRule.scenario
        activity.moveToState(Lifecycle.State.CREATED)
        try {
            assertEquals(Lifecycle.State.CREATED, activity.state)
            screen.runOnUiThread {
                assertEquals(1, session.cancels)
                assertEquals(1, session.destroys)
                session.events.finalText("late in background")
            }
            unchanged()
        } finally {
            activity.moveToState(Lifecycle.State.RESUMED)
        }
        screen.waitUntil(20_000) { imeVisible() }
        bar.assertDoesNotExist()
        screen.runOnUiThread {
            session.events.error(SpeechRecognizer.ERROR_CLIENT)
            session.events.finalText("late after resume")
            assertEquals(1, fake.sessions.size)
        }
        assertEditor(original, selection, field)
        unchanged()
    }

    @Test fun defaultSpeechServiceRequiresExplicitConsentBeforeItStarts() {
        // Exercise the fresh-install state of this single flag and restore it after the test.
        val preferences = screen.activity.getSharedPreferences("chat_dictation", Context.MODE_PRIVATE)
        val key = "allow_default_service"
        val previous = if (preferences.contains(key)) preferences.getBoolean(key, false) else null
        preferences.edit().remove(key).commit()
        fun label(id: Int) = screen.onNodeWithText(screen.activity.getString(id))
        try {
            screen.runOnUiThread { fake.service = DictationService.System }
            val microphone = screen.onNodeWithTag("chat-composer-dictate")
            touch(microphone)
            label(R.string.dictation_service_consent_body).assertIsDisplayed()
            assertTrue(screen.runOnUiThread { fake.sessions.isEmpty() })
            touch(label(R.string.dictation_service_cancel))
            bar.assertDoesNotExist()
            screen.waitUntil(20_000) { imeVisible() }
            assertTrue(screen.runOnUiThread { fake.sessions.isEmpty() })
            assertFalse(preferences.getBoolean(key, false))
            unchanged()

            touch(microphone)
            label(R.string.dictation_service_consent_body).assertIsDisplayed()
            assertTrue(screen.runOnUiThread { fake.sessions.isEmpty() })
            touch(label(R.string.dictation_service_allow))
            bar.assertIsDisplayed()
            screen.waitUntil(20_000) { imeVisible() }
            assertEquals(1, screen.runOnUiThread { fake.sessions.single().starts })
            assertTrue(preferences.getBoolean(key, false))
            touch(control("cancel"))
            unchanged()
        } finally {
            val edit = preferences.edit()
            if (previous == null) edit.remove(key) else edit.putBoolean(key, previous)
            edit.commit()
        }
    }

    @Test fun switchingModeCancelsListeningAndIgnoresOldCallbacksInTheNextSession() {
        val old = start()
        touch(screen.onNodeWithText("Image"))
        bar.assertDoesNotExist()
        screen.onNodeWithTag("chat-composer-dictate").assertDoesNotExist()
        screen.runOnUiThread {
            assertEquals(Mode.Image, vm.state.value.current.mode)
            assertEquals(1, old.cancels)
            assertEquals(1, old.destroys)
            old.events.finalText("stale Image result")
        }
        unchanged()
        touch(screen.onNodeWithText("Chat"))
        val current = start()
        screen.runOnUiThread {
            old.events.end()
            old.events.error(SpeechRecognizer.ERROR_CLIENT)
            old.events.finalText("stale Chat result")
        }
        bar.assertIsDisplayed()
        control("waiting").assertDoesNotExist()
        unchanged()
        touch(control("cancel"))
        assertEquals(1, screen.runOnUiThread { current.cancels })
        unchanged()
    }
}

private class FakeSpeechFactory : DictationSpeechFactory {
    val sessions = mutableListOf<FakeSession>()

    var service = DictationService.OnDevice

    override fun available(context: Context) = service
    override fun create(
        context: Context, service: DictationService, listener: DictationSpeechListener
    ): DictationSpeech {
        assertEquals(this.service, service)
        return FakeSession(listener).also(sessions::add)
    }
}

private class FakeSession(val events: DictationSpeechListener) : DictationSpeech {
    var starts = 0
    var stops = 0
    var cancels = 0
    var destroys = 0

    override fun start() {
        starts++
        events.ready()
    }
    override fun stop() { stops++ }
    override fun cancel() { cancels++ }
    override fun destroy() { destroys++ }
}
