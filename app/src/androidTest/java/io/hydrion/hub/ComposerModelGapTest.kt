package io.hydrion.hub

import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.provider.Capability
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.deepSeekProvider
import io.hydrion.hub.ui.HydrionApp
import java.util.UUID
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerModelGapTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun node(tag: String) = screen.onNodeWithTag(tag, useUnmergedTree = true)

    private fun checkInk(direction: LayoutDirection, model: ProviderModel, enabled: Boolean): IntRange {
        val capsule = node("composer-capsule")
        val viewport = capsule.fetchSemanticsNode()
        val density = viewport.layoutInfo.density.density
        val bitmap = capsule.captureToImage().asAndroidBitmap()
        fun box(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInRoot
            .translate(-viewport.boundsInRoot.left, -viewport.boundsInRoot.top)

        // The control bounds locate each crop. Only pixels belonging to its ink count as the visible edge.
        fun ink(node: SemanticsNodeInteraction, accepts: (Int) -> Boolean): IntRange {
            val region = box(node)
            val cols = (region.left.roundToInt() until region.right.roundToInt()).filter { x ->
                (region.top.roundToInt() until region.bottom.roundToInt()).any { y -> accepts(bitmap.getPixel(x, y)) }
            }
            assertTrue("No ink at $region", cols.isNotEmpty())
            return cols.first()..cols.last()
        }
        val bright: (Int) -> Boolean = { maxOf(Color.red(it), Color.green(it), Color.blue(it)) >= 145 }
        val plus = ink(node("chat-composer-attach"), bright)
        val tools = ink(screen.onNodeWithContentDescription("打开工具箱"), bright)
        val terminal = ink(screen.onNodeWithContentDescription("工作区终端"), bright)
        val labelTag = hasTestTag("chat-model-source-label")
        val name = screen.onNode(hasText(model.name) and hasAnyAncestor(labelTag), useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        name.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Fixture must exercise actual text truncation", model.id == "long", layouts.single().isLineEllipsized(0))
        val label = ink(node("chat-model-source-label"), bright)

        val face = node("chat-composer-action-face")
        val disk = box(face)
        val paint = bitmap.getPixel(disk.center.x.roundToInt(), (disk.top + disk.height * .16f).roundToInt())
        assertTrue("Disk differs from composer at its corner", paint != bitmap.getPixel(disk.left.roundToInt(), disk.top.roundToInt()))
        assertEquals("Blue enabled face / neutral disabled face", enabled, Color.blue(paint) - Color.red(paint) > 100)
        val paintedDisk = ink(face) { it == paint }
        assertEquals("Solid fill must span the send circle", 32f * density, paintedDisk.count().toFloat(), 2f)

        fun gap(a: IntRange, b: IntRange) =
            if (direction == LayoutDirection.Ltr) b.first - a.last - 1 else a.first - b.last - 1
        val measured = gap(label, paintedDisk).toFloat()
        // Final glyph side bearings, filtered antialias pixels and integer rounding can add a few pixels.
        val tolerance = 2f * density + 1f
        assertEquals("Visible model-to-send gap", 22f * density, measured, tolerance)
        assertEquals("Compared to attachment/tool ink", gap(plus, tools).toFloat(), measured, tolerance)
        assertEquals("Compared to tool/terminal ink", gap(tools, terminal).toFloat(), measured, tolerance)
        return paintedDisk
    }

    @Test fun modelInkKeepsTheToolbarRhythmInBothDirections() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val before = vm.providers.catalog.value
        val originalDraft = vm.state.value.draft.text
        val models = listOf(ProviderModel("short", "Max"), ProviderModel("long", "Atlas Model ".repeat(20) + "Max",
            listOf(Capability("effort", "Effort", listOf("Low", "High")))))
        val provider = deepSeekProvider.copy(id = "gap-test-${UUID.randomUUID()}", models = models)
        val dir = mutableStateOf(LayoutDirection.Ltr)
        try {
            screen.runOnUiThread { screen.activity.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides dir.value) { HydrionApp() }
            } }
            screen.runOnIdle { vm.providers.save(provider, "", null) }
            for (direction in LayoutDirection.values()) for (model in models) {
                screen.runOnIdle {
                    dir.value = direction
                    val parameters = if (model.id == "long") mapOf("effort" to "High") else emptyMap()
                    vm.providers.configure(Mode.Chat, RemoteModel(provider.id, model.id), parameters)
                    vm.setDraft("1")
                }
                val enabledDisk = checkInk(direction, model, enabled = true)
                node("chat-model-trigger").performClick()
                node("chat-model-card").assertIsDisplayed()
                screen.runOnIdle { vm.setDraft(" ") }
                assertEquals("Picker and color changes keep the send circle in the same place",
                    enabledDisk, checkInk(direction, model, enabled = false))
                node("chat-model-trigger").performClick()
                node("chat-model-card").assertDoesNotExist()
            }
        } finally {
            screen.runOnIdle {
                vm.providers.remove(provider.id)
                before.active[Mode.Chat]?.let { vm.providers.configure(Mode.Chat, it, before.overrides[it].orEmpty()) }
                vm.setDraft(originalDraft)
            }
        }
    }
}
