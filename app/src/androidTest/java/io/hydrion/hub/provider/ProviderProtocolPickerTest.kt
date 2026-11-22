package io.hydrion.hub.provider

import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderProtocolPickerTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun choosesAChatProtocolAndKeepsItWhenCustomInputIsCancelled() {
        val original = Provider("fixture", Mode.Chat, "Fixture", ChatProtocol.ChatCompletionsHttp.label, "", emptyList())
        val draft = mutableStateOf(original)
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    ProviderConfigTab(draft.value, apiKey = "", sample = emptyMap(), scroll = rememberScrollState(),
                        existing = false, onUpdate = { draft.value = it }, onKey = {}, onModels = {}, onDelete = {})
                }
            }
        }

        fun label(id: Int) = screen.activity.getString(id)
        fun openMenu() = screen.onNode(hasText(label(R.string.model_api_protocol)) and hasClickAction()).performClick()
        val responses = ChatProtocol.ResponsesHttp.label
        openMenu()
        screen.onNodeWithText(responses).performClick()
        screen.runOnIdle { assertEquals(original.copy(protocol = responses), draft.value) }

        openMenu()
        screen.onNodeWithText(label(R.string.model_custom_entry)).performClick()
        screen.onNodeWithContentDescription(label(R.string.model_protocol_type)).performTextReplacement("fixture-custom")
        screen.onNodeWithText(label(R.string.settings_cancel)).performClick()
        screen.onNodeWithText(responses).assertIsDisplayed()
        screen.runOnIdle { assertEquals(original.copy(protocol = responses), draft.value) }

        openMenu()
        val local = label(R.string.model_protocol_local)
        screen.onNodeWithText(local).performClick()
        screen.onNodeWithText(local).assertIsDisplayed()
        screen.runOnIdle { assertEquals(original.copy(protocol = "local"), draft.value) }
    }
}
