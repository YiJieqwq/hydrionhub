package io.hydrion.hub

import android.graphics.Color
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.ui.composer.AwaitToolsImeDismissal
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerToolsTest {
    @get:Rule
    val screen = createAndroidComposeRule<MainActivity>()

    private fun openTools(checkColors: Boolean = false) {
        screen.onNodeWithContentDescription("消息输入框").performClick()
        screen.waitUntil(10_000) { screen.onNodeWithContentDescription("打开工具箱").isDisplayed() }
        if (checkColors) {
            screen.onNodeWithContentDescription("添加附件").assertGlyphGray("添加附件", 255)
            screen.onNodeWithContentDescription("打开工具箱").assertGlyphGray("打开工具箱", 175)
            screen.onNodeWithContentDescription("工作区终端").assertGlyphGray("工作区终端", 175)
        }
        screen.onNodeWithContentDescription("打开工具箱").assertIsEnabled().performTouchInput { click(center) }
        screen.waitUntil(10_000) { screen.onNodeWithText("Tools").isDisplayed() }
    }

    private fun choice(label: String) =
        screen.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    private fun enabledCount(count: Int) =
        screen.onNodeWithContentDescription("已启用 $count 个工具，共 2 个")

    @Test fun handoffWaitsForImeDismissalAndDropsCancelledRequests() {
        val pending = mutableStateOf(false)
        val imeActive = mutableStateOf(true)
        val eligible = mutableStateOf(true)
        val decisions = mutableListOf<Boolean>()
        screen.runOnUiThread {
            screen.activity.setContent {
                if (pending.value) {
                    AwaitToolsImeDismissal(imeActive = imeActive.value, eligible = eligible.value) { present ->
                        decisions += present
                        pending.value = false
                    }
                }
            }
        }
        screen.waitForIdle()
        val view = screen.activity.window.decorView
        screen.waitUntil(5_000) { screen.runOnUiThread { view.hasWindowFocus() } }
        val clock = screen.mainClock
        clock.autoAdvance = false
        fun change(block: () -> Unit) {
            screen.runOnUiThread(block)
            clock.advanceTimeByFrame()
            screen.waitForIdle()
        }
        fun assertDecisions(vararg expected: Boolean) {
            assertEquals(expected.toList(), screen.runOnUiThread { decisions.toList() })
        }
        try {
            change { pending.value = true }
            clock.advanceTimeBy(400)
            assertDecisions()
            change { imeActive.value = false }
            assertDecisions(true)
            clock.advanceTimeBy(1100)
            assertDecisions(true)

            // Disposing the request cancels its timer. Even a later hidden-inset signal must do nothing.
            change {
                imeActive.value = true
                pending.value = true
            }
            clock.advanceTimeBy(160)
            change { pending.value = false }
            change { imeActive.value = false }
            clock.advanceTimeBy(1100)
            assertDecisions(true)

            // The initiating route becomes ineligible on the same frame Android reports dismissal.
            change {
                imeActive.value = true
                pending.value = true
            }
            change {
                eligible.value = false
                imeActive.value = false
            }
            assertDecisions(true, false)
            clock.advanceTimeByFrame() // Dispose the completed request before issuing another.

            change {
                eligible.value = true
                imeActive.value = true
                pending.value = true
            }
            clock.advanceTimeBy(900)
            assertDecisions(true, false)
            clock.advanceTimeBy(160)
            assertDecisions(true, false, true)
        } finally {
            clock.autoAdvance = true
        }
    }

    @Test
    fun independentlySelectToolsAndKeepChoicesAfterDismissal() {
        openTools(checkColors = true)
        enabledCount(2).assertIsDisplayed()
        choice("模型内置搜索").assertIsSelected()
        choice("禁用").performClick().assertIsSelected()
        choice("模型内置搜索").assertIsNotSelected()
        enabledCount(1).assertIsDisplayed()
        screen.onNode(hasText("网络搜索") and hasText("禁用")).performClick()

        screen.onNode(hasText("图片生成") and hasText("自定义图片生成")).performClick()
        screen.onNodeWithText("模型内置图片生成").assertDoesNotExist()
        choice("自定义图片生成").assertIsSelected()
        choice("禁用").performClick().assertIsSelected()
        choice("自定义图片生成").assertIsNotSelected()
        enabledCount(0).assertIsDisplayed()
        choice("自定义图片生成").performClick().assertIsSelected()
        enabledCount(1).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        screen.onNodeWithText("Tools").assertDoesNotExist()

        openTools()
        enabledCount(1).assertIsDisplayed()
        choice("禁用").assertIsSelected()
        screen.onNode(hasText("图片生成") and hasText("自定义图片生成")).performClick()
        choice("自定义图片生成").assertIsSelected()
    }
}

internal fun SemanticsNodeInteraction.assertGlyphGray(label: String, expectedGray: Int) {
    val image = captureToImage().asAndroidBitmap()
    var solidPixels = 0
    var brightest = 0
    for (y in image.height / 4 until 3 * image.height / 4) {
        for (x in image.width / 4 until 3 * image.width / 4) {
            val color = image.getPixel(x, y)
            val channels = listOf(Color.red(color), Color.green(color), Color.blue(color))
            if (channels.all { abs(it - expectedGray) <= 2 }) solidPixels++
            brightest = maxOf(brightest, channels.max())
        }
    }
    assertTrue("$label has $solidPixels solid stroke pixels, brightest channel $brightest; expected $expectedGray",
        solidPixels >= 2 && abs(brightest - expectedGray) <= 2)
}
