package io.hydrion.hub.ui.messages

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import io.hydrion.hub.R
import io.hydrion.hub.MainActivity
import io.hydrion.hub.model.HubViewModel
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    private val userCopy get() = hasContentDescription(screen.activity.getString(R.string.turn_copy))
    private val assistantMenu get() = hasContentDescription(screen.activity.getString(R.string.turn_more_reply))

    @After
    fun removeTestConversation() {
        screen.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[HubViewModel::class.java]
            vm.state.value.history.filter { it.title == prompt || it.title == "$prompt (1)" }.forEach { vm.deleteConversation(it.id) }
        }
    }

    // A long or enlarged reply may put its buttons and the user bubble on different screens.
    private fun turnControl(turnIndex: Int, matcher: SemanticsMatcher): SemanticsNodeInteraction {
        screen.onNodeWithTag("message-timeline")
            .performScrollToIndex(turnIndex)
        return screen.onNode(matcher and hasAnyAncestor(hasTestTag("message-timeline"))).performScrollTo().assertIsDisplayed()
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
    fun branchAtFirstReplyKeepsTheSourceSuffixAndDraft() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        fun sendLocal(text: String) {
            screen.runOnIdle { vm.setDraft(text); vm.send("Local fixture") }
            screen.waitUntil(20_000) { !vm.state.value.isGenerating }
        }
        screen.runOnIdle { vm.newConversation() }
        sendLocal(prompt)
        sendLocal("later fixture")
        screen.runOnIdle { vm.setDraft("parked draft") }
        val original = vm.state.value.current
        val reply = original.messages[1]
        turnControl(1, assistantMenu and hasAnyAncestor(hasTestTag("message-turn-${reply.id}"))).performClick()
        screen.onNodeWithText(screen.activity.getString(R.string.turn_fork)).performScrollTo().performClick()
        screen.waitUntil(5_000) { vm.state.value.current.id != original.id }
        val branch = vm.state.value.current
        assertEquals("$prompt (1)", branch.title)
        assertEquals(original.messages.take(2).map { it.text }, branch.messages.map { it.text })
        assertNotEquals(reply.id, branch.messages.last().id)
        assertEquals(branch.messages.first().id, branch.messages.last().replyTo)
        screen.runOnIdle { vm.openConversation(original.id) }
        assertEquals(original, vm.state.value.current)
        assertEquals("parked draft", vm.state.value.draft.text)
    }

    @Test
    fun thoughtAndTurnActionsUseTheEditedPromptOnRetry() {
        screen.onNodeWithContentDescription("消息输入框").performClick().performTextInput(prompt)
        screen.onNodeWithContentDescription("发送消息").assertIsEnabled().performClick()
        awaitReply(prompt)

        val thought = hasText(screen.activity.getString(R.string.turn_thinking)) and hasClickAction()
        val details = hasText("已读取当前消息与附件", substring = true)
        turnControl(1, thought)
        screen.onNode(details).assertDoesNotExist()
        turnControl(1, thought).performClick()
        turnControl(1, details)
        turnControl(1, thought).performClick()
        screen.onNode(details).assertDoesNotExist()

        turnControl(0, userCopy).performClick()
        awaitClipboard { it == prompt }
        turnControl(0, hasTestTag("message-user-bubble")).performClick()
        screen.onNode(hasSetTextAction() and hasText(prompt)).performTextReplacement("discarded")
        screen.onNodeWithText(screen.activity.getString(R.string.settings_cancel)).performClick()
        turnControl(0, hasText(prompt))
        turnControl(0, hasContentDescription(screen.activity.getString(R.string.turn_edit))).performClick()
        screen.onNode(hasSetTextAction() and hasText(prompt)).performTextReplacement(edited)
        screen.onNodeWithText(screen.activity.getString(R.string.settings_save)).assertIsEnabled().performClick()
        turnControl(0, hasText(edited))

        turnControl(1, hasContentDescription(screen.activity.getString(R.string.turn_retry))).assertIsEnabled().performClick()
        screen.onNodeWithText(screen.activity.getString(R.string.settings_cancel)).performClick()
        turnControl(1, hasText("「$prompt」", substring = true))
        turnControl(1, hasContentDescription(screen.activity.getString(R.string.turn_retry))).performClick()
        screen.onNodeWithText(screen.activity.getString(R.string.turn_retry)).performClick()
        awaitReply(edited)
        val replyId = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java].state.value.current.messages.last().id }
        val inReply = hasAnyAncestor(hasTestTag("message-turn-$replyId"))
        turnControl(1, inReply and hasContentDescription(screen.activity.getString(R.string.turn_previous))).performClick()
        turnControl(1, inReply and hasText("「$prompt」", substring = true))
        turnControl(1, inReply and hasContentDescription(screen.activity.getString(R.string.turn_next))).performClick()
        turnControl(1, hasText("「$edited」", substring = true))
        turnControl(1, hasContentDescription(screen.activity.getString(R.string.turn_copy_reply))).performClick()
        awaitClipboard { it.contains("「$edited」") }

        turnControl(1, assistantMenu).performClick()
        screen.onNodeWithText(screen.activity.getString(R.string.turn_fork)).assertIsDisplayed()
        screen.onNodeWithText(screen.activity.getString(R.string.turn_delete_version)).performScrollTo().performClick()
        screen.onNodeWithText(screen.activity.getString(R.string.turn_confirm_one)).assertIsDisplayed()
        screen.onNodeWithText(screen.activity.getString(R.string.settings_cancel)).performClick()
        turnControl(1, hasText("「$edited」", substring = true))
    }
}
