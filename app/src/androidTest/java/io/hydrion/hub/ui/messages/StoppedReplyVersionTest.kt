package io.hydrion.hub.ui.messages

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.R
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.HistoryFile
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Role
import io.hydrion.hub.model.SavedWorkspace
import io.hydrion.hub.model.appendVersion
import java.io.File
import java.nio.file.Files
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoppedReplyVersionTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Test fun previousVersionShowsStoppedTextAndCanRequestRegeneration() {
        val host = screen.activity
        val directory = Files.createTempDirectory(host.cacheDir.toPath(), "stopped-reply-").toFile()
        val isolated = object : Application() {
            init { attachBaseContext(host.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
            override fun getNoBackupFilesDir() = File(directory, "no-backup")
            override fun getSharedPreferences(name: String, mode: Int) =
                host.getSharedPreferences(directory.name, mode)
        }

        val question = Message(role = Role.User, text = "Fixture question")
        val stopped = Message(role = Role.Assistant, replyTo = question.id, text = "Partial answer", status = Generation.Stopped)
        val complete = stopped.copy(text = "Replacement answer", status = Generation.Completed)
        val chat = Conversation(messages = listOf(question, stopped)).appendVersion(stopped, complete)
        try {
            HistoryFile(isolated).save(SavedWorkspace(history = listOf(chat)))
            val vm = screen.runOnIdle {
                ViewModelProvider(host, ViewModelProvider.AndroidViewModelFactory(isolated))[HubViewModel::class.java]
            }
            screen.waitUntil(5_000) { vm.state.value.history.any { it.id == chat.id } }
            screen.runOnIdle { vm.openConversation(chat.id) }

            val config = Configuration(host.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            val english = host.createConfigurationContext(config)
            var requested: Pair<Message, TurnSheet>? = null
            screen.setContent {
                val state by vm.state.collectAsState()
                CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides config) {
                    MaterialTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            AssistantTurn(state.current.messages.last(), state.current, "", vm, state.isGenerating) { msg, action ->
                                requested = msg to action
                            }
                        }
                    }
                }
            }

            fun action(label: Int) = screen.onNodeWithContentDescription(english.getString(label)).performScrollTo()
            screen.onNodeWithText(complete.text).assertIsDisplayed()
            screen.onNodeWithText("Stopped").assertDoesNotExist()
            action(R.string.turn_previous).performClick()
            screen.onNodeWithText(stopped.text).performScrollTo().assertIsDisplayed()
            screen.onNodeWithText("Stopped").performScrollTo().assertIsDisplayed()
            action(R.string.turn_retry).assertIsEnabled().performClick()
            screen.runOnIdle {
                assertEquals(stopped.id, requested?.first?.id)
                assertEquals(Generation.Stopped, requested?.first?.status)
                assertEquals(TurnSheet.Retry, requested?.second)
            }
            action(R.string.turn_next).performClick()
            screen.onNodeWithText(complete.text).performScrollTo().assertIsDisplayed()
            screen.onNodeWithText("Stopped").assertDoesNotExist()
        } finally {
            screen.activityRule.scenario.close()
            host.deleteSharedPreferences(directory.name)
            directory.deleteRecursively()
        }
    }
}
