package io.hydrion.hub

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSearchTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun openSettings() {
        screen.onNodeWithContentDescription("打开侧栏").performClick()
        screen.onNodeWithTag("drawer-settings").performClick()
        search().assertIsDisplayed()
    }

    private fun search() = screen.onNode(hasContentDescription("搜索设置") and hasSetTextAction())
    private fun row(label: String) = hasText(label) and hasClickAction()

    private fun scrollingList() = screen.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasScrollAction()
    )

    private fun revealRow(label: String): SemanticsNodeInteraction {
        val matcher = row(label)
        if (screen.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            scrollingList().performScrollToNode(matcher)
        }
        return screen.onNode(matcher).performScrollTo().assertIsDisplayed()
    }

    private fun assertSearchClosed() {
        search().assertIsNotFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        screen.onNodeWithContentDescription("退出搜索").assertDoesNotExist()
        screen.onNode(row("聊天模型")).assertExists()
    }

    @Test fun settingsHeaderCirclesAlignWithChatCapsuleEdgesAndRetainTouchTargets() {
        assumeTrue(screen.activity.resources.configuration.fontScale == 1f)
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val conversationId = screen.runOnIdle {
            vm.newConversation()
            vm.setDraft("顶栏位置")
            vm.send("Hydrion")
            vm.stop()
            vm.state.value.current.id
        }
        try {
            fun capsule(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            val menu = capsule("header-drawer-capsule").fetchSemanticsNode().boundsInWindow
            val actions = capsule("header-actions-capsule").fetchSemanticsNode().boundsInWindow
            openSettings()
            for (label in listOf("返回", "关于应用")) {
                screen.onNode(hasContentDescription(label) and hasClickAction()).assertIsDisplayed()
                    .assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
            }
            fun circle(tag: String) = capsule(tag).assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
                .fetchSemanticsNode().boundsInWindow
            val back = circle("settings-back-circle")
            val info = circle("settings-info-circle")
            val menuIsLeft = menu.center.x < actions.center.x
            assertEquals("menu outer edge", if (menuIsLeft) menu.left else menu.right,
                if (menuIsLeft) back.left else back.right, 1f)
            assertEquals("actions outer edge", if (menuIsLeft) actions.right else actions.left,
                if (menuIsLeft) info.right else info.left, 1f)
            for ((expected, actual) in listOf(menu to back, actions to info)) {
                assertEquals("top", expected.top, actual.top, 1f)
                assertEquals("bottom", expected.bottom, actual.bottom, 1f)
            }
        } finally {
            screen.runOnIdle { vm.deleteConversation(conversationId) }
        }
    }

    @Test fun filtersChineseAndEnglishAndExitsWithClearOrBack() {
        openSettings()
        screen.onNode(row("关于 HydrionHub")).assertDoesNotExist()
        search().performClick()
        search().assertIsFocused()
        screen.onNodeWithContentDescription("退出搜索").assertIsDisplayed()

        search().performTextReplacement("image")
        screen.onNode(row("图像生成模型")).assertIsDisplayed()
        screen.onNode(row("聊天模型")).assertDoesNotExist()
        screen.onNode(row("消息样式")).assertDoesNotExist()

        search().performTextReplacement("图片模型供应商")
        screen.onNode(row("图像生成模型")).assertIsDisplayed()
        search().performTextReplacement("语言模型供应商")
        screen.onNode(row("聊天模型")).assertIsDisplayed()

        search().performTextReplacement("关于")
        screen.onNode(row("关于 HydrionHub")).assertIsDisplayed()
        screen.onNode(row("图像生成模型")).assertDoesNotExist()

        search().performTextReplacement("no-such-setting-008")
        screen.onNodeWithText("没有匹配的设置").assertIsDisplayed()
        screen.onNodeWithContentDescription("退出搜索").performClick()
        assertSearchClosed()
        screen.onNodeWithText("没有匹配的设置").assertDoesNotExist()

        search().performClick()
        search().performTextReplacement("消息")
        screen.onNode(row("消息样式")).assertIsDisplayed()
        val backDispatcher = screen.activity.onBackPressedDispatcher
        screen.runOnIdle { backDispatcher.onBackPressed() }
        assertSearchClosed()

        screen.onNodeWithContentDescription("返回").performClick()
        screen.onNodeWithContentDescription("打开侧栏").assertIsDisplayed()
        search().assertDoesNotExist()
    }

    @Test fun infoOpensAboutAndLastRowRemainsAboveTheSearchField() {
        openSettings()
        screen.onNodeWithContentDescription("关于应用").performClick()
        screen.onNode(hasText("关于 HydrionHub") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        screen.onNode(hasText("完成") and hasAnyAncestor(isDialog())).performClick()
        screen.onNode(isDialog()).assertDoesNotExist()

        val last = revealRow("数据管理")
        // Scroll through any trailing space reserved for a floating search bar.
        val list = scrollingList()
        val viewport = list.fetchSemanticsNode().boundsInRoot.height
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, viewport) }
        val rowBounds = last.getUnclippedBoundsInRoot()
        val searchBounds = search().getUnclippedBoundsInRoot()
        assertTrue("末项底部 ${rowBounds.bottom} 应位于搜索框顶部 ${searchBounds.top} 之上",
            rowBounds.bottom <= searchBounds.top)

        revealRow("图像生成模型").performClick()
        screen.onNodeWithText("图像生成模型供应商").assertIsDisplayed()
        screen.onNodeWithContentDescription("添加供应商").assertIsDisplayed()
        screen.onNodeWithContentDescription("返回").performClick()
        search().assertIsDisplayed()
    }
}
