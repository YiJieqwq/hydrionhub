package io.hydrion.hub.ui.messages

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.MessageVersions
import io.hydrion.hub.model.Role
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageVersionConfirmationTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun confirmationTracksOtherVersionsAndCancelKeepsThem() {
        val visible = Message(id = "fixture-reply", role = Role.Assistant, text = "Visible")
        val older = listOf(visible.copy(text = "First"), visible.copy(text = "Second"))
        val base = Conversation(id = "fixture-chat", messages = listOf(visible))
        fun withOthers(count: Int) = base.copy(versions = mapOf(visible.id to MessageVersions(count, older.take(count))))
        val chat = mutableStateOf(withOthers(1))
        val sheet = mutableStateOf<TurnSheet?>(TurnSheet.DeleteOthers)
        screen.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[HubViewModel::class.java]
            activity.setContent {
                MaterialTheme {
                    sheet.value?.let { ChatTurnOverlay(visible, chat.value, vm, false, it) { next -> sheet.value = next } }
                }
            }
        }

        fun confirmation(count: Int) = screen.onNodeWithText(
            screen.activity.resources.getQuantityString(R.plurals.turn_confirm_others, count, count))
        confirmation(1).assertIsDisplayed()
        screen.runOnIdle { chat.value = withOthers(2) }
        confirmation(1).assertDoesNotExist()
        confirmation(2).assertIsDisplayed()

        screen.onNodeWithText(screen.activity.getString(R.string.settings_cancel)).performClick()
        screen.onNode(isDialog()).assertDoesNotExist()
        screen.runOnIdle {
            assertEquals(null, sheet.value)
            assertEquals(withOthers(2), chat.value)
            sheet.value = TurnSheet.DeleteOthers
        }
        confirmation(2).assertIsDisplayed()
        screen.runOnIdle { chat.value = base }
        screen.onNode(isDialog()).assertDoesNotExist()
    }
}
