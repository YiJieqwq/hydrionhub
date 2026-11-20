package io.hydrion.hub

import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushDrawerTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun node(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    @Test fun dragMovesTheFullWidthChatAndOnlyTheExposedStripDismissesTheDrawer() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val draft = "尚未发送的草稿"
        val (conversation, reply) = screen.runOnIdle {
            vm.newConversation()
            vm.setMode(Mode.Chat)
            vm.setDraft("侧栏位置")
            vm.send("Hydrion")
            vm.stop()
            val reply = vm.state.value.current.messages.last().id
            vm.editMessage(reply, (1..70).joinToString("\n") { "第 $it 行" })
            vm.setDraft(draft)
            vm.state.value.current.id to reply
        }
        try {
            val timeline = screen.onNodeWithTag("message-timeline", useUnmergedTree = true)
            val root = screen.onRoot()
            timeline.performScrollToIndex(0)
            val scrollBy = timeline.fetchSemanticsNode().size.height * .5f
            timeline.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scrollBy) }
            val replyTop = node("message-turn-$reply").positionInRoot.y
            val chat = node("drawer-workspace")
            val origin = chat.positionInRoot
            val width = chat.size.width
            val rtl = screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val sign = if (rtl) -1f else 1f
            // Sample solid menu chrome away from the glyph, border and system bars.
            val menu = node("header-drawer-capsule").boundsInRoot
            val sampleX = menu.center.x
            val sampleY = (menu.top + menu.height * .77f).roundToInt()
            val originalPixel = root.captureToImage().asAndroidBitmap().getPixel(sampleX.roundToInt(), sampleY)

            // Hold the finger still so both sides can be sampled during the very same drag.
            root.performTouchInput {
                down(Offset(width * (if (rtl) .96f else .04f), center.y))
                val step = minOf(width * .12f, 50.dp.toPx()) * sign
                moveBy(Offset(step, 0f))
                moveBy(Offset(step, 0f))
            }
            try {
                val sheet = node("drawer-sheet")
                val middle = node("drawer-workspace")
                val distance = sign * (middle.positionInRoot.x - origin.x)
                assertTrue("Drag should expose part of the sheet", distance > 1 && distance < sheet.size.width - 1)
                val boundary = if (rtl) sheet.positionInRoot.x else sheet.positionInRoot.x + sheet.size.width
                val chatEdge = middle.positionInRoot.x + if (rtl) width else 0
                assertEquals("No gap or overlap", boundary, chatEdge, 1f)
                assertEquals(width, middle.size.width)
                assertEquals(origin.y, middle.positionInRoot.y, 1f)
            } finally {
                root.performTouchInput {
                    moveTo(Offset(width * (if (rtl) .08f else .92f), center.y))
                    up()
                }
            }

            screen.onNodeWithText("设置").assertIsDisplayed()
            val sheet = node("drawer-sheet")
            val open = node("drawer-workspace")
            val openX = open.positionInRoot.x
            assertEquals(width, open.size.width)
            assertEquals(sheet.size.width.toFloat(), sign * (openX - origin.x), 1f)
            val cap = 348f * screen.activity.resources.displayMetrics.density
            assertEquals(minOf(width * .75f, cap), sheet.size.width.toFloat(), 1f)
            screen.onNode(hasContentDescription("关闭侧栏") and hasClickAction()).assertIsDisplayed()
            screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction()).assertDoesNotExist()
            val translatedX = (sampleX + openX - origin.x).roundToInt()
            if (translatedX in 0 until width) {
                assertEquals("Exposed chat keeps its original color", originalPixel,
                    root.captureToImage().asAndroidBitmap().getPixel(translatedX, sampleY))
            }
            screen.onNodeWithText("HydrionHub").performTouchInput { click(center) }
            assertEquals(openX, node("drawer-workspace").positionInRoot.x, 1f)

            root.performTouchInput {
                val strip = (width - sheet.size.width) / 2f
                click(Offset(if (rtl) strip else width - strip, center.y))
            }
            screen.onNodeWithContentDescription("打开侧栏").assertIsDisplayed()
            assertEquals(origin, node("drawer-workspace").positionInRoot)
            assertEquals(replyTop, node("message-turn-$reply").positionInRoot.y, 1f)
            screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction()).assertTextContains(draft)
            screen.runOnIdle { assertEquals(conversation, vm.state.value.current.id) }
        } finally {
            screen.runOnIdle { vm.deleteConversation(conversation) }
        }
    }
}
