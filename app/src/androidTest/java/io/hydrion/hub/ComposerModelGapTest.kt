package io.hydrion.hub

import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
        val labelTag = hasTestTag("chat-model-source-label")
        val name = screen.onNode(hasText(model.name) and hasAnyAncestor(labelTag), useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        name.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Fixture must exercise actual text truncation", model.id == "long", layouts.single().isLineEllipsized(0))
        val white: (Int) -> Boolean = { minOf(Color.red(it), Color.green(it), Color.blue(it)) >= 245 }
        ink(name, white)
        val label = box(node("chat-model-source-label"))
        val mic = box(node("chat-composer-dictate").assertIsEnabled().assertHasClickAction())
        val send = box(node("chat-composer-send"))
        val tolerance = 1.5f
        val rtl = direction == LayoutDirection.Rtl
        assertTrue("Model label stays outside the dictation target", if (rtl) label.left >= mic.right - tolerance
            else label.right <= mic.left + tolerance)
        assertTrue("Dictation and send have separate targets", if (rtl) send.right <= mic.left + tolerance
            else mic.right <= send.left + tolerance)
        assertTrue("Dictation retains its 48dp target", mic.width >= 48f * density - tolerance &&
            mic.height >= 48f * density - tolerance)

        val face = node("chat-composer-action-face")
        val disk = box(face)
        val paint = bitmap.getPixel(disk.center.x.roundToInt(), (disk.top + disk.height * .16f).roundToInt())
        assertTrue("Disk differs from composer at its corner", paint != bitmap.getPixel(disk.left.roundToInt(), disk.top.roundToInt()))
        assertEquals("Blue enabled face / neutral disabled face", enabled, Color.blue(paint) - Color.red(paint) > 100)
        val paintedDisk = ink(face) { it == paint }
        assertEquals("Solid fill must span the send circle", 32f * density, paintedDisk.count().toFloat(), 2f)

        val trailingClearance = if (rtl) disk.left else bitmap.width - disk.right
        assertEquals("Send face stays at its original trailing inset", 9.75f * density, trailingClearance, tolerance)
        val microphoneToSend = (disk.center.x - mic.center.x) * (if (rtl) -1f else 1f)
        assertEquals("The mic sits immediately before the unchanged send face", 48.25f * density, microphoneToSend, 2f)
        assertEquals("Microphone and send share the toolbar center line", disk.center.y, mic.center.y, tolerance)
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
                CompositionLocalProvider(LocalLayoutDirection provides dir.value) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.widthIn(max = 320.dp)) { HydrionApp() }
                    }
                }
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
