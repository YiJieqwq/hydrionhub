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
        screen.onNodeWithText("设置").performClick()
        screen.onNodeWithText("聊天模型").performClick()
        screen.onNodeWithText("聊天模型供应商").assertIsDisplayed()
        screen.onNodeWithContentDescription("添加供应商").performClick()
        screen.onNodeWithContentDescription("供应商名称").performTextReplacement(providerName)
        screen.onNode(hasText("模型") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        // Wait for the editor keyboard and sheet windows to transition before typing.
        screen.onNodeWithText("选择模型").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        screen.waitUntil(15_000) { screen.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        screen.onNode(hasSetTextAction()).performTextInput("Standard")
        val add = hasContentDescription("添加 $sampleName")
        screen.onNode(add).performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        screen.waitUntil(15_000) { screen.onAllNodes(hasContentDescription("已添加 $sampleName")).fetchSemanticsNodes().isNotEmpty() }
        screen.onNode(add).assertDoesNotExist()
        screen.onNodeWithText("完成").performSemanticsAction(SemanticsActions.OnClick)
        screen.onNodeWithText(sampleName).assertExists()

        screen.onNodeWithText("配置").performClick()
        screen.onNodeWithContentDescription("供应商名称").assertTextContains(providerName)
        screen.onNodeWithContentDescription("启用供应商").performScrollTo().assertIsDisplayed().performClick()
        screen.onNodeWithText("保存").performClick()
        screen.onNodeWithText(providerName).performClick()
        val saved = store.catalog.value.providers.single { it.name == providerName }
        assertFalse(saved.enabled)
        assertEquals(listOf(sampleName), saved.models.map { it.name })

        screen.onNodeWithContentDescription("供应商名称").performTextReplacement("未保存的名称")
        screen.onNodeWithContentDescription("返回").performClick()
        screen.onNodeWithText("放弃更改").performClick()
        screen.onNodeWithText(providerName).assertExists()
        assertEquals(providerName, store.catalog.value.providers.single { it.id == saved.id }.name)
    }
}
