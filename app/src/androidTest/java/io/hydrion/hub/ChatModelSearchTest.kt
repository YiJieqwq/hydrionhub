package io.hydrion.hub

import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.ui.ModelEditorMotionMillis
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
    private val searchHint get() = screen.activity.getString(R.string.chat_picker_search_hint)

    private fun assertComposerHint(expected: String?) {
        val inCapsule = hasAnyAncestor(hasTestTag("composer-capsule"))
        for (hint in listOf("Chat with AI", searchHint)) {
            screen.onAllNodes(inCapsule and hasText(hint)).assertCountEquals(if (hint == expected) 1 else 0)
        }
    }

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

    private fun tapBackArrow(tag: String) {
        val button = screen.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
        val node = button.fetchSemanticsNode()
        val minimum = 48f * screen.activity.resources.displayMetrics.density - 1f
        assertTrue("Back needs a 48dp target", node.boundsInRoot.width >= minimum && node.boundsInRoot.height >= minimum)
        assertTrue(node.config[SemanticsProperties.ContentDescription].any { it.isNotBlank() })
        button.performTouchInput { click(center) }
    }

    private fun enterModels(): SemanticsNodeInteraction {
        val keyboardWasOpen = imeIsVisible()
        control("select").performTouchInput { click(center) }
        control("list").assertIsDisplayed()
        val inCapsule = hasAnyAncestor(hasTestTag("composer-capsule"))
        val field = screen.onNode(hasTestTag("chat-model-search-field") and inCapsule)
            .assertIsDisplayed().assert(hasImeAction(ImeAction.Search))
        if (keyboardWasOpen) field.assertIsFocused()
        assertEquals("Entering the model page preserves the keyboard", keyboardWasOpen, imeIsVisible())
        assertEquals("", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        screen.onNode(hasTestTag("chat-model-list-back") and inCapsule).assertIsDisplayed().assertHasClickAction()
        screen.onNode(hasTestTag("chat-model-search-clear") and inCapsule).assertIsDisplayed().assertIsNotEnabled()
        val inResultsPage = hasAnyAncestor(hasTestTag("chat-model-list"))
        screen.onAllNodes(inResultsPage and (hasSetTextAction() or hasTestTag("chat-model-list-back")),
            useUnmergedTree = true).assertCountEquals(0)
        send.assertDoesNotExist()
        screen.onNodeWithTag("chat-composer-dictate").assertDoesNotExist()
        return field
    }

    private fun imeIsVisible() = screen.runOnUiThread {
        val view = screen.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(view)
        view.hasWindowFocus() && insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun hideKeyboard() {
        screen.runOnUiThread {
            val window = screen.activity.window
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        screen.waitUntil(15_000) { !imeIsVisible() }
    }

    private fun capsule() = screen.onNodeWithTag("composer-capsule", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun assertAnchored(tag: String) {
        val density = screen.activity.resources.displayMetrics.density
        val card = control(tag).assertIsDisplayed().fetchSemanticsNode()
        val composer = screen.onNodeWithTag("composer-capsule", useUnmergedTree = true).fetchSemanticsNode()
        screen.runOnIdle {
            assertEquals("Panel gap above composer", 11.5f * density,
                composer.boundsInRoot.top - card.boundsInRoot.bottom, 2f)
        }
    }

    private fun assertImeStays(visible: Boolean, action: () -> Unit) {
        val view = screen.activity.window.decorView
        val samples = mutableListOf<Boolean>()
        val listener = ViewTreeObserver.OnPreDrawListener {
            samples += ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            true
        }
        screen.runOnUiThread { view.viewTreeObserver.addOnPreDrawListener(listener) }
        try {
            action()
            screen.waitForIdle()
            assertEquals(visible, imeIsVisible())
            screen.runOnUiThread {
                assertTrue("IME visibility per drawn frame: $samples", samples.isNotEmpty() && samples.all { it == visible })
            }
        } finally {
            screen.runOnUiThread { view.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
    }

    @Test fun emptyModelQueryKeepsTheKeyboardClosedUntilTappedAndClearDoesNotExit() = withProviders { vm, _, _ ->
        screen.runOnIdle { vm.setDraft("") }
        val compact = capsule()
        editor.performTouchInput { click(center) }
        screen.waitUntil(15_000) { imeIsVisible() }
        openControls()
        hideKeyboard()
        screen.waitUntil(15_000) { capsule() == compact }
        assertAnchored("card")
        assertImeStays(false) { enterModels() }
        assertComposerHint(searchHint)
        val query = control("search-field")
        query.performTouchInput { click(center) }
        screen.waitUntil(15_000) { imeIsVisible() }
        query.performTextReplacement("No such model")
        reveal("search-empty")
        assertImeStays(true) { control("search-clear").performTouchInput { click(center) } }
        query.assertIsFocused()
        assertComposerHint(searchHint)
        control("results").assertIsDisplayed()
        assertImeStays(true) { control("list-back").performTouchInput { click(center) } }
        control("card").assertIsDisplayed()
        assertComposerHint("Chat with AI")
        hideKeyboard()
        screen.waitUntil(15_000) { capsule() == compact }
        send.assertIsNotEnabled()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun cancelAfterHidingTheSearchKeyboardRestoresParkedDraftSelectionAndAttachment() =
        withProviders(includeAttachment = true) { vm, _, _ ->
            val draft = "draft stays here"
            val selection = TextRange(2, 7)
            screen.runOnIdle { vm.setDraft(draft) }
            val parked = capsule()
            val attachments = screen.runOnIdle { vm.state.value.draft.attachments }
            editor.performTouchInput { click(center) }
            screen.waitUntil(15_000) { imeIsVisible() && capsule().width > parked.width }
            editor.performTextInputSelection(selection)
            openControls()
            assertImeStays(true) { enterModels().performTextReplacement("Nimbus") }
            assertComposerHint(null)
            hideKeyboard()
            assertImeStays(false) { control("list-back").performTouchInput { click(center) } }
            control("list").assertDoesNotExist()
            editor.assertTextEquals(draft)
            assertComposerHint(null)
            assertAnchored("card")
            screen.onNodeWithTag("chat-composer-dictate").assertIsDisplayed().assertHasClickAction()
            assertEquals(parked, capsule())

            // The capsule's back arrow ends the whole model selection; re-entry starts empty.
            assertImeStays(false) { enterModels() }
            assertImeStays(false) { control("list-back").performTouchInput { click(center) } }
            assertImeStays(false) { tapBackArrow("chat-effort-back") }
            control("card").assertDoesNotExist()
            editor.assertTextEquals(draft)
            assertEquals(parked, capsule())
            assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            screen.runOnIdle { assertEquals(attachments, vm.state.value.draft.attachments) }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test fun listArrowEndsActiveSearchAndEffortArrowClosesWithKeyboardOpen() =
        withProviders(includeAttachment = true) { vm, _, _ ->
            val draft = "first line\nsecond line"
            val selection = TextRange(2, 8)
            editor.performTouchInput { click(center) }
            screen.waitUntil(15_000) { imeIsVisible() }
            editor.performTextReplacement(draft)
            editor.performTextInputSelection(selection)
            val before = screen.runOnIdle { vm.state.value.draft }

            openControls()
            enterModels().performTextReplacement("Nimbus")
            assertImeStays(true) { control("list-back").performTouchInput { click(center) } }
            control("search-field").assertDoesNotExist()
            control("list").assertDoesNotExist()
            assertAnchored("card")
            editor.assertIsFocused().assertTextEquals(draft)
            assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])

            enterModels()  // A new selection starts with an empty query.
            assertImeStays(true) { control("list-back").performTouchInput { click(center) } }
            assertImeStays(true) { tapBackArrow("chat-effort-back") }
            control("card").assertDoesNotExist()
            editor.assertIsFocused().assertTextEquals(draft)
            assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            screen.onNodeWithText("draft-only.txt").assertIsDisplayed()
            screen.runOnIdle { assertEquals(before, vm.state.value.draft) }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test fun editorDoubleTapClosesSearchInOneStepAndKeepsTheEditingSession() =
        withProviders(includeAttachment = true) { vm, _, _ ->
            val draft = "my original draft\nsecond line"
            val selection = TextRange(2, 8)
            editor.performTouchInput { click(center) }
            screen.waitUntil(15_000) { imeIsVisible() }
            editor.performTextReplacement(draft)
            editor.performTextInputSelection(selection)
            val original = screen.runOnIdle { vm.state.value }
            openControls()
            val search = enterModels()
            search.performTextReplacement("Nimbus")
            val textLine = screen.onNodeWithTag("composer-editable-text", useUnmergedTree = true)
            assertImeStays(true) { textLine.performTouchInput { doubleClick(center) } }
            control("search-field").assertDoesNotExist()
            control("list").assertDoesNotExist()
            control("card").assertDoesNotExist()
            editor.assertIsFocused().assertTextEquals(draft)
            assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            screen.onNodeWithText("draft-only.txt").assertIsDisplayed()
            screen.runOnIdle {
                assertEquals(original.draft, vm.state.value.draft)
                assertEquals(original.current.messages, vm.state.value.current.messages)
            }
            openControls()
            enterModels() // A new search has no retained query.
        }

    @Test fun systemBackHidesTheKeyboardBeforeDismissingTheModelPageDirectly() = withProviders { vm, _, _ ->
        screen.runOnIdle { vm.setDraft("parked draft") }
        val parked = capsule()
        openControls()
        val field = enterModels()
        field.performTouchInput { click(center) }
        screen.waitUntil(15_000) { imeIsVisible() }
        field.performTextReplacement("Nimbus")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        screen.waitUntil(15_000) { !imeIsVisible() }
        control("list").assertIsDisplayed()
        field.assertTextEquals("Nimbus")
        assertImeStays(false) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            control("list").assertDoesNotExist()
            control("search-field").assertDoesNotExist()
            control("card").assertDoesNotExist()
        }
        editor.assertTextEquals("parked draft")
        assertEquals(parked, capsule())
        openControls()
        enterModels()
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
        val field = enterModels()
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
        control(option(second, "other-only")).assertDoesNotExist()
        assertActive(active)
        field.performTextReplacement("draft stays")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        field.assertIsFocused().assertTextEquals("draft stays")
        send.assertDoesNotExist()
        assertDraftUntouched()
        assertImeStays(true) { control("search-clear").performClick() }
        reveal(option(first, "last"))
        field.performTextReplacement("Nimbus")
        reveal("provider-trigger").performTouchInput { click(center) }
        val providerTag = "chat-model-provider:${second.id}"
        val providerList = hasTestTag("chat-provider-list")
        screen.onNode(hasScrollAction() and (providerList or hasAnyAncestor(providerList)))
            .performScrollToNode(hasTestTag(providerTag))
        screen.onNodeWithTag(providerTag).performTouchInput { click(center) }
        field.assertIsFocused().assertTextEquals("Nimbus")
        control(option(second, "other-only")).assertDoesNotExist()
        assertActive(active)
        field.performTextReplacement("batch-alpha")
        control(option(first, "batch-alpha")).assertDoesNotExist()
        reveal(option(second, "batch-alpha")).assertIsNotSelected().performTouchInput { click(center) }
        control("card").assertIsDisplayed()
        control("search-field").assertDoesNotExist()
        assertActive(RemoteModel(second.id, "batch-alpha"))
        assertEditing()
        enterModels()
        reveal(option(second, "other-only"))
        control("list-back").performTouchInput { click(center) }
        assertEditing()
    }

    @Test fun lastModelIsReachableAboveTheSearchCapsuleAndCanBePicked() = withProviders { vm, first, _ ->
        screen.runOnIdle { vm.setDraft("model list") }
        editor.performTouchInput { click(center) }
        screen.waitUntil(15_000) { imeIsVisible() }
        openControls()
        enterModels()
        val last = reveal(option(first, "last"))
        control("results").performTouchInput { swipeUp() }
        val row = last.fetchSemanticsNode().boundsInRoot
        val minimum = 48f * screen.activity.resources.displayMetrics.density - 1f
        assertTrue("Last model's tap target is fully available above the live editor", row.height >= minimum && row.bottom <= capsule().top + 1f)
        val rtl = screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        last.performTouchInput { click(Offset(if (rtl) 4.dp.toPx() else width - 4.dp.toPx(), centerY)) }
        control("card").assertIsDisplayed()
        screen.runOnIdle { assertEquals(RemoteModel(first.id, "last"), vm.providers.catalog.value.active[Mode.Chat]) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun longPressEditsTheBrowsedProviderWithoutSelectingItAndRestoresTheSearch() =
        withProviders(includeAttachment = true) { vm, first, second ->
            val beforeTarget = List(14) { ProviderModel("batch-alpha-before-$it", "Before $it") }
            val afterTarget = List(18) { ProviderModel("batch-alpha-after-$it", "After $it") }
            screen.runOnIdle {
                vm.providers.save(second.copy(models = beforeTarget + second.models + afterTarget), "", null)
            }
            val draft = "first line\nsecond line"
            val selection = TextRange(2, 8)
            editor.performTouchInput { click(center) }
            screen.waitUntil(15_000) { imeIsVisible() }
            editor.performTextReplacement(draft)
            editor.performTextInputSelection(selection)
            val savedDraft = screen.runOnIdle { vm.state.value.draft }

            openControls()
            enterModels()
            val providerRow = reveal("provider-trigger")
            providerRow.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            providerRow.performClick()
            val providerTag = "chat-model-provider:${second.id}"
            val providers = hasTestTag("chat-provider-list")
            screen.onNode(hasScrollAction() and (providers or hasAnyAncestor(providers)))
                .performScrollToNode(hasTestTag(providerTag))
            screen.onNodeWithTag(providerTag).performClick()
            val search = control("search-field").assertIsDisplayed()
            val query = "batch-alpha"
            val querySelection = TextRange(2, 6)
            search.performTextReplacement(query)
            search.performTextInputSelection(querySelection)
            val row = reveal(option(second, query)).assertIsNotSelected()
            val results = control("results")
            fun rowOffset() = row.fetchSemanticsNode().positionInRoot.y - results.fetchSemanticsNode().positionInRoot.y
            val inset = 7 * screen.activity.resources.displayMetrics.density
            val delta = rowOffset() + inset
            results.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            val savedOffset = rowOffset()
            assertEquals("First visible model should be partly scrolled", -inset, savedOffset, 1f)
            assertTrue(imeIsVisible())
            row.performTouchInput { longClick(center) }
            control("editor").assertIsDisplayed()
            screen.waitUntil(15_000) { !imeIsVisible() }
            // The other provider uses the same local model ID but has external ID "vendor/atlas-v3".
            screen.onNodeWithContentDescription("模型 ID").assertTextContains("Nimbus")
            val active = RemoteModel(first.id, query)
            screen.runOnIdle { assertEquals(active, vm.providers.catalog.value.active[Mode.Chat]) }

            val name = screen.onNodeWithContentDescription("模型名称").performScrollTo()
            name.performTextReplacement("Renamed on B")
            hideKeyboard()
            screen.onNodeWithContentDescription("保存模型").performClick()
            control("editor").assertDoesNotExist()
            screen.waitUntil(15_000) { imeIsVisible() }
            search.assertIsFocused().assertTextEquals(query)
            assertEquals(querySelection, search.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            control(option(second, query)).assertIsDisplayed().assertIsNotSelected().assertTextContains("Renamed on B")
            assertEquals("The same model keeps its pixel offset in the filtered list", savedOffset, rowOffset(), 1f)
            control(option(first, query)).assertDoesNotExist()
            screen.runOnIdle {
                val catalog = vm.providers.catalog.value
                assertEquals(active, catalog.active[Mode.Chat])
                assertEquals(first, catalog.providers.single { it.id == first.id })
                assertEquals("Renamed on B", catalog.providers.single { it.id == second.id }.models.single { it.id == query }.name)
                val saved = ProviderStore(screen.activity.applicationContext).catalog.value
                assertEquals(catalog.providers.single { it.id == second.id }, saved.providers.single { it.id == second.id })
            }
            assertImeStays(true) { control("list-back").performTouchInput { click(center) } }
            editor.assertIsFocused().assertTextEquals(draft)
            assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            screen.onNodeWithText("draft-only.txt").assertIsDisplayed()
            screen.runOnIdle { assertEquals(savedDraft, vm.state.value.draft) }
        }

    @Test fun longPressFromScrolledListWithClosedKeyboardSlidesInAndReturnsToTheSameRow() =
        withProviders { vm, first, _ ->
            screen.runOnIdle { vm.setDraft("parked") }
            openControls()
            enterModels()
            val row = reveal(option(first, "extra-9")).assertIsNotSelected()
            val list = control("results")
            fun scrollPosition() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val scrollBefore = scrollPosition()
            assertTrue(scrollBefore > 0f)
            assertTrue(!imeIsVisible())
            val panel = control("editor")
            // A bounded intermediate frame checks motion without tying it to the keyboard's duration.
            // Trigger the same long-click semantic here; the keyboard-open case above uses a real pointer.
            val earlyBottom = try {
                screen.mainClock.autoAdvance = false
                row.performSemanticsAction(SemanticsActions.OnLongClick)
                screen.mainClock.advanceTimeBy(ModelEditorMotionMillis / 4L)
                // The top is clipped while the panel enters from above. Its bottom remains observable.
                panel.fetchSemanticsNode().boundsInRoot.bottom
            } finally {
                screen.mainClock.autoAdvance = true
            }
            screen.waitForIdle()
            assertTrue("Configuration moves down from the top", earlyBottom < panel.fetchSemanticsNode().boundsInRoot.bottom)
            screen.onNodeWithContentDescription("模型名称").assertTextContains("Other model 9")
            assertImeStays(false) {
                try {
                    screen.mainClock.autoAdvance = false
                    screen.onNodeWithContentDescription("返回").performClick()
                    screen.mainClock.advanceTimeBy(ModelEditorMotionMillis / 5L)
                    control("list").assertDoesNotExist() // The moving chat cannot expose the picker early.
                } finally {
                    screen.mainClock.autoAdvance = true
                }
            }
            panel.assertDoesNotExist()
            row.assertIsDisplayed().assertIsNotSelected()
            assertEquals(scrollBefore, scrollPosition(), .001f)
            control("search-field").assertIsDisplayed()
            screen.runOnIdle { assertEquals("parked", vm.state.value.draft.text) }
            screen.runOnIdle {
                assertEquals(RemoteModel(first.id, "batch-alpha"), vm.providers.catalog.value.active[Mode.Chat])
            }

            control("search-field").performTouchInput { click(center) }
            screen.waitUntil(15_000) { imeIsVisible() }
            val search = control("search-field")
            search.performTextReplacement("extra-9")
            hideKeyboard()
            row.performTouchInput { longClick(center) }
            control("editor").assertIsDisplayed()
            assertImeStays(false) { screen.onNodeWithContentDescription("返回").performClick() }
            control("editor").assertDoesNotExist()
            search.assertIsDisplayed().assertTextEquals("extra-9")
            row.assertIsDisplayed().assertIsNotSelected()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test fun returningToModelSearchRestoresItsKeyboardWithoutRollingBackNewConversationMessages() =
        withProviders { vm, first, _ ->
            val previous = screen.runOnIdle { vm.state.value.current.id }
            val conversation = screen.runOnIdle { vm.newConversation(); vm.state.value.current.id }
            try {
                val draft = "keep my selection"
                val selection = TextRange(2, 6)
                editor.performTouchInput { click(center) }
                screen.waitUntil(15_000) { imeIsVisible() }
                editor.performTextReplacement(draft)
                editor.performTextInputSelection(selection)
                openControls()
                val search = enterModels()
                search.assertIsFocused()
                reveal(option(first, "batch-beta")).performTouchInput { longClick(center) }
                control("editor").assertIsDisplayed()
                screen.waitUntil(15_000) { !imeIsVisible() }

                // Exercise a real local session update while the configuration page covers the chat.
                val messages = screen.runOnIdle {
                    vm.setDraft("offline background fixture")
                    vm.send("Hydrion", remoteModel = null)
                    vm.stop()
                    vm.state.value.current.messages
                }
                assertTrue(messages.any { it.text == "offline background fixture" })
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                control("editor").assertDoesNotExist()
                screen.waitUntil(15_000) { imeIsVisible() }
                search.assertIsFocused()
                control("list").assertIsDisplayed()
                assertImeStays(true) { control("list-back").performTouchInput { click(center) } }
                editor.assertIsFocused().assertTextEquals(draft)
                assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
                screen.runOnIdle {
                    assertEquals(conversation, vm.state.value.current.id)
                    assertEquals(messages, vm.state.value.current.messages)
                }
            } finally {
                screen.runOnIdle { vm.deleteConversation(conversation); vm.openConversation(previous) }
            }
        }
}
