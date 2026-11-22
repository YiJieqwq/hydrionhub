package io.hydrion.hub.ui.messages

import android.graphics.Color.parseColor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.Role
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.R
import io.hydrion.hub.MainActivity
import io.hydrion.hub.model.HubViewModel
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageAppearanceRenderTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private lateinit var previous: MessageStyle
    private val prompt = "样式-" + UUID.randomUUID().toString().take(6)
    private val user = MessageCardStyle("#195D32", "#EC3853", "#FFD4F7")
    private val assistant = MessageCardStyle("#673719", "#1DC5CB", "#FAFF25")

    @Before fun rememberStyle() {
        previous = MessageStyleStore.shared(screen.activity).style.value
    }

    @After fun restore() {
        screen.activityRule.scenario.onActivity { activity ->
            MessageStyleStore.shared(activity).update { previous }
            val vm = ViewModelProvider(activity)[HubViewModel::class.java]
            vm.state.value.history.find { it.title == prompt }?.let { vm.deleteConversation(it.id) }
        }
    }

    @Test fun chatBubblesWrapShortTextAndKeepTheirRoleEdgeInANarrowList() {
        val text = mutableStateOf("Hi")
        screen.runOnUiThread {
            screen.activity.setContent {
                Box(Modifier.fillMaxSize()) {
                    MessageCanvas(MessageStyle(assistantBubble = true), Modifier.width(240.dp).testTag("narrow-list")) {
                        Column(Modifier.fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                UserBubble(Message(role = Role.User, text = text.value), "", Mode.Chat)
                            }
                            AssistantBody(text.value, "", Mode.Chat)
                        }
                    }
                }
            }
        }
        fun bounds(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val list = bounds("narrow-list")
        val user = bounds("message-user-bubble")
        val assistant = bounds("message-assistant-body")
        assertEquals(list.right, user.right, 1f)
        assertEquals(list.left, assistant.left, 1f)
        assertTrue(user.width < list.width / 2 && assistant.width < list.width / 2)

        screen.runOnIdle { text.value = "A longer sentence for the same narrow list. ".repeat(3) }
        val longUser = bounds("message-user-bubble")
        assertEquals(list.right, longUser.right, 1f)
        assertEquals(list.left + with(screen.density) { 24.dp.toPx() }, longUser.left, 1f)
    }

    private fun apply(style: MessageStyle) {
        screen.runOnIdle { MessageStyleStore.shared(screen.activity).update { style } }
    }

    private fun scrollToTurn(index: Int) {
        screen.onNodeWithTag("message-timeline").performScrollToIndex(index)
    }

    private fun pixels(tag: String): Map<Int, Int> {
        val node = screen.onNodeWithTag(tag, useUnmergedTree = true)
        node.performScrollTo().assertIsDisplayed()
        val bitmap = node.captureToImage().asAndroidBitmap()
        val bytes = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(bytes, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return bytes.asIterable().groupingBy { it }.eachCount()
    }

    private fun assertInk(counts: Map<Int, Int>, vararg colors: String) {
        colors.forEach { hex ->
            assertTrue("Missing rendered color $hex", counts.getOrDefault(parseColor(hex), 0) > 2)
        }
    }

    @Test fun customAppearanceReachesActualMessagesAndSettingsPreview() {
        val style = MessageStyle(tone = MessageTone.Light, light = MessagePalette(assistant, user), assistantBubble = true)
        apply(style)
        assertEquals(style, MessageStyleStore(screen.activity).style.value)
        screen.onNodeWithContentDescription("消息输入框").performClick().performTextInput(prompt)
        screen.onNodeWithContentDescription("发送消息").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        screen.waitUntil(20_000) {
            screen.onAllNodes(hasText("本地模拟回复", substring = true)).fetchSemanticsNodes().isNotEmpty() &&
                screen.onAllNodes(hasContentDescription("发送消息")).fetchSemanticsNodes().isNotEmpty()
        }
        scrollToTurn(0)
        assertInk(pixels("message-user-bubble"), user.background, user.border, user.text)
        scrollToTurn(1)
        assertInk(pixels("message-thought"), assistant.background, assistant.border, assistant.text)
        assertInk(pixels("message-assistant-body"), assistant.background, assistant.border, assistant.text)

        apply(style.copy(assistantBubble = false))
        assertInk(pixels("message-assistant-body"), "#FAFAFB", assistant.text)
        apply(style.copy(material = CardMaterial.Default))
        assertInk(pixels("message-thought"), "#F0F1F2", "#C8CBD0")
        apply(style.copy(material = CardMaterial.Frosted))
        val frosted = pixels("message-thought")
        assertInk(frosted, assistant.border, assistant.text)
        assertTrue("Frosted fill must remain translucent and textured",
            frosted.getOrDefault(parseColor(assistant.background), 0) < frosted.values.sum() / 4)

        apply(style)
        screen.onNodeWithContentDescription("打开侧栏").performClick()
        screen.onNodeWithTag("drawer-settings").performClick()
        screen.onNodeWithText(screen.activity.getString(io.hydrion.hub.R.string.settings_style)).performScrollTo().performClick()
        assertInk(pixels("message-user-bubble"), user.background, user.border, user.text)
        assertInk(pixels("message-thought"), assistant.background, assistant.border, assistant.text)
        assertInk(pixels("message-assistant-body"), assistant.background, assistant.border, assistant.text)
    }
}
