package io.hydrion.hub.provider

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.model.Mode
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDiscoveryPickerTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val configured = ProviderModel(
        id = "local-record", name = "Configured fixture", enabled = false,
        profile = ModelProfile(externalId = "fixture/already"),
    )
    private val provider = Provider(
        id = "fixture-provider", mode = Mode.Chat, name = "Fixture",
        protocol = ChatProtocol.ChatCompletionsHttp.label, baseUrl = "https://fixture.invalid/v1",
        models = listOf(configured),
    )
    private val draft = ProviderDraft(provider, true, emptyMap(), "")
    private val requests = AtomicInteger()
    private val changes = mutableListOf<List<ProviderModel>>()

    private fun showTab(fetch: suspend () -> List<String>) {
        screen.runOnUiThread {
            screen.activity.setContent {
                MaterialTheme {
                    ProviderModelsTab(
                        provider = draft.provider,
                        defaultIds = emptySet(),
                        onModelsChange = { models ->
                            changes.add(models)
                            draft.replaceModels(models)
                        },
                        onEdit = {},
                        fetchModels = {
                            requests.incrementAndGet()
                            fetch()
                        },
                    )
                }
            }
        }
        screen.runOnIdle { assertEquals(0, requests.get()) }
    }

    private fun label(resource: Int) = screen.activity.getString(resource)
    private fun button(resource: Int) = screen.onNode(hasText(label(resource)) and hasClickAction())
    private fun model(id: String) = screen.onNode(hasText(id) and isToggleable())

    private fun assertDraftUntouched() = screen.runOnIdle {
        assertFalse(draft.dirty)
        assertEquals(listOf(configured), draft.provider.models)
        assertEquals(0, changes.size)
    }

    @Test fun choosingAcrossSearchesAppendsOnceAndPreservesConfiguredModels() {
        val response = CompletableDeferred<List<String>>()
        showTab { response.await() }
        button(R.string.model_discovery_fetch).performClick()
        screen.onNodeWithText(label(R.string.model_discovery_loading)).assertExists()
        response.complete(listOf("fixture/already", "fixture/alpha", "fixture/beta"))
        model("fixture/already").assertIsNotEnabled()
        button(R.string.model_discovery_add_selected).assertIsNotEnabled()
        screen.onNodeWithText(label(R.string.model_discovery_search)).assertExists()

        val search = screen.onNode(hasSetTextAction())
        search.performTextReplacement("alpha")
        model("fixture/alpha").performClick()
        search.performTextReplacement("beta")
        model("fixture/alpha").assertDoesNotExist()
        model("fixture/beta").performClick()
        search.performTextReplacement("")
        model("fixture/alpha").assertIsOn()
        model("fixture/beta").assertIsOn()
        assertDraftUntouched()

        button(R.string.model_discovery_add_selected).performClick()
        screen.runOnIdle {
            assertEquals(1, requests.get())
            assertEquals(draft.provider.models, changes.single())
            assertEquals(3, draft.provider.models.size)
            assertEquals(setOf("fixture/already", "fixture/alpha", "fixture/beta"),
                draft.provider.models.map { it.externalModelId }.toSet())
            assertEquals(configured, draft.provider.models.single { it.id == configured.id })
        }
    }

    @Test fun cancelDiscardsSelectionWithoutWritingTheDraft() {
        showTab { listOf("fixture/alpha") }
        button(R.string.model_discovery_fetch).performClick()
        model("fixture/alpha").performClick()
        button(R.string.settings_cancel).performClick()
        assertDraftUntouched()

        button(R.string.model_discovery_fetch).performClick()
        model("fixture/alpha").assertIsOff()
        button(R.string.model_discovery_add_selected).assertIsNotEnabled()
        screen.runOnIdle { assertEquals(2, requests.get()) }
    }

    @Test fun failedRequestRetriesOnlyOnDemandAndShowsReturnedModels() {
        showTab {
            if (requests.get() == 1) throw IOException("Synthetic catalog failure")
            listOf("fixture/recovered")
        }
        button(R.string.model_discovery_fetch).performClick()
        screen.onNodeWithText(label(R.string.model_discovery_error)).assertExists()
        screen.runOnIdle { assertEquals(1, requests.get()) }
        button(R.string.model_discovery_retry).performClick()
        model("fixture/recovered").assertIsEnabled()
        screen.runOnIdle { assertEquals(2, requests.get()) }
        assertDraftUntouched()
    }

    @Test fun emptyResponseShowsTheEmptyStateWithoutChangingTheDraft() {
        showTab { emptyList() }
        button(R.string.model_discovery_fetch).performClick()
        screen.onNodeWithText(label(R.string.model_discovery_empty)).assertExists()
        assertDraftUntouched()
    }
}
