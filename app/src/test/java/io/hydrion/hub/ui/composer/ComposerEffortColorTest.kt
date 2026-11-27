package io.hydrion.hub.ui.composer

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.ViewModelProvider
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.provider.Capability
import io.hydrion.hub.provider.ChatTriggerLabel
import io.hydrion.hub.provider.ChatTriggerText
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.deepSeekProvider
import io.hydrion.hub.ui.messages.LocalMessageStyle
import io.hydrion.hub.ui.messages.MessageStyle
import io.hydrion.hub.ui.messages.MessageStyleStore
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h880dp-xhdpi")
class ComposerEffortColorTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private val levels = listOf("Default", "Low", "High")
    private val peak = Color(0xFF924FF7)
    private val gray = Color(0xFFAFAFAF)
    private fun tag(id: String) = screen.onNodeWithTag(id, useUnmergedTree = true)
    private fun source(text: String) = screen.onNode(hasText(text) and hasAnyAncestor(hasTestTag("chat-model-source-label")), useUnmergedTree = true)

    private fun SemanticsNodeInteraction.style(): TextStyle {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style
    }

    private data class RowSample(val model: TextStyle, val suffix: TextStyle, val bounds: Rect, val pickerInk: Color)

    private fun checkLevels(expected: List<Color>): List<RowSample> = levels.mapIndexed { index, level ->
        tag("chat-model-trigger").performClick()
        tag("chat-model-effort").performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
        val title = tag("chat-model-effort-title").assertTextEquals(level)
        val pickerInk = title.style().color
        if (level == "High") assertEquals(peak, pickerInk)
        tag("chat-model-trigger").performClick()
        val suffix = source(level).assertIsDisplayed()
        val style = suffix.style()
        assertEquals(level, expected[index], style.color)
        RowSample(source("Atlas").style(), style.copy(color = Color.Unspecified), suffix.fetchSemanticsNode().boundsInRoot, pickerInk)
    }

    private fun toggleInAppearance(enable: Boolean) {
        screen.onNodeWithContentDescription("打开侧栏").performClick()
        screen.onNodeWithTag("drawer-settings").performClick()
        screen.onNodeWithText(screen.activity.getString(R.string.settings_style)).performScrollTo().performClick()
        val setting = screen.onNode(isToggleable() and hasText("Color-code reasoning effort levels")).performScrollTo()
        if (enable) setting.assertIsOff().performClick().assertIsOn() else setting.assertIsOn().performClick().assertIsOff()
        // Construct a new reader for the actual preference file instead of consulting the live singleton.
        assertEquals(enable, MessageStyleStore(screen.activity).style.value.colorCodeReasoningEffort)
        repeat(2) { screen.onNodeWithContentDescription(screen.activity.getString(R.string.settings_back)).performClick() }
    }

    @Test fun appearanceSwitchUpdatesTheComposerAndPersistsBothChoices() {
        val chinese = Configuration(screen.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        assertEquals("推理档位异色", screen.activity.createConfigurationContext(chinese).getString(R.string.settings_color_code_reasoning_effort))
        val model = ProviderModel("effort-fixture", "Atlas", listOf(Capability("effort", "Effort", levels)))
        val provider = deepSeekProvider.copy(id = "effort-color-fixture", models = listOf(model))
        screen.runOnIdle {
            val vm = ViewModelProvider(screen.activity)[HubViewModel::class.java]
            vm.providers.save(provider, "", null)
            vm.providers.configure(Mode.Chat, RemoteModel(provider.id, model.id), emptyMap())
            vm.setDraft("Garden notes")
        }
        val white = List(levels.size) { Color.White }
        val original = checkLevels(white)
        toggleInAppearance(true)
        assertEquals(original, checkLevels(listOf(gray, gray, peak)))
        toggleInAppearance(false)
        assertEquals(original, checkLevels(white))
    }

    @Test fun lightComposerRestoresItsOriginalSecondaryInkWhenEnabled() {
        val enabled = mutableStateOf(false)
        val scheme = lightColorScheme()
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme(colorScheme = scheme) {
                    CompositionLocalProvider(LocalMessageStyle provides MessageStyle(colorCodeReasoningEffort = enabled.value)) {
                        ChatTriggerText(ChatTriggerLabel("Atlas", "Default"), dark = false,
                            style = MaterialTheme.typography.labelLarge, gapPx = 4, modifier = Modifier)
                    }
                }
            }
        }
        val suffix = screen.onNodeWithText("Default")
        val name = screen.onNodeWithText("Atlas")
        assertEquals(Color.White, suffix.style().color)
        assertEquals(scheme.onSurface, name.style().color)
        screen.runOnIdle { enabled.value = true }
        assertEquals(scheme.onSurfaceVariant, suffix.style().color)
        assertEquals(scheme.onSurface, name.style().color)
    }
}
