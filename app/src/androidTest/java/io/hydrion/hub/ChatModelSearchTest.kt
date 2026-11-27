package io.hydrion.hub

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
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
            ProviderModel("batch-alpha", "Nimbus", profile = ModelProfile(externalId = "atlas/remote-b")), ProviderModel("other-only", "Elsewhere"),
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

    private fun enterModels(): SemanticsNodeInteraction {
        control("select").performTouchInput { click(center) }
        control("list").assertIsDisplayed()
        screen.waitUntil(15_000) { !imeIsVisible() }
        val inShell = hasAnyAncestor(hasTestTag("composer-capsule"))
        return screen.onNode(hasTestTag("chat-model-search-field") and inShell)
            .assertIsDisplayed().assertIsNotFocused().assert(hasImeAction(ImeAction.Search))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun searchUsesItsOwnImeAndPreservesDraftAndRouteUntilAModelIsPicked() =
        withProviders(includeAttachment = true) { vm, first, second ->
            val draft = "keep my draft"
            val range = TextRange(2, 7)
            editor.performTouchInput { click(center) }
            screen.waitUntil(15_000) { imeIsVisible() }
            editor.performTextReplacement(draft)
            editor.performTextInputSelection(range)
            val saved = screen.runOnIdle { vm.state.value.draft }
            openControls()
            val search = enterModels()
            search.performTouchInput { click(center) }
            screen.waitUntil(15_000) { imeIsVisible() }
            search.performTextReplacement("nIMBus")
            reveal(option(first, "batch-alpha")).assertIsSelected()
            reveal(option(second, "batch-alpha")).assertIsNotSelected()
            search.performTextReplacement("  /AtLaS-v3  ")
            reveal(option(first, "batch-alpha")).assertIsSelected()
            control(option(first, "batch-beta")).assertDoesNotExist()
            search.performTextReplacement("no-such-model-qz")
            reveal("search-empty")
            search.performImeAction()
            screen.runOnIdle {
                assertEquals(saved, vm.state.value.draft)
                assertEquals(RemoteModel(first.id, "batch-alpha"), vm.providers.catalog.value.active[Mode.Chat])
            }
            search.performTextClearance()
            reveal(option(first, "last"))
            search.performTextReplacement("Elsewhere")
            reveal(option(second, "other-only")).performTouchInput { click(center) }
            control("card").assertIsDisplayed()
            screen.waitUntil(15_000) { !imeIsVisible() }
            enterModels()
            reveal(option(second, "other-only")).assertIsSelected()
            screen.onNodeWithTag("composer-panel-dismiss").performClick()
            control("list").assertDoesNotExist()
            editor.assertIsNotFocused().assertTextEquals(draft)
            assertEquals(range, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            screen.onNodeWithText("draft-only.txt").assertIsDisplayed()
            screen.runOnIdle {
                assertEquals(saved, vm.state.value.draft)
                assertEquals(RemoteModel(second.id, "other-only"), vm.providers.catalog.value.active[Mode.Chat])
            }
        }

    @Test fun systemBackDismissesSearchKeyboardBeforeThePanel() = withProviders { vm, _, _ ->
        screen.runOnIdle { vm.setDraft("parked") }
        openControls()
        val search = enterModels()
        search.performTouchInput { click(center) }
        screen.waitUntil(15_000) { imeIsVisible() }
        search.performTextInput("Nimbus")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        screen.waitUntil(15_000) { !imeIsVisible() }
        search.assertIsDisplayed().assertTextContains("Nimbus")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        control("list").assertDoesNotExist()
        editor.assertIsNotFocused().assertTextEquals("parked")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun longPressUpdatesTheCorrectProviderAndRestoresTheListAndDraft() =
        withProviders(includeAttachment = true) { vm, first, second ->
            val draft = "keep my selection"
            val range = TextRange(2, 6)
            editor.performTouchInput { click(center) }
            screen.waitUntil(15_000) { imeIsVisible() }
            editor.performTextReplacement(draft)
            editor.performTextInputSelection(range)
            val saved = screen.runOnIdle { vm.state.value.draft }
            openControls()
            val search = enterModels()
            search.performTouchInput { click(center) }
            screen.waitUntil(15_000) { imeIsVisible() }
            search.performTextReplacement("Nimbus")
            val querySelection = TextRange(2, 5)
            search.performTextInputSelection(querySelection)
            hideKeyboard()
            val row = reveal(option(second, "batch-alpha"))
            val top = row.fetchSemanticsNode().boundsInRoot.top
            row.performTouchInput { longClick(center) }
            control("editor").assertIsDisplayed()
            screen.onNodeWithContentDescription(screen.activity.getString(R.string.model_id)).assertTextContains("atlas/remote-b")
            screen.onNodeWithContentDescription(screen.activity.getString(R.string.model_name)).performScrollTo().performTextReplacement("Nimbus B")
            hideKeyboard()
            screen.onNodeWithContentDescription(screen.activity.getString(R.string.model_save)).performClick()
            control("editor").assertDoesNotExist()
            search.assertTextContains("Nimbus").assertIsNotFocused()
            assertEquals(querySelection, search.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            row.assertIsNotSelected().assertTextContains("Nimbus B")
            assertEquals(top, row.fetchSemanticsNode().boundsInRoot.top, 1f)
            screen.runOnIdle {
                val reloaded = ProviderStore(screen.activity.applicationContext).catalog.value
                assertEquals(first, reloaded.providers.single { it.id == first.id })
                assertEquals("Nimbus B", reloaded.providers.single { it.id == second.id }.models.first().name)
                assertEquals(RemoteModel(first.id, "batch-alpha"), reloaded.active[Mode.Chat])
            }
            screen.onNodeWithTag("composer-panel-dismiss").performClick()
            editor.assertTextEquals(draft).assertIsNotFocused()
            assertEquals(range, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            assertTrue(!imeIsVisible())
            screen.runOnIdle { assertEquals(saved, vm.state.value.draft) }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test fun returningFromModelEditingPreservesNewConversationMessages() = withProviders { vm, first, _ ->
        val previous = screen.runOnIdle { vm.state.value.current.id }
        val chat = screen.runOnIdle { vm.newConversation(); vm.state.value.current.id }
        try {
            val draft = "keep my selection"
            val selection = TextRange(2, 6)
            editor.performTouchInput { click(center) }
            editor.performTextReplacement(draft)
            editor.performTextInputSelection(selection)
            openControls()
            val search = enterModels()
            reveal(option(first, "batch-beta")).performTouchInput { longClick(center) }
            control("editor").assertIsDisplayed()
            val messages = screen.runOnIdle {
                vm.setDraft("offline background fixture")
                vm.send("Offline fixture", search = "禁用", imageTool = "禁用", remoteModel = null)
                vm.stop()
                vm.state.value.current.messages
            }
            assertTrue(messages.any { it.text == "offline background fixture" })
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            search.assertIsDisplayed().assertIsNotFocused()
            screen.onNodeWithTag("composer-panel-dismiss").performClick()
            editor.assertIsNotFocused().assertTextEquals(draft)
            assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            screen.runOnIdle {
                assertEquals(chat, vm.state.value.current.id)
                assertEquals(messages, vm.state.value.current.messages)
            }
        } finally {
            screen.runOnIdle { vm.deleteConversation(chat); vm.openConversation(previous) }
        }
    }
}
