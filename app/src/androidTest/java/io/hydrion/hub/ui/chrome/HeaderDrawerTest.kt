package io.hydrion.hub.ui.chrome

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.MainActivity
import io.hydrion.hub.model.Mode
import io.hydrion.hub.ui.theme.HydrionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeaderDrawerTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun emptyAndExistingChatHaveAClickableTwoStrokeDrawerIcon() {
        val locked = mutableStateOf(false)
        var opens = 0
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                HydrionTheme {
                    Header(
                        mode = Mode.Chat, locked = locked.value, title = "示例会话", pinned = false,
                        contextLabel = "0.5k", contextFraction = 0.1f, onMode = {}, onDrawer = { opens++ },
                        onNew = {}, onRename = {}, onPin = {}, onFind = {}, onDelete = {}
                    )
                }
            }
        }
        for (hasMessages in listOf(false, true)) {
            screen.runOnIdle { locked.value = hasMessages }
            screen.onNodeWithText(if (hasMessages) "示例会话" else "Image", substring = true).assertIsDisplayed()
            val icon = screen.onNodeWithContentDescription("打开侧栏", useUnmergedTree = true)
            assertDrawerShape(icon.assertIsDisplayed().captureToImage().asAndroidBitmap())
            screen.onNode(hasContentDescription("打开侧栏") and hasClickAction())
                .performSemanticsAction(SemanticsActions.OnClick)
            screen.runOnIdle { assertEquals(if (hasMessages) 2 else 1, opens) }
        }
    }

    /** Inspect the rendered glyph in its own bounds, independent of screen coordinates or density. */
    private fun assertDrawerShape(bitmap: Bitmap) {
        fun brightness(color: Int) = Color.red(color) + Color.green(color) + Color.blue(color)
        val background = brightness(bitmap.getPixel(0, 0))
        val cutoff = (background + 3 * 255) / 2
        val strokes = mutableListOf<MutableList<Int>>()
        var previousRowHadInk = false
        for (y in 0 until bitmap.height) {
            val ink = (0 until bitmap.width).filter { x -> brightness(bitmap.getPixel(x, y)) > cutoff }
            if (ink.isNotEmpty()) {
                if (!previousRowHadInk) strokes.add(mutableListOf())
                assertEquals("Each stroke must be continuous", ink.last() - ink.first() + 1, ink.size)
                strokes.last().add(ink.size)
            }
            previousRowHadInk = ink.isNotEmpty()
        }
        assertEquals("Expected exactly two strokes in the sidebar icon", 2, strokes.size)
        val upper = strokes.first().max()
        val lower = strokes.last().max()
        assertTrue("The upper stroke must be visibly longer", upper > lower * 1.15f)
        assertTrue("Both strokes must be horizontal",
            upper > 3 * strokes.first().size && lower > 3 * strokes.last().size)
    }
}
