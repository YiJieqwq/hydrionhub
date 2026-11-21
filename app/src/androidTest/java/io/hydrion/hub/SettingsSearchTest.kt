package io.hydrion.hub

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val chat = screen.onNodeWithTag("drawer-new-chat").assertIsDisplayed()
        val avatar = screen.onNodeWithTag("drawer-settings").assertIsDisplayed()
        assertEquals(chat.fetchSemanticsNode().boundsInRoot.center.y, avatar.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        avatar.performClick()
        search().assertIsDisplayed()
    }

    private fun search() = screen.onNode(hasContentDescription(screen.activity.getString(R.string.settings_search)) and hasSetTextAction())
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
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.settings_end_search)).assertDoesNotExist()
        screen.onNode(row(screen.activity.getString(R.string.settings_chat))).assertExists()
    }

    @Test fun settingsHeaderUsesCompactCirclesAndFullSizeClickTargets() {
        assumeTrue(screen.activity.resources.configuration.fontScale == 1f)
        openSettings()
        for (id in listOf(R.string.settings_back, R.string.settings_info)) {
            screen.onNode(hasContentDescription(screen.activity.getString(id)) and hasClickAction()).assertIsDisplayed()
                .assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        }
        fun circle(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true)
            .assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp).fetchSemanticsNode().boundsInWindow
        assertEquals(circle("settings-back-circle").center.y, circle("settings-info-circle").center.y, 1f)
    }

    @Test fun filtersChineseAndEnglishAndExitsWithClearOrBack() {
        openSettings()
        screen.onNode(row(screen.activity.getString(R.string.settings_about))).assertDoesNotExist()
        search().performClick()
        search().assertIsFocused()
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.settings_end_search)).assertIsDisplayed()

        search().performTextReplacement("image")
        screen.onNode(row(screen.activity.getString(R.string.settings_image))).assertIsDisplayed()
        screen.onNode(row(screen.activity.getString(R.string.settings_chat))).assertDoesNotExist()
        screen.onNode(row(screen.activity.getString(R.string.settings_style))).assertDoesNotExist()

        search().performTextReplacement("图片模型供应商")
        screen.onNode(row(screen.activity.getString(R.string.settings_image))).assertIsDisplayed()
        search().performTextReplacement("语言模型供应商")
        screen.onNode(row(screen.activity.getString(R.string.settings_chat))).assertIsDisplayed()

        search().performTextReplacement("关于")
        screen.onNode(row(screen.activity.getString(R.string.settings_about))).assertIsDisplayed()
        screen.onNode(row(screen.activity.getString(R.string.settings_image))).assertDoesNotExist()

        search().performTextReplacement("no-such-setting-008")
        screen.onNodeWithText(screen.activity.getString(R.string.settings_no_matches)).assertIsDisplayed()
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.settings_end_search)).performClick()
        assertSearchClosed()
        screen.onNodeWithText(screen.activity.getString(R.string.settings_no_matches)).assertDoesNotExist()

        search().performClick()
        search().performTextReplacement("消息")
        screen.onNode(row(screen.activity.getString(R.string.settings_style))).assertIsDisplayed()
        val backDispatcher = screen.activity.onBackPressedDispatcher
        screen.runOnIdle { backDispatcher.onBackPressed() }
        assertSearchClosed()

        screen.onNodeWithContentDescription(screen.activity.getString(R.string.settings_back)).performClick()
        screen.onNodeWithContentDescription("打开侧栏").assertIsDisplayed()
        search().assertDoesNotExist()
    }

    @Test fun previewRowsCanBeFoundWithoutOfferingNavigation() {
        openSettings()
        search().performClick()
        val previews = listOf(
            "assistant" to R.string.settings_assistants,
            "默认模型" to R.string.settings_defaults,
            "web search" to R.string.settings_web_search,
            "记忆" to R.string.settings_memory,
            "containers" to R.string.settings_environments,
            "mCp" to R.string.settings_mcp,
            "Skill" to R.string.settings_skill,
        )
        for ((query, id) in previews) {
            search().performTextReplacement(query)
            screen.onNodeWithText(screen.activity.getString(id)).assertIsDisplayed()
                .assertIsNotEnabled().assertHasNoClickAction()
        }
        search().performTextReplacement("消息样式")
        screen.onNodeWithText(screen.activity.getString(R.string.settings_style)).assertHasClickAction()
    }

    @Test fun infoOpensAboutAndLastRowRemainsAboveTheSearchField() {
        openSettings()
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.settings_info)).performClick()
        screen.onNode(hasText("关于 HydrionHub") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        screen.onNode(hasText("完成") and hasAnyAncestor(isDialog())).performClick()
        screen.onNode(isDialog()).assertDoesNotExist()

        val last = revealRow(screen.activity.getString(R.string.settings_data))
        // Scroll through any trailing space reserved for a floating search bar.
        val list = scrollingList()
        val viewport = list.fetchSemanticsNode().boundsInRoot.height
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, viewport) }
        val rowBounds = last.getUnclippedBoundsInRoot()
        val searchBounds = search().getUnclippedBoundsInRoot()
        assertTrue("末项底部 ${rowBounds.bottom} 应位于搜索框顶部 ${searchBounds.top} 之上",
            rowBounds.bottom <= searchBounds.top)

        revealRow(screen.activity.getString(R.string.settings_image)).performClick()
        screen.onNodeWithText("图像生成模型供应商").assertIsDisplayed()
        screen.onNodeWithContentDescription("添加供应商").assertIsDisplayed()
        screen.onNodeWithContentDescription("返回").performClick()
        search().assertIsDisplayed()
    }
}
