package io.hydrion.hub

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val model = ProviderModel("test", "A long model name")
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                    MaterialTheme {
                        Box(Modifier.width(width.value).testTag("test-header")) {
                            ChatModelControls(model, emptyMap(), ChatSpeedControls(), {}, {}, { _, _ -> }, {}, {})
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
                assertTrue(select.contains(arrow.center))
                val buttons = listOf(bounds("chat-effort-back"), bounds("chat-model-fast"), bounds("chat-model-reset"))
                if (widthDp >= 144) {
                    assertEquals(buttons.first().center.y, buttons[1].center.y, 1f)
                    assertEquals(buttons.first().center.y, buttons[2].center.y, 1f)
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
