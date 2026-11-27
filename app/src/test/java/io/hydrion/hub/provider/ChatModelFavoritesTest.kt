package io.hydrion.hub.provider

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.hydrion.hub.MainActivity
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.WorkspaceLoad
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h880dp-xhdpi")
class ChatModelFavoritesTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun node(tag: String) = screen.onNodeWithTag(tag)
    private val results get() = node("chat-model-results")

    private fun item(route: RemoteModel, shortcut: Boolean = false, heart: Boolean = false): SemanticsNodeInteraction {
        val prefix = if (shortcut) "chat-model-favorite-option" else "chat-model-option"
        val tag = "$prefix:${route.providerId}/${route.modelId}" + if (heart) ":favorite" else ""
        results.performScrollToNode(hasTestTag(tag))
        return node(tag).assertIsDisplayed()
    }

    @Test fun favoritesFromTheChatPickerSurviveReloadWithoutChoosingOrEditingTheModel() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        screen.waitUntil(10_000) { vm.workspaceLoad.value == WorkspaceLoad.Ready }
        val model = ProviderModel("atlas", "Atlas", profile = ModelProfile(externalId = "garden-fixture"))
        val north = Provider("north", Mode.Chat, "North", "local", "", listOf(model))
        val south = north.copy(id = "south", name = "South")
        val first = RemoteModel(north.id, model.id)
        val second = RemoteModel(south.id, model.id)
        screen.runOnIdle {
            vm.providers.save(north, "", null)
            vm.providers.save(south, "", null)
            vm.providers.configure(Mode.Chat, first, emptyMap())
            vm.setDraft("Garden notes")
        }
        val original = screen.runOnIdle { vm.providers.backupCatalog() }
        fun assertSaved(vararg favorites: RemoteModel) {
            screen.runOnIdle {
                val expected = original.copy(favorites = favorites.toSet())
                assertEquals(expected, vm.providers.backupCatalog())
                assertEquals(expected, ProviderStore(screen.activity).backupCatalog())
                assertEquals(first, vm.state.value.current.modelRoutes?.get(Mode.Chat))
                assertEquals("Garden notes", vm.state.value.draft.text)
            }
        }

        node("chat-model-trigger").performClick()
        node("chat-model-select").performClick()
        val query = node("chat-model-search-field")
        query.performTextReplacement("garden-fixture")
        item(second, heart = true).assertIsOff().performTouchInput { click(center) }
        item(second, heart = true).assertIsOn()
        item(first, heart = true).assertIsOff()
        item(first).assertIsSelected()
        item(second).assertIsNotSelected()
        node("chat-model-editor").assertDoesNotExist()
        screen.onNodeWithText("Favorites").assertDoesNotExist()
        assertSaved(second)

        item(first, heart = true).performTouchInput { longClick(center) }
        node("chat-model-editor").assertDoesNotExist()
        assertSaved(first, second)
        query.performTextClearance()
        results.performScrollToIndex(0)
        screen.onNodeWithText("Favorites").assertIsDisplayed()
        item(first, shortcut = true).assert(hasText("Atlas | North"))
        val shortcut = item(second, shortcut = true).assert(hasText("Atlas | South"))
        val beforeEdit = shortcut.fetchSemanticsNode().boundsInRoot.top
        shortcut.performTouchInput { longClick(center) }
        node("chat-model-config").assertIsDisplayed()
        screen.runOnIdle { screen.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(beforeEdit, shortcut.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
        item(second, shortcut = true, heart = true).assertIsOn().performTouchInput { click(center) }
        item(second, heart = true).assertIsOff()
        item(first, heart = true).assertIsOn()
        assertSaved(first)

        item(first, shortcut = true, heart = true).performTouchInput { click(center) }
        results.performScrollToIndex(0)
        screen.onNodeWithText("Favorites").assertDoesNotExist()
        assertSaved()
        item(second).performTouchInput { click(center) }
        node("chat-model-card").assertIsDisplayed()
        screen.runOnIdle { assertEquals(second, vm.state.value.current.modelRoutes?.get(Mode.Chat)) }
    }
}
