package io.hydrion.hub

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.deepSeekProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatModelSearchTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val editor get() = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())
    private fun control(id: String) = screen.onNodeWithTag("chat-model-$id")
    private fun option(provider: Provider, id: String) = "option:${provider.id}/$id"
    private fun reveal(id: String): SemanticsNodeInteraction {
        control("results").performScrollToNode(hasTestTag("chat-model-$id"))
        return control(id).assertIsDisplayed()
    }

    private fun withProviders(test: (HubViewModel, Provider, Provider) -> Unit) {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val store = vm.providers
        val before = store.catalog.value
        val previous = before.active[Mode.Chat]
        val draft = vm.state.value.draft.text
        val prefix = "model-search-test-${UUID.randomUUID()}"
        val first = deepSeekProvider.copy(id = "$prefix-a", name = "Search fixture A", models = listOf(
            ProviderModel("batch-alpha", "Nimbus", profile = ModelProfile(externalId = "vendor/atlas-v3")),
            ProviderModel("batch-beta", "Nimbus"),
        ) + List(20) { ProviderModel("extra-$it", "Other model $it") } + ProviderModel("last", "Zeta"))
        val second = first.copy(id = "$prefix-b", name = "Search fixture B", models = listOf(
            ProviderModel("batch-alpha", "Nimbus"), ProviderModel("other-only", "Elsewhere"),
        ))
        try {
            screen.runOnIdle {
                store.save(first, "", null)
                store.save(second, "", null)
                store.configure(Mode.Chat, RemoteModel(first.id, "batch-alpha"), emptyMap())
            }
            test(vm, first, second)
        } finally {
            screen.runOnIdle {
                store.remove(first.id)
                store.remove(second.id)
                if (previous != null) store.configure(Mode.Chat, previous, before.overrides[previous].orEmpty())
                vm.setDraft(draft)
            }
        }
    }

    private fun openModels() {
        control("trigger").performTouchInput { click(center) }
        control("select").performTouchInput { click(center) }
        control("list").assertIsDisplayed()
    }

    private fun imeIsVisible() = screen.runOnUiThread {
        val view = screen.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(view)
        view.hasWindowFocus() && insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun searchUsesTheBrowsedProviderAndRestoresTheDraftSelection() = withProviders { vm, first, second ->
        val draft = "first line\nsecond line\nthird line"
        val selection = TextRange(6, 17)
        editor.performTouchInput { click(center) }
        screen.waitUntil(15_000) { imeIsVisible() }
        editor.performTextReplacement(draft)
        editor.performTextInputSelection(selection)
        fun assertEditing() {
            editor.assertIsFocused().assertTextEquals(draft)
            assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            assertTrue("The draft's keyboard should remain visible", imeIsVisible())
        }
        fun openSearch() {
            control("search").performTouchInput { click(center) }
            val field = control("search-field").assertIsDisplayed().assertIsFocused()
            assertEquals("", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
        val active = RemoteModel(first.id, "batch-alpha")
        fun assertActive(route: RemoteModel) {
            screen.runOnIdle { assertEquals(route, vm.providers.catalog.value.active[Mode.Chat]) }
        }

        openModels()
        control("search-field").assertDoesNotExist()
        assertEditing()
        openSearch()
        val field = control("search-field")
        field.performTextReplacement("nIMBus")
        reveal(option(first, "batch-alpha")).assertIsSelected().assertTextContains("batch-alpha", substring = true)
        reveal(option(first, "batch-beta")).assertIsNotSelected().assertTextContains("batch-beta", substring = true)
        control(option(second, "batch-alpha")).assertDoesNotExist()
        field.performTextReplacement("tch-BEt")
        reveal(option(first, "batch-beta"))
        control(option(first, "batch-alpha")).assertDoesNotExist()
        field.performTextReplacement("  /AtLaS-v3  ")
        reveal(option(first, "batch-alpha")).assertIsSelected()
        control(option(first, "batch-beta")).assertDoesNotExist()
        field.performTextReplacement("other-only")
        reveal("search-empty")
        control(option(second, "other-only")).assertDoesNotExist()
        assertActive(active)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        control("search-field").assertDoesNotExist()
        control("search").assertIsDisplayed()
        assertEditing()

        openSearch()
        field.performTextReplacement("Nimbus")
        reveal("provider-trigger").performTouchInput { click(center) }
        val providerTag = "chat-model-provider:${second.id}"
        val providerList = hasTestTag("chat-provider-list")
        screen.onNode(hasScrollAction() and (providerList or hasAnyAncestor(providerList)))
            .performScrollToNode(hasTestTag(providerTag))
        screen.onNodeWithTag(providerTag).performTouchInput { click(center) }
        control("search-field").assertDoesNotExist()
        reveal(option(second, "other-only"))
        assertActive(active)

        openSearch()
        field.performTextReplacement("batch-alpha")
        control(option(first, "batch-alpha")).assertDoesNotExist()
        reveal(option(second, "batch-alpha")).assertIsNotSelected().performTouchInput { click(center) }
        control("card").assertIsDisplayed()
        control("search-field").assertDoesNotExist()
        assertActive(RemoteModel(second.id, "batch-alpha"))
        assertEditing()
    }

    @Test fun floatingSearchKeepsItsTouchAreaAndTheLastModelCanBePicked() = withProviders { vm, first, _ ->
        screen.runOnIdle { vm.setDraft("model list") }
        openModels()
        val card = control("list").fetchSemanticsNode().boundsInRoot
        val search = control("search").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val minimum = 48f * screen.activity.resources.displayMetrics.density - 1f
        val rtl = screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        assertTrue("Floating search needs a 48dp target: $search", search.width >= minimum && search.height >= minimum)
        assertTrue("Search must be inside the lower part of the card",
            card.contains(search.center) && search.center.y > card.center.y)
        assertTrue("Search must be on the trailing side",
            if (rtl) search.center.x < card.center.x else search.center.x > card.center.x)

        val results = control("results")
        results.performScrollToNode(hasTestTag("chat-model-option:${first.id}/last"))
        results.performTouchInput { swipeUp() }
        val last = control(option(first, "last")).assertIsDisplayed()
        val row = last.fetchSemanticsNode().boundsInRoot
        assertTrue("Last option must be fully available beside the search target: $row",
            row.height >= minimum && !row.overlaps(search))
        last.performTouchInput { click(Offset(if (rtl) 4.dp.toPx() else width - 4.dp.toPx(), centerY)) }
        control("card").assertIsDisplayed()
        screen.runOnIdle { assertEquals(RemoteModel(first.id, "last"), vm.providers.catalog.value.active[Mode.Chat]) }
    }
}
