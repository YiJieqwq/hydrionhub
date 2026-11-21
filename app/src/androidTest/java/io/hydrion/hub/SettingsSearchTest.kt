package io.hydrion.hub

import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt
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
import io.hydrion.hub.R
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

    @Test fun darkGroupHasOneOutlineAndAMatchingDivider() {
        openSettings()
        val row = revealRow(screen.activity.getString(R.string.settings_style))
        val bounds = row.fetchSemanticsNode().boundsInRoot
        val image = screen.onRoot().captureToImage().asAndroidBitmap()
        val x = bounds.left.roundToInt()
        val y = bounds.center.y.roundToInt()
        val density = screen.activity.resources.displayMetrics.density
        val line = Color.rgb(79, 79, 79)
        val thickness = kotlin.math.ceil(density).toInt()
        val fill = Color.rgb(48, 48, 48)
        val edge = bounds.bottom.roundToInt()
        val center = bounds.center.x.roundToInt()
        for (i in 0 until thickness) {
            assertEquals(line, image.getPixel(x + i, y))
            assertEquals(line, image.getPixel(center, edge - 1 - i))
        }
        assertEquals(fill, image.getPixel(x + thickness, y))
        assertEquals(fill, image.getPixel(center, edge - 1 - thickness))
        assertEquals(fill, image.getPixel(center, edge))
        assertEquals(54f * density, bounds.height, 1f)
    }

    @Test fun systemKeyboardDismissalFoldsSearchAndPreservesTheUnfilteredProfilePosition() {
        openSettings()
        val profile = screen.onNodeWithTag("settings-profile")
        val initial = profile.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        val density = screen.activity.resources.displayMetrics.density
        fun keyboardVisible() = screen.runOnUiThread {
            ViewCompat.getRootWindowInsets(screen.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        fun closeKeyboard() {
            val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
            ParcelFileDescriptor.AutoCloseInputStream(shell).use { it.readBytes() }
            screen.waitUntil(10_000) { !keyboardVisible() }
        }
        repeat(2) {
            val field = search().fetchSemanticsNode().boundsInRoot
            val list = scrollingList().fetchSemanticsNode().boundsInRoot
            assertEquals(34f * density, field.left - list.left, 1f)
            assertEquals(34f * density, list.right - field.right, 1f)
            search().performClick()
            screen.waitUntil(10_000) { keyboardVisible() }
            search().assertIsFocused().assertHeightIsEqualTo(48.dp)
            assertEquals(initial, profile.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
            closeKeyboard()
            assertSearchClosed()
        }
        search().performClick()
        screen.waitUntil(10_000) { keyboardVisible() }
        search().performTextReplacement("image")
        closeKeyboard()
        search().assertIsNotFocused().assertTextContains("image")
        profile.assertDoesNotExist()
        search().performClick()
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.settings_end_search)).performClick()
        assertSearchClosed()
        profile.assertIsDisplayed()
    }

    @Test fun focusedSearchFloatsOverTheListWithAnOpenScrollableGap() {
        openSettings()
        search().performClick().assertIsFocused()
        val list = scrollingList()
        val clear = screen.onNode(hasContentDescription(screen.activity.getString(R.string.settings_end_search)) and hasClickAction())
        clear.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val field = search().fetchSemanticsNode().boundsInRoot
        val close = clear.fetchSemanticsNode().boundsInRoot
        val view = list.fetchSemanticsNode().boundsInRoot
        val space = 12f * screen.activity.resources.displayMetrics.density
        val (left, right) = listOf(field, close).sortedBy { it.left }
        assertEquals(space, left.left - view.left, 1f)
        assertEquals(space, view.right - right.right, 1f)
        assertEquals(space, right.left - left.right, 1f)
        assertTrue("List viewport extends underneath both capsules", view.bottom >= maxOf(field.bottom, close.bottom))

        val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val before = range.value()
        val start = Offset((left.right + right.left) / 2 - view.left, field.center.y - view.top)
        list.performTouchInput { swipe(start, Offset(start.x, start.y / 2)) }
        screen.waitForIdle()
        assertTrue("Swiping between the capsules scrolls the underlying list", range.value() > before)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue()) }
        val last = screen.onNode(row(screen.activity.getString(R.string.settings_data))).fetchSemanticsNode().boundsInRoot
        assertTrue(last.height > 0f && last.bottom <= search().fetchSemanticsNode().boundsInRoot.top)
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
            "登录账户" to R.string.settings_signed_in_accounts,
            "signed-in accounts" to R.string.settings_signed_in_accounts,
            "containers" to R.string.settings_environments,
            "Skill" to R.string.settings_skill,
        )
        for ((query, id) in previews) {
            search().performTextReplacement(query)
            screen.onNodeWithText(screen.activity.getString(id)).assertIsDisplayed()
                .assertIsNotEnabled().assertHasNoClickAction()
        }
        for ((query, id) in listOf(
            "记忆" to R.string.settings_memory,
            "mCp" to R.string.settings_mcp,
            "默认模型" to R.string.settings_defaults, "web search" to R.string.settings_web_search)) {
            search().performTextReplacement(query)
            screen.onNodeWithText(screen.activity.getString(id)).assertIsDisplayed().assertHasClickAction()
        }
        search().performTextReplacement("assistant")
        screen.onNodeWithText(screen.activity.getString(R.string.settings_assistants)).assertHasClickAction()
        search().performTextReplacement("消息样式")
        screen.onNodeWithText(screen.activity.getString(R.string.settings_style)).assertHasClickAction()
    }

    @Test fun defaultModelsAndSearchProvidersOpenAndCancelWithoutChangingConfiguration() {
        fun label(id: Int) = screen.activity.getString(id)
        openSettings()
        revealRow(label(R.string.settings_defaults)).performClick()
        screen.onNodeWithText(label(R.string.settings_chat)).assertExists()
        screen.onNodeWithText(label(R.string.settings_image)).assertExists()
        screen.onNodeWithContentDescription(label(R.string.settings_back)).performClick()
        revealRow(label(R.string.settings_web_search)).performClick()
        screen.onNodeWithText(label(R.string.search_local_demo)).assertIsDisplayed()
        screen.onNodeWithContentDescription(label(R.string.model_settings_add_provider)).performClick()
        screen.onNode(hasText(label(R.string.search_provider_name)) and hasSetTextAction()).performTextReplacement("Unsaved fixture")
        screen.onNodeWithContentDescription(label(R.string.settings_save)).performClick()
        screen.onNodeWithText(label(R.string.search_invalid)).assertIsDisplayed()
        screen.onNodeWithContentDescription(label(R.string.settings_back)).performClick()
        screen.onNodeWithText(label(R.string.model_settings_drop)).performClick()
        screen.onNodeWithText("Unsaved fixture").assertDoesNotExist()
        screen.onNodeWithText(label(R.string.search_local_demo)).assertIsDisplayed()
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
        screen.onNodeWithText(screen.activity.getString(R.string.model_settings_image_providers)).assertIsDisplayed()
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.model_settings_add_provider)).assertIsDisplayed()
        screen.onNodeWithContentDescription(screen.activity.getString(R.string.settings_back)).performClick()
        search().assertIsDisplayed()
    }
}
