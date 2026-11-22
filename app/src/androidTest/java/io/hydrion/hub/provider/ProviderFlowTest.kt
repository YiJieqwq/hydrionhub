package io.hydrion.hub.provider

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.MainActivity
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import io.hydrion.hub.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderFlowTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private val providerName = "测试供应商-" + UUID.randomUUID().toString().take(8)
    private val sampleName = "Hydrion Chat Standard"
    private val store get() = ProviderStore(screen.activity.applicationContext)

    @After fun removeFixture() {
        val saved = store
        saved.catalog.value.providers.find { it.name == providerName }?.let { saved.remove(it.id) }
    }

    @Test fun saveAcrossTabsAndDiscardSubsequentEdits() {
        screen.onNodeWithContentDescription("打开侧栏").performClick()
        screen.onNodeWithTag("drawer-settings").performClick()
        screen.onNodeWithText(screen.activity.getString(io.hydrion.hub.R.string.settings_chat)).performClick()
        screen.onNodeWithText(screen.activity.getString(R.string.model_settings_chat_providers)).assertIsDisplayed()
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.model_settings_add_provider)).performClick()
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.search_provider_name)).performTextReplacement(providerName)
        screen.onNode(hasText(screen.activity.getString(R.string.chat_picker_models)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        // Wait for the editor keyboard and sheet windows to transition before typing.
        screen.onNodeWithText(screen.activity.getString(R.string.provider_models_local_presets)).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        screen.waitUntil(15_000) { screen.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        screen.onNode(hasSetTextAction()).performTextInput("Standard")
        val add = hasContentDescription(screen.activity.getString(R.string.model_add_named, sampleName))
        screen.onNode(add).performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        screen.waitUntil(15_000) { screen.onAllNodes(hasContentDescription(screen.activity.getString(R.string.model_added_named, sampleName))).fetchSemanticsNodes().isNotEmpty() }
        screen.onNode(add).assertDoesNotExist()
        screen.onNodeWithText(screen.activity.getString(R.string.model_done)).performSemanticsAction(SemanticsActions.OnClick)
        screen.onNodeWithText(sampleName).assertExists()

        screen.onNodeWithText(screen.activity.getString(R.string.model_configure)).performClick()
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.search_provider_name)).assertTextContains(providerName)
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.model_provider_enable)).performScrollTo().assertIsDisplayed().performClick()
        screen.onNodeWithText(screen.activity.getString(R.string.settings_save)).performClick()
        screen.onNodeWithText(providerName).performClick()
        val saved = store.catalog.value.providers.single { it.name == providerName }
        assertFalse(saved.enabled)
        assertEquals(listOf(sampleName), saved.models.map { it.name })

        screen.onNodeWithContentDescription(screen.activity.getString(R.string.search_provider_name)).performTextReplacement("未保存的名称")
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.settings_back)).performClick()
        screen.onNodeWithText(screen.activity.getString(R.string.model_settings_drop)).performClick()
        screen.onNodeWithText(providerName).assertExists()
        assertEquals(providerName, store.catalog.value.providers.single { it.id == saved.id }.name)
    }
}
