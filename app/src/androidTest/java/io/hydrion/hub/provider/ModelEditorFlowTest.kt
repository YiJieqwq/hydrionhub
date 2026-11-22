package io.hydrion.hub.provider

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.MainActivity
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import io.hydrion.hub.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelEditorFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val providerName = "模型编辑测试-" + UUID.randomUUID().toString().take(8)
    private val modelId = "test/reasoning"
    private val modelName = "测试推理模型"
    private fun store() = ProviderStore(ui.activity.applicationContext)
    private fun findProvider() = store().catalog.value.providers.find { it.name == providerName }

    @After fun clean() {
        findProvider()?.let { store().remove(it.id) }
    }

    private fun tab(label: String) = ui.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    @Test fun editsThreePagesThenCommitsModelAndProviderSeparately() {
        ui.onNodeWithContentDescription("打开侧栏").performClick()
        ui.onNodeWithTag("drawer-settings").performClick()
        ui.onNodeWithText(ui.activity.getString(io.hydrion.hub.R.string.settings_chat)).performClick()
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.model_settings_add_provider)).performClick()
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.search_provider_name)).performTextReplacement(providerName)
        tab(ui.activity.getString(R.string.chat_picker_models)).performClick()
        ui.onNodeWithText(ui.activity.getString(R.string.provider_models_add_by_id)).performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.model_id)).performScrollTo().performTextReplacement(modelId)
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.model_name)).performScrollTo().performTextReplacement(modelName)
        tab(ui.activity.getString(R.string.model_tab_reasoning)).performClick()
        ui.onNodeWithText(ui.activity.getString(R.string.model_enable_reasoning)).performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText(ui.activity.getString(R.string.model_can_disable)).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        tab(ui.activity.getString(R.string.model_tab_advanced)).performClick()
        ui.onNodeWithText("图像生成").assertDoesNotExist()
        ui.onNodeWithText("图像模型").assertDoesNotExist()
        ui.onNodeWithText(ui.activity.getString(R.string.model_remote_images)).assertExists()
        ui.onNodeWithText(ui.activity.getString(R.string.model_interpreter)).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.model_save)).performClick()

        ui.onNodeWithText(modelName).assertExists()
        assertNull("模型完成只更新供应商草稿", findProvider())
        ui.onNodeWithText(ui.activity.getString(R.string.settings_save)).performClick()
        val saved = requireNotNull(findProvider()).models.single()
        assertEquals(modelId, saved.externalModelId)
        assertEquals(modelName, saved.name)
        assertTrue("reasoning" in saved.profile.abilities)
        assertTrue(saved.profile.allowDisabling)
        assertTrue(saved.profile.codeInterpreter)

        ui.onNodeWithText(providerName).performClick()
        tab(ui.activity.getString(R.string.chat_picker_models)).performClick()
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.model_configure_named, modelName)).performClick()
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.model_id)).assertTextContains(modelId)
        tab(ui.activity.getString(R.string.model_tab_reasoning)).performClick()
        ui.onNodeWithText(ui.activity.getString(R.string.model_can_disable)).assertIsOn()
        tab(ui.activity.getString(R.string.model_tab_advanced)).performClick()
        ui.onNodeWithText(ui.activity.getString(R.string.model_interpreter)).assertIsOn()
    }
}
