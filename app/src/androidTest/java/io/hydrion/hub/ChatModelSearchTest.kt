package io.hydrion.hub

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hydrion.hub.model.Attachment
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
    private val send get() = screen.onNodeWithTag("chat-composer-send")
    private fun control(id: String) = screen.onNodeWithTag("chat-model-$id")
    private fun option(provider: Provider, id: String) = "option:${provider.id}/$id"
    private fun reveal(id: String): SemanticsNodeInteraction {
        control("results").performScrollToNode(hasTestTag("chat-model-$id"))
        return control(id).assertIsDisplayed()
    }

    private fun withProviders(includeAttachment: Boolean = false, test: (HubViewModel, Provider, Provider) -> Unit) {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val store = vm.providers
        val before = store.catalog.value
        val previous = before.active[Mode.Chat]
        val draft = vm.state.value.draft.text
        val prefix = "model-search-test-${UUID.randomUUID()}"
        val attachment = Attachment("$prefix-file", "draft-only.txt", mimeType = "text/plain")
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
                if (includeAttachment) vm.addAttachment(attachment)
            }
            test(vm, first, second)
        } finally {
            screen.runOnIdle {
                store.remove(first.id)
                store.remove(second.id)
                if (previous != null) store.configure(Mode.Chat, previous, before.overrides[previous].orEmpty())
                if (includeAttachment) vm.removeAttachment(attachment.id)
                vm.setDraft(draft)
            }
        }
    }

    private fun openControls() {
        control("trigger").performTouchInput { click(center) }
        control("card").assertIsDisplayed()
        control("search-field").assertDoesNotExist()
    }

    private fun enterSearch(button: String, expectedQuery: String = ""): SemanticsNodeInteraction {
        control(button).performTouchInput { click(center) }
        control("list").assertIsDisplayed()
        val field = screen.onNode(hasTestTag("chat-model-search-field") and
            hasAnyAncestor(hasTestTag("composer-capsule")))
            .assertIsDisplayed().assertIsFocused().assert(hasImeAction(ImeAction.Search))
        screen.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("chat-model-list")),
            useUnmergedTree = true).assertCountEquals(0)
        assertEquals(expectedQuery, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        control("search-close").assertIsDisplayed()
        control("search").assertDoesNotExist()
        send.assertIsDisplayed().assertIsNotEnabled()
        return field
    }

    private fun imeIsVisible() = screen.runOnUiThread {
        val view = screen.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(view)
        view.hasWindowFocus() && insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun searchUsesTheBrowsedProviderAndRestoresTheDraftSelection() = withProviders(includeAttachment = true) { vm, first, second ->
        val draft = "first line\nsecond line\nthird line"
        val selection = TextRange(6, 17)
        editor.performTouchInput { click(center) }
        screen.waitUntil(15_000) { imeIsVisible() }
        editor.performTextReplacement(draft)
        editor.performTextInputSelection(selection)
        val before = screen.runOnIdle { vm.state.value }
        fun assertDraftUntouched() {
            screen.runOnIdle {
                val now = vm.state.value
                assertEquals(before.current.id, now.current.id)
                assertEquals(before.current.messages, now.current.messages)
                assertEquals(before.draft, now.draft)
            }
        }
        fun assertEditing() {
            assertDraftUntouched()
            editor.assertIsFocused().assertTextEquals(draft)
            assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            assertTrue("The draft's keyboard should remain visible", imeIsVisible())
            screen.onNodeWithText("draft-only.txt").assertIsDisplayed()
        }
        val active = RemoteModel(first.id, "batch-alpha")
        fun assertActive(route: RemoteModel) {
            screen.runOnIdle { assertEquals(route, vm.providers.catalog.value.active[Mode.Chat]) }
        }

        openControls()
        assertEditing()
        val field = enterSearch("select")
        // The first search has no filter; its first and last provider models must both be reachable.
        reveal(option(first, "batch-alpha")).assertIsSelected()
        reveal(option(first, "last"))
        field.performTextReplacement("Nimbus model ".repeat(8))
        field.performTextInputSelection(TextRange.Zero)
        field.performTouchInput { click(center) }
        field.assertIsFocused()
        val caret = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertTrue("A tap inside the query must reach the text field and move its cursor",
            caret.collapsed && caret.start > 0)
        field.performTextReplacement("nIMBus")
        field.performImeAction()
        control("list").assertIsDisplayed()
        assertDraftUntouched()
        reveal(option(first, "batch-alpha")).assertIsSelected().assertTextContains("batch-alpha", substring = true)
        reveal(option(first, "batch-beta")).assertIsNotSelected().assertTextContains("batch-beta", substring = true)
        control(option(second, "batch-alpha")).assertDoesNotExist()
        field.performTextReplacement("tch-BEt")
        reveal(option(first, "batch-beta"))
        control(option(first, "batch-alpha")).assertDoesNotExist()
        field.performTextClearance()
        reveal(option(first, "last"))
        field.performTextReplacement("  /AtLaS-v3  ")
        reveal(option(first, "batch-alpha")).assertIsSelected()
        control(option(first, "batch-beta")).assertDoesNotExist()
        field.performTextReplacement("other-only")
        reveal("search-empty")
        val querySelection = TextRange(1, 5)
        field.performTextInputSelection(querySelection)
        control(option(second, "other-only")).assertDoesNotExist()
        assertActive(active)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        control("search-field").assertDoesNotExist()
        control("search").assertIsDisplayed()
        assertEditing()
        reveal(option(first, "last"))
        val resumed = enterSearch("search", "other-only")
        assertEquals(querySelection, resumed.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        reveal("search-empty")
        resumed.performTextReplacement("draft stays")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        resumed.assertIsFocused().assertTextEquals("draft stays")
        send.assertIsNotEnabled()
        assertDraftUntouched()
        val viewport = screen.onNodeWithTag("chat-picker-viewport")
        // Tap a blank corner outside both the model list and the composer's query hit area.
        val offset = 2f * screen.activity.resources.displayMetrics.density
        val corner = viewport.fetchSemanticsNode().boundsInRoot.topLeft + Offset(offset, offset)
        assertTrue(!control("list").fetchSemanticsNode().boundsInRoot.contains(corner))
        assertTrue(!screen.onNodeWithTag("composer-capsule").fetchSemanticsNode().boundsInRoot.contains(corner))
        viewport.performTouchInput { click(Offset(2.dp.toPx(), 2.dp.toPx())) }
        control("search-field").assertDoesNotExist()
        control("list").assertDoesNotExist()
        control("card").assertDoesNotExist()
        assertEditing()
        openControls()
        enterSearch("select").performTextReplacement("Nimbus")
        reveal("provider-trigger").performTouchInput { click(center) }
        val providerTag = "chat-model-provider:${second.id}"
        val providerList = hasTestTag("chat-provider-list")
        screen.onNode(hasScrollAction() and (providerList or hasAnyAncestor(providerList)))
            .performScrollToNode(hasTestTag(providerTag))
        screen.onNodeWithTag(providerTag).performTouchInput { click(center) }
        control("search-field").assertDoesNotExist()
        reveal(option(second, "other-only"))
        assertActive(active)

        enterSearch("search", "Nimbus").performTextReplacement("batch-alpha")
        control(option(first, "batch-alpha")).assertDoesNotExist()
        reveal(option(second, "batch-alpha")).assertIsNotSelected().performTouchInput { click(center) }
        control("card").assertIsDisplayed()
        control("search-field").assertDoesNotExist()
        assertActive(RemoteModel(second.id, "batch-alpha"))
        assertEditing()
        enterSearch("select")
        reveal(option(second, "other-only"))
        control("search-close").performTouchInput { click(center) }
        assertEditing()
    }

    @Test fun floatingSearchKeepsItsTouchAreaAndTheLastModelCanBePicked() = withProviders { vm, first, _ ->
        screen.runOnIdle { vm.setDraft("model list") }
        editor.performTouchInput { click(center) }
        screen.waitUntil(15_000) { imeIsVisible() }
        openControls()
        val field = enterSearch("select")
        val card = control("list").fetchSemanticsNode().boundsInRoot
        val close = control("search-close")
        val closeBounds = close.fetchSemanticsNode().boundsInRoot
        val minimum = 48f * screen.activity.resources.displayMetrics.density - 1f
        val rtl = screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        assertTrue("The floating X needs a 48dp target: $closeBounds", closeBounds.width >= minimum && closeBounds.height >= minimum)
        assertTrue("The X must be inside the lower part of the card",
            card.contains(closeBounds.center) && closeBounds.center.y > card.center.y)
        assertTrue("The X must be on the trailing side",
            if (rtl) closeBounds.center.x < card.center.x else closeBounds.center.x > card.center.x)
        field.performTextReplacement("Zeta")
        close.performTouchInput { click(center) }
        control("search-field").assertDoesNotExist()
        control("list").assertIsDisplayed()
        val search = control("search").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Search and close use the same left edge", search.left, closeBounds.left, 1f)
        assertEquals("Search and close use the same top edge", search.top, closeBounds.top, 1f)
        assertEquals("Search and close use the same right edge", search.right, closeBounds.right, 1f)
        assertEquals("Search and close use the same bottom edge", search.bottom, closeBounds.bottom, 1f)
        editor.assertIsFocused().assertTextEquals("model list")
        send.assertIsEnabled()
        reveal(option(first, "batch-beta"))

        val beforeTap = screen.runOnIdle { vm.state.value }
        enterSearch("search", "Zeta").performTextReplacement("must not send")
        send.assertIsNotEnabled().performTouchInput { click(center) }
        control("search-field").assertDoesNotExist()
        control("list").assertDoesNotExist()
        control("card").assertDoesNotExist()
        editor.assertIsFocused().assertTextEquals("model list")
        screen.runOnIdle {
            assertEquals(beforeTap.current, vm.state.value.current)
            assertEquals(beforeTap.draft, vm.state.value.draft)
        }
        openControls()
        enterSearch("select")
        control("search-close").performTouchInput { click(center) }
        val results = control("results")
        results.performScrollToNode(hasTestTag("chat-model-option:${first.id}/last"))
        results.performTouchInput { swipeUp() }
        val last = control(option(first, "last")).assertIsDisplayed()
        val row = last.fetchSemanticsNode().boundsInRoot
        assertTrue("Last option must be fully available beside the search target: $row",
            row.height >= minimum && !row.overlaps(control("search").fetchSemanticsNode().boundsInRoot))
        last.performTouchInput { click(Offset(if (rtl) 4.dp.toPx() else width - 4.dp.toPx(), centerY)) }
        control("card").assertIsDisplayed()
        screen.runOnIdle { assertEquals(RemoteModel(first.id, "last"), vm.providers.catalog.value.active[Mode.Chat]) }
    }
}
