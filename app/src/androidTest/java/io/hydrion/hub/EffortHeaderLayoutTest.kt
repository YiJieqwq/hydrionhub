package io.hydrion.hub

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.abs
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.provider.Capability
import io.hydrion.hub.provider.ChatModelControls
import io.hydrion.hub.provider.ChatSpeedControls
import io.hydrion.hub.provider.ProviderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EffortHeaderLayoutTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun bothLabelsStayOnTheCardCenterlineWithIndependentButtons() {
        val width = mutableStateOf(288.dp)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val model = ProviderModel("test", "DeepSeek V4.1 Flash", listOf(
            Capability("effort", "Effort", listOf("Low", "Max")), Capability("speed", "Speed", listOf("Standard", "Fast"))))
        var selected = 0
        var fastTapped = 0
        val speed = mutableStateOf(ChatSpeedControls())
        val modelName = mutableStateOf(model.name)
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Box(Modifier.width(width.value).background(Color.Black).testTag("test-header")) {
                            ChatModelControls(model.copy(name = modelName.value), emptyMap(), speed.value, null, {}, { selected++ },
                                { _, _ -> fastTapped++ }, {}, {})
                        }
                    }
                }
            }
        }
        fun bounds(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val minimum = 48f * screen.activity.resources.displayMetrics.density - 1f
        for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for (widthDp in listOf(288, 216, 128, 88)) {
                screen.runOnIdle {
                    width.value = widthDp.dp
                    direction.value = layout
                }
                val card = bounds("test-header")
                val title = bounds("chat-model-effort-title")
                val name = bounds("chat-model-name")
                val arrow = bounds("chat-model-name-chevron")
                assertEquals(card.center.x, title.center.x, 1f)
                assertEquals(card.center.x, name.center.x, 1f)
                assertTrue(if (layout == LayoutDirection.Ltr) arrow.left >= name.right else arrow.right <= name.left)
                val select = bounds("chat-model-select")
                val fast = bounds("chat-model-fast")
                val glyph = bounds("chat-model-fast-glyph")
                val dp = screen.activity.resources.displayMetrics.density
                val towardStart = if (layout == LayoutDirection.Ltr) -1 else 1
                screen.onNodeWithTag("chat-effort-back", useUnmergedTree = true).assertDoesNotExist()
                val startGap = if (layout == LayoutDirection.Ltr) fast.left - card.left else card.right - fast.right
                assertEquals("Fast occupies the top leading slot", 0f, startGap, 1f)
                assertEquals(card.top, fast.top, 1f)
                assertEquals(fast.center.x + towardStart * 3f * dp, glyph.center.x, 1.5f)
                assertEquals(fast.center.y, glyph.center.y, 1f)
                assertTrue(select.contains(arrow.center) || arrow.top >= fast.bottom)
                if (widthDp == 288) {
                    val results = mutableListOf<TextLayoutResult>()
                    val node = screen.onNodeWithTag("chat-model-name", useUnmergedTree = true).fetchSemanticsNode()
                    assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
                    assertTrue("A typical name fits on one line", !results.single().isLineEllipsized(0))
                    val bitmap = screen.onNodeWithTag("test-header").captureToImage().asAndroidBitmap()
                    fun inkRows(box: Rect): List<Int> {
                        val local = box.translate(-card.topLeft)
                        val xs = ceil(local.left).toInt().coerceAtLeast(0) until floor(local.right).toInt().coerceAtMost(bitmap.width)
                        val ys = ceil(local.top).toInt().coerceAtLeast(0) until floor(local.bottom).toInt().coerceAtMost(bitmap.height)
                        return ys.filter { y -> xs.any { x -> (bitmap.getPixel(x, y) and 0xff) > 48 } }
                    }
                    val nameInk = inkRows(name)
                    val titleInk = inkRows(title)
                    val rail = bounds("chat-model-effort")
                    val railX = rail.left + rail.width * if (layout == LayoutDirection.Ltr) .8f else .2f
                    val railTop = inkRows(Rect(railX, rail.top, railX + 2f, rail.center.y)).first()
                    assertTrue("Visible title/name/rail gaps agree to raster edges",
                        abs((nameInk.first() - titleInk.last()) - (railTop - nameInk.last())) <= 3)

                }
                val reset = bounds("chat-model-reset")
                val buttons = listOf(fast, reset)
                if (widthDp >= 96) assertEquals(fast.center.y, reset.center.y, 1f)
                else assertTrue("In a narrow card the two targets wrap without overlap", fast.bottom <= reset.top)
                if (widthDp == 288) {
                    fun tap(point: Offset) {
                        screen.onNodeWithTag("test-header").performTouchInput { click(point - card.topLeft) }
                        screen.waitForIdle()
                    }
                    val previousCalls = selected
                    screen.runOnIdle { modelName.value = "Hydrion" }
                    val shortHit = bounds("chat-model-select")
                    val nameY = bounds("chat-model-name").center.y
                    tap(Offset(shortHit.left - 3 * dp, nameY))
                    tap(Offset(shortHit.right + 3 * dp, nameY))
                    assertEquals("Neither side of the short name is a model action", 0, selected - previousCalls)
                    screen.runOnIdle { modelName.value = model.name }
                    val oldSelection = selected
                    val oldFast = fastTapped
                    tap(arrow.center)
                    tap(fast.center)
                    assertEquals(oldSelection + 1, selected)
                    assertEquals(oldFast, fastTapped)
                    screen.runOnIdle { speed.value = ChatSpeedControls("Fast", "Standard", null) }
                    tap(fast.center)
                    assertEquals(oldSelection + 1, selected)
                    assertEquals(oldFast + 1, fastTapped)
                    val longName = "A provider-specific model identifier with very long display name and descenders"
                    screen.runOnIdle { modelName.value = longName }
                    screen.onNodeWithTag("chat-model-name", useUnmergedTree = true).assertTextEquals(longName)
                    val longChevron = bounds("chat-model-name-chevron")
                    assertTrue(!select.contains(longChevron.center) && longChevron.top >= fast.bottom)
                    tap(longChevron.center)
                    assertEquals(oldSelection + 2, selected)
                    screen.runOnIdle { speed.value = ChatSpeedControls(); modelName.value = model.name }
                }
                val targets = buttons + select
                targets.forEachIndexed { i, box ->
                    assertTrue("48dp target at ${widthDp}dp: $box", box.width >= minimum && box.height >= minimum)
                    assertTrue(targets.drop(i + 1).none(box::overlaps))
                }
            }
        }
    }
}
