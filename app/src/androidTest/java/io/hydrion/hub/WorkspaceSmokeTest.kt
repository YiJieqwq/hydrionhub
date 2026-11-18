package io.hydrion.hub

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceSmokeTest {
    @get:Rule
    val screen = createAndroidComposeRule<MainActivity>()

    private fun modeTab(mode: String) =
        hasText(mode) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun awaitNode(matcher: SemanticsMatcher) {
        screen.waitUntil(timeoutMillis = 20_000) {
            screen.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun send(prompt: String) {
        val input = screen.onNodeWithContentDescription("消息输入框")
        input.performClick()
        input.performTextInput(prompt)
        screen.onNodeWithContentDescription("发送消息").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun deleteCurrentConversation() {
        screen.onNodeWithContentDescription("更多会话操作").performClick()
        screen.onNodeWithText("删除会话").performClick()
        screen.onNodeWithText("删除").performClick()
        screen.onNode(modeTab("Chat")).assertIsSelected()
    }

    @Test
    fun emptyModeSwitchChatSendAndPinnedHistory() {
        val prompt = "冒烟对话-" + UUID.randomUUID().toString().take(8)
        screen.onNode(modeTab("Chat")).assertIsSelected()
        screen.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
        screen.onNode(modeTab("Image")).performClick().assertIsSelected()
        screen.onNode(modeTab("Chat")).performClick()
        send(prompt)

        awaitNode(hasText("本地模拟回复", substring = true))
        screen.onNode(hasText(prompt) and hasText("Chat") and hasText("0.5k") and
            hasContentDescription("已锁定")).assertIsDisplayed()
        screen.onAllNodes(hasText("上下文", substring = true)).assertCountEquals(0)
        screen.onAllNodes(modeTab("Image")).assertCountEquals(0)
        screen.onNodeWithContentDescription("打开侧栏").performClick()
        screen.onNodeWithContentDescription("管理 " + prompt).performClick()
        screen.onNodeWithText("置顶").performClick()
        screen.onNodeWithText("置顶会话").assertIsDisplayed()
        screen.onNode(hasContentDescription("新建会话") and hasAnySibling(hasText("HydrionHub"))).performClick()
        screen.onNode(modeTab("Chat")).assertIsSelected()
        screen.onNodeWithContentDescription("打开侧栏").performClick()
        // Now switch from the fresh draft back into the pinned, mode-locked conversation.
        screen.onNode(hasText(prompt) and hasContentDescription("Chat") and hasClickAction()).performClick()
        screen.onAllNodes(modeTab("Image")).assertCountEquals(0)
        deleteCurrentConversation()
    }

    @Test
    fun imageGenerationAllowsReferenceOnlyFollowup() {
        screen.onNode(modeTab("Image")).performClick()
        send("冒烟图片-" + UUID.randomUUID().toString().take(8))

        awaitNode(hasText("作为参考图"))
        screen.onNode(hasText("Image") and hasText("0.0k") and hasContentDescription("已锁定")).assertIsDisplayed()
        screen.onAllNodes(modeTab("Chat")).assertCountEquals(0)
        screen.onNodeWithText("作为参考图").performClick()
        screen.onNodeWithContentDescription("移除 参考图").assertExists()
        screen.onNodeWithContentDescription("发送消息").assertIsEnabled()
        screen.onNodeWithContentDescription("移除 参考图").performClick()
        screen.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
        deleteCurrentConversation()
    }

    @Test
    fun configureHidesUnsupportedModelParameters() {
        screen.onNodeWithContentDescription("消息输入框").performClick()
        screen.onNodeWithContentDescription("配置模型").performClick()
        val sheet = hasScrollAction() and hasAnyDescendant(hasText("Configure"))
        screen.onNode(sheet).performScrollToNode(hasText("Reset to default"))
        screen.onNodeWithText("Reset to default").performClick()
        screen.onNodeWithText("Effort").assertExists()
        screen.onNodeWithText("Speed").assertExists()

        screen.onNodeWithText("Model").performClick()
        screen.onNodeWithText("Hydrion Mini · 本机语言模型").performClick()
        screen.onNodeWithText("Effort").assertDoesNotExist()
        screen.onNodeWithText("Speed").assertDoesNotExist()
        screen.onNodeWithText("Reset to default").performClick()
        screen.onNodeWithText("Effort").assertExists()
        screen.onNode(sheet).performScrollToNode(hasText("Done"))
        screen.onNodeWithText("Done").performClick()
        screen.onNodeWithText("Configure").assertDoesNotExist()
    }
}
