package io.hydrion.hub.ui.messages

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.MainActivity
import io.hydrion.hub.model.HubViewModel
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatTurnActionsTest {
    @get:Rule
    val screen = createAndroidComposeRule<MainActivity>()

    private val prompt = "初稿-" + UUID.randomUUID().toString().take(8)
    private val edited = prompt.replace("初稿", "修订")
    private val userCopy = hasContentDescription("复制消息")
    private val assistantMenu = hasContentDescription("回复操作")

    @After
    fun removeTestConversation() {
        screen.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[HubViewModel::class.java]
            vm.state.value.history.firstOrNull { it.title == prompt }?.let { vm.deleteConversation(it.id) }
        }
    }

    // A long or enlarged reply may put its buttons and the user bubble on different screens.
    private fun turnControl(turnIndex: Int, matcher: SemanticsMatcher): SemanticsNodeInteraction {
        screen.onNode(hasScrollAction() and hasAnyDescendant(userCopy or assistantMenu))
            .performScrollToIndex(turnIndex)
        return screen.onNode(matcher).performScrollTo().assertIsDisplayed()
    }

    private fun awaitReply(expectedPrompt: String) {
        screen.waitUntil(20_000) {
            screen.onAllNodes(hasText("「$expectedPrompt」", substring = true)).fetchSemanticsNodes().isNotEmpty() &&
                screen.onAllNodes(hasContentDescription("发送消息")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitClipboard(matches: (String) -> Boolean) {
        val clipboard = screen.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        screen.waitUntil(5_000) {
            val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            item?.text?.toString()?.let(matches) == true
        }
    }

    @Test
    fun thoughtAndTurnActionsUseTheEditedPromptOnRetry() {
        screen.onNodeWithContentDescription("消息输入框").performClick().performTextInput(prompt)
        screen.onNodeWithContentDescription("发送消息").assertIsEnabled().performClick()
        awaitReply(prompt)

        val thought = hasText("思考过程") and hasClickAction()
        val details = hasText("已读取当前消息与附件", substring = true)
        turnControl(1, thought)
        screen.onNode(details).assertDoesNotExist()
        turnControl(1, thought).performClick()
        turnControl(1, details)
        turnControl(1, thought).performClick()
        screen.onNode(details).assertDoesNotExist()

        turnControl(0, userCopy).performClick()
        awaitClipboard { it == prompt }
        turnControl(0, hasContentDescription("编辑消息")).performClick()
        screen.onNode(hasSetTextAction() and hasText(prompt)).performTextReplacement(edited)
        screen.onNodeWithText("保存").assertIsEnabled().performClick()
        turnControl(0, hasText(edited))

        turnControl(1, hasContentDescription("重新生成回复")).assertIsEnabled().performClick()
        awaitReply(edited)
        turnControl(1, hasContentDescription("复制回复")).performClick()
        awaitClipboard { it.contains("「$edited」") }

        turnControl(1, assistantMenu).performClick()
        screen.onNodeWithText("重新生成").assertDoesNotExist()
        screen.onNodeWithText("删除消息").performClick()
        screen.onNodeWithText("删除这条回复？").assertIsDisplayed()
        screen.onNodeWithText("取消").performClick()
        turnControl(1, hasText("「$edited」", substring = true))
    }
}
