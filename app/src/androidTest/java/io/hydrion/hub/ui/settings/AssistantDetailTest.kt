package io.hydrion.hub.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.hydrion.hub.MainActivity
import androidx.test.platform.app.InstrumentationRegistry
import io.hydrion.hub.R
import io.hydrion.hub.model.LocalAssistant
import io.hydrion.hub.provider.demoCatalog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AssistantDetailTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun editingAnInactiveProfileDiscardsOrCommitsOnlyOnSave() {
        val current = LocalAssistant("primary", "Primary", systemPrompt = "current prompt")
        var other = LocalAssistant("secondary", "Secondary", systemPrompt = "original")
        val open = mutableStateOf(true)
        var saves = 0
        screen.runOnUiThread { screen.activity.setContent {
            MaterialTheme {
                if (open.value) AssistantEditor(other, demoCatalog(), save = {
                    other = it; saves++; true
                }, close = { open.value = false }) { Text(it.name) }
            }
        } }
        fun prompt() = screen.onNodeWithTag("assistant-system-prompt")
        screen.onNodeWithText(label(R.string.assistant_prompts)).performClick()
        prompt().performTextReplacement("discard this")
        screen.onNodeWithContentDescription(label(R.string.settings_back)).performClick()
        screen.onNodeWithText(label(R.string.model_settings_drop)).performClick()
        screen.runOnIdle { assertEquals("original", other.systemPrompt); assertEquals(0, saves); open.value = true }
        screen.onNodeWithText(label(R.string.assistant_prompts)).performClick()
        prompt().assertTextContains("original").performTextReplacement("save this")
        screen.onNodeWithText(label(R.string.settings_save)).performClick()
        screen.runOnIdle {
            assertEquals(1, saves); assertEquals("secondary", other.id); assertEquals("save this", other.systemPrompt)
            assertEquals("current prompt", current.systemPrompt); open.value = true
        }
        screen.onNodeWithText(label(R.string.assistant_prompts)).performClick()
        prompt().assertTextContains("save this")
    }
}
