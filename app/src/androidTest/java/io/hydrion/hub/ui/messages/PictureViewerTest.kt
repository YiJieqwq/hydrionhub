package io.hydrion.hub.ui.messages

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Role
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PictureViewerTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun tappingTogglesBothControlBarsAndPagingRestoresThem() {
        val keys = listOf("viewer-demo-first", "viewer-demo-second")
        val msg = Message(role = Role.Assistant, prompt = "Viewer test prompt", imageKeys = keys)
        val size = Extent(96, 72)
        val sizes = keys.associateWith { size }
        val open = mutableStateOf(true)
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    if (open.value) PictureViewer(msg, size, sizes, initial = 0,
                        onReference = { error("Unexpected reference action") }, onDismiss = { open.value = false })
                }
            }
        }

        val hide = screen.activity.getString(R.string.turn_hide_image_controls)
        val show = screen.activity.getString(R.string.turn_show_image_controls)
        fun image(page: Int, label: String) = hasContentDescription("大图，第 $page 张") and
            SemanticsMatcher("image click action labeled $label") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == label
            }
        fun assertControlsShown(page: Int) {
            screen.onNodeWithText("$page / 2").assertIsDisplayed()
            screen.onNodeWithContentDescription("复制图片").assertIsDisplayed()
            screen.onNodeWithContentDescription("保存图片").assertIsDisplayed()
            screen.onNodeWithText(msg.prompt).assertIsDisplayed()
            screen.onNodeWithText("作为参考图").assertIsDisplayed()
        }

        assertControlsShown(1)
        screen.waitUntil(5_000) { screen.onAllNodes(image(1, hide)).fetchSemanticsNodes().isNotEmpty() }
        screen.onNode(image(1, hide)).performClick()
        screen.onNodeWithText("1 / 2").assertDoesNotExist()
        screen.onNodeWithContentDescription("复制图片").assertDoesNotExist()
        screen.onNodeWithContentDescription("保存图片").assertDoesNotExist()
        screen.onNodeWithText(msg.prompt).assertDoesNotExist()
        screen.onNodeWithText("作为参考图").assertDoesNotExist()

        screen.onNode(image(1, show)).performClick()
        assertControlsShown(1)
        screen.onNode(image(1, hide)).performClick()
        screen.onNode(image(1, show)).performTouchInput { swipeLeft() }
        assertControlsShown(2)
        screen.waitUntil(5_000) { screen.onAllNodes(image(2, hide)).fetchSemanticsNodes().isNotEmpty() }
        screen.onNodeWithContentDescription("关闭大图").performClick()
        screen.onNode(isDialog()).assertDoesNotExist()
    }
}
