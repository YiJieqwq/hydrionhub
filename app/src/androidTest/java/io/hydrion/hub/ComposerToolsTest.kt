package io.hydrion.hub

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerToolsTest {
    @get:Rule
    val screen = createAndroidComposeRule<MainActivity>()

    private fun openTools() {
        screen.onNodeWithContentDescription("消息输入框").performClick()
        screen.onNodeWithContentDescription("打开工具箱").performClick()
        screen.onNodeWithText("Tools").assertIsDisplayed()
    }

    private fun choice(label: String) =
        screen.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    private fun enabledCount(count: Int) =
        screen.onNodeWithContentDescription("已启用 $count 个工具，共 2 个")

    @Test
    fun independentlySelectToolsAndKeepChoicesAfterDismissal() {
        openTools()
        enabledCount(2).assertIsDisplayed()
        choice("模型内置搜索").assertIsSelected()
        choice("禁用").performClick().assertIsSelected()
        choice("模型内置搜索").assertIsNotSelected()
        enabledCount(1).assertIsDisplayed()
        screen.onNode(hasText("网络搜索") and hasText("禁用")).performClick()

        screen.onNode(hasText("图片生成") and hasText("自定义图片生成")).performClick()
        choice("自定义图片生成").assertIsSelected()
        choice("禁用").performClick()
        enabledCount(0).assertIsDisplayed()
        choice("模型内置图片生成").performClick().assertIsSelected()
        choice("自定义图片生成").assertIsNotSelected()
        enabledCount(1).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        screen.onNodeWithText("Tools").assertDoesNotExist()

        openTools()
        enabledCount(1).assertIsDisplayed()
        choice("禁用").assertIsSelected()
        screen.onNode(hasText("图片生成") and hasText("模型内置图片生成")).performClick()
        choice("模型内置图片生成").assertIsSelected()
    }
}
