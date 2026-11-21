package io.hydrion.hub

import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.provider.ChatTriggerLabel
import io.hydrion.hub.provider.ChatTriggerText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.provider.Capability
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.ModelType
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.deepSeekProvider
import io.hydrion.hub.ui.HydrionApp
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerEffortGeometryTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun tag(name: String) = screen.onNodeWithTag(name, useUnmergedTree = true)

    private fun withPicker(model: ProviderModel, block: (HubViewModel) -> Unit) {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val prior = vm.providers.catalog.value
        val draft = vm.state.value.draft.text
        val provider = deepSeekProvider.copy(id = "effort-geometry-${UUID.randomUUID()}", models = listOf(model))
        try {
            screen.runOnIdle {
                vm.providers.save(provider, "", null)
                vm.providers.configure(Mode.Chat, RemoteModel(provider.id, model.id), emptyMap())
                vm.setDraft("1")
            }
            tag("chat-model-trigger").performClick()
            tag("chat-model-card").assertIsDisplayed()
            block(vm)
            tag("chat-model-trigger").performClick()
        } finally {
            screen.runOnIdle {
                vm.providers.remove(provider.id)
                prior.active[Mode.Chat]?.let { vm.providers.configure(Mode.Chat, it, prior.overrides[it].orEmpty()) }
                vm.setDraft(draft)
            }
        }
    }

    @Test fun changingEffortKeepsComposerTrailingEdgeAndPickerRowInPlace() {
        // Exercise the descender in High even when the emulator runs in Chinese.
        screen.activityRule.scenario.onActivity { activity ->
            val config = Configuration(activity.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            val context = ContextThemeWrapper(activity, 0).apply { applyOverrideConfiguration(config) }
            activity.setContent {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides config,
                    LocalActivityResultRegistryOwner provides activity,
                ) { HydrionApp() }
            }
        }
        for (choices in listOf(listOf("Low", "Medium", "High"), listOf("A", "Wide stage", "iii"))) {
            checkAdjustable(choices)
        }
    }

    @Test fun modelAndEffortEndTogetherAtTheToolbarAnchor() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val width = mutableStateOf(160.dp)
        val model = mutableStateOf("Hydrion")
        val selected = mutableStateOf<String?>("Medium")
        val choices = listOf("Medium", "High")
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                    Box(Modifier.width(width.value).testTag("label-space"), contentAlignment = Alignment.CenterEnd) {
                        ChatTriggerText(ChatTriggerLabel(model.value, selected.value, effortLabels = choices),
                            dark = true, style = TextStyle(fontSize = 14.sp), gapPx = 6, modifier = Modifier)
                    }
                }
            }
        }
        for (dir in LayoutDirection.values()) for (available in listOf(160.dp, 72.dp)) {
            for (modelName in listOf("Hydrion", "A long model name that needs truncation")) {
                screen.runOnIdle { direction.value = dir; width.value = available; model.value = modelName }
                val area = tag("label-space").fetchSemanticsNode().boundsInRoot
                val name = screen.onNodeWithText(modelName, useUnmergedTree = true)
                for (effort in choices) {
                    screen.runOnIdle { selected.value = effort }
                    val nameBox = name.fetchSemanticsNode().boundsInRoot
                    val suffix = screen.onNodeWithText(effort, useUnmergedTree = true)
                    val slot = suffix.fetchSemanticsNode().boundsInRoot
                    val lines = mutableListOf<TextLayoutResult>()
                    suffix.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(lines) }
                    val left = slot.left + lines.single().getLineLeft(0)
                    val right = slot.left + lines.single().getLineRight(0)
                    val rtl = dir == LayoutDirection.Rtl
                    assertEquals("Suffix ends at the available edge", if (rtl) area.left else area.right,
                        if (rtl) left else right, 1f)
                    assertEquals("One space between visible labels", 6f,
                        if (rtl) nameBox.left - right else left - nameBox.right, 1f)
                    if (modelName != "Hydrion") {
                        val layouts = mutableListOf<TextLayoutResult>()
                        name.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        assertTrue(layouts.single().isLineEllipsized(0))
                    }
                }
                screen.runOnIdle { selected.value = null }
                val onlyName = name.fetchSemanticsNode().boundsInRoot
                if (dir == LayoutDirection.Ltr) assertEquals(area.right, onlyName.right, 1f)
                else assertEquals(area.left, onlyName.left, 1f)
            }
        }
    }

    private fun checkAdjustable(choices: List<String>) {
        val model = ProviderModel("geometry", "Atlas", listOf(Capability("effort", "Effort", choices)))
        withPicker(model) { vm ->
            fun boxes(): List<Rect> = listOf(tag("chat-model-trigger"), tag("chat-composer-dictate"), tag("chat-composer-send"),
                tag("chat-model-name"), tag("chat-model-name-chevron"), tag("chat-model-effort")).map { it.fetchSemanticsNode().boundsInRoot }
            val expected = boxes()
            val card = tag("chat-model-card").fetchSemanticsNode().boundsInRoot
            assertEquals(card.center.x, expected[3].center.x, 1f)
            val rtl = screen.activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
            val arrowGap = if (rtl) expected[3].left - expected[4].right else expected[4].left - expected[3].right
            val density = tag("chat-model-name").fetchSemanticsNode().layoutInfo.density.density
            assertEquals("Chevron stays next to the model name", 3f * density, arrowGap, 1f)
            for (index in listOf(1, 2, 0)) {
                tag("chat-model-effort").performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
                tag("chat-model-effort-title").assertTextEquals(choices[index])
                screen.runOnIdle { assertEquals(choices[index], vm.providers.selection(Mode.Chat).parameters["effort"]) }
                val suffix = screen.onNode(hasText(choices[index]) and hasAnyAncestor(hasTestTag("chat-model-source-label")),
                    useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val mic = expected[1]
                val clearance = if (rtl) suffix.left - mic.right else mic.left - suffix.right
                assertEquals("Effort ends at the existing toolbar inset", 16f * density, clearance, 1f)
                assertEquals("Trigger, adjacent actions, popup name, chevron and rail keep their bounds", expected, boxes())
            }
        }
    }

    @Test fun fixedAndAbsentEffortsHaveAnInertNeutralRailAndAnAvailableModelSelector() {
        val single = Capability("effort", "Effort", listOf("High"))
        val typed = ModelProfile(type = ModelType.Chat, abilities = setOf("reasoning"), levels = listOf("High"),
            defaultLevel = "High", allowDisabling = false)
        for (model in listOf(ProviderModel("plain", "Plain"), ProviderModel("one", "One", listOf(single)),
            ProviderModel("typed", "Typed", profile = typed))) {
            withPicker(model) { vm ->
                val title = tag("chat-model-effort-title")
                val default = screen.activity.getString(R.string.chat_controls_default)
                title.assertTextEquals(default)
                val reset = tag("chat-model-reset").assertIsNotEnabled()
                assertFalse(SemanticsActions.OnClick in reset.fetchSemanticsNode().config)
                val rail = tag("chat-model-effort").assertIsDisplayed()
                val semantics = rail.fetchSemanticsNode().config
                assertFalse(SemanticsActions.OnClick in semantics)
                assertFalse(SemanticsActions.SetProgress in semantics)
                assertFalse(SemanticsProperties.ProgressBarRangeInfo in semantics)
                val before = vm.providers.catalog.value.overrides
                rail.performTouchInput {
                    click(Offset(width * .22f, centerY))
                    swipe(Offset(width * .85f, centerY), Offset(width * .15f, centerY), durationMillis = 180)
                }
                screen.runOnIdle { assertEquals(before, vm.providers.catalog.value.overrides) }
                title.assertTextEquals(default)
                val image = rail.captureToImage().asAndroidBitmap()
                fun pixel(fraction: Float) = image.getPixel((fraction * image.width).toInt(), image.height / 2)
                val body = pixel(.25f)
                assertEquals(body, pixel(.75f))
                val rgb = listOf(Color.red(body), Color.green(body), Color.blue(body))
                assertTrue("The track is neutral", rgb.max() - rgb.min() <= 8)
                assertTrue("The thumb stays bright and centered after both gestures", Color.red(pixel(.5f)) > Color.red(body))
                tag("chat-model-select").assertHasClickAction()
            }
        }
    }
}
