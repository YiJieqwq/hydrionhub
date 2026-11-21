package io.hydrion.hub

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.hydrion.hub.provider.BrowseTile
import io.hydrion.hub.provider.deepSeekProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class ModelBrowseTileAppearanceTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun narrowRowWrapsItsCapabilitiesAndOnlyTintsTheSelectedModel() {
        val dark = mutableStateOf(true)
        val checked = mutableStateOf(false)
        val model = deepSeekProvider.models.first()
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.width(244.dp).background(if (dark.value) Color.Black else Color.White)) {
                        BrowseTile(model.name, deepSeekProvider, model, checked.value, Modifier.testTag("tile"),
                            onPick = { checked.value = true })
                    }
                }
            }
        }
        for (night in listOf(true, false)) {
            screen.runOnIdle { dark.value = night; checked.value = false }
            val tile = screen.onNodeWithTag("tile")
            fun sample(): Int {
                val image = tile.captureToImage().asAndroidBitmap()
                val density = tile.fetchSemanticsNode().layoutInfo.density.density
                return image.getPixel((3 * density).roundToInt(), image.height / 2)
            }
            val plain = if (night) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            assertEquals(plain, sample())
            tile.performClick().assertIsSelected()
            val actual = sample()
            val tint = if (night) intArrayOf(182, 196, 255) else intArrayOf(77, 92, 146)
            val alpha = if (night) .12f else .08f
            val background = if (night) 0 else 255
            val channels = intArrayOf(android.graphics.Color.red(actual), android.graphics.Color.green(actual),
                android.graphics.Color.blue(actual))
            for (i in channels.indices) assertEquals(background + (tint[i] - background) * alpha, channels[i].toFloat(), 1f)
            for (id in listOf(R.string.browse_chat, R.string.browse_tools, R.string.browse_reasoning)) {
                val label = screen.activity.getString(id)
                if (id == R.string.browse_chat) screen.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                else screen.onNodeWithContentDescription(label, useUnmergedTree = true).assertIsDisplayed()
            }
        }
    }
}
