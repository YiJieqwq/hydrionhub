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
        ui.onNodeWithContentDescription("添加供应商").performClick()
        ui.onNodeWithContentDescription("供应商名称").performTextReplacement(providerName)
        tab("模型").performClick()
        ui.onNodeWithText("添加模型").performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithContentDescription("模型 ID").performScrollTo().performTextReplacement(modelId)
        ui.onNodeWithContentDescription("模型名称").performScrollTo().performTextReplacement(modelName)
        tab("推理").performClick()
        ui.onNodeWithText("启用推理").performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText("允许关闭推理").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        tab("高级").performClick()
        ui.onNodeWithText("图像生成").assertDoesNotExist()
        ui.onNodeWithText("图像模型").assertDoesNotExist()
        ui.onNodeWithText("远程图片链接").assertExists()
        ui.onNodeWithText("代码解释器").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithContentDescription("保存模型").performClick()

        ui.onNodeWithText(modelName).assertExists()
        assertNull("模型完成只更新供应商草稿", findProvider())
        ui.onNodeWithText("保存").performClick()
        val saved = requireNotNull(findProvider()).models.single()
        assertEquals(modelId, saved.externalModelId)
        assertEquals(modelName, saved.name)
        assertTrue("reasoning" in saved.profile.abilities)
        assertTrue(saved.profile.allowDisabling)
        assertTrue(saved.profile.codeInterpreter)

        ui.onNodeWithText(providerName).performClick()
        tab("模型").performClick()
        ui.onNodeWithContentDescription("配置 $modelName").performClick()
        ui.onNodeWithContentDescription("模型 ID").assertTextContains(modelId)
        tab("推理").performClick()
        ui.onNodeWithText("允许关闭推理").assertIsOn()
        tab("高级").performClick()
        ui.onNodeWithText("代码解释器").assertIsOn()
    }
}
