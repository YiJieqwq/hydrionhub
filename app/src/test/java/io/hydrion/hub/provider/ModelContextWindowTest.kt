package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import org.junit.Assert.*
import org.junit.Test

class ModelContextWindowTest {
    @Test fun windowOverrideValidatesAndSnapshots() {
        val model = deepSeekProvider.models.first()
        assertEquals(1_000_000, model.contextWindowTokens)
        assertEquals(128_000, ProviderModel("demo","Hydrion").contextWindowTokens)
        val draft = ModelEditDraft(model, Mode.Chat, false)
        for (bad in listOf("0", "-1", "4k", "20000000000")) {
            draft.contextWindow = bad
            assertEquals(ModelPage.Basic, draft.error(emptyList())?.first)
        }
        draft.contextWindow = "16000"
        assertNull(draft.error(emptyList()))
        val edited = draft.buildModel()
        assertEquals(16_000, edited.contextWindowTokens)
        assertEquals("16000", edited.requestParameters(emptyMap())[CONTEXT_WINDOW_PARAMETER])
        val restored = ModelEditDraft(edited, Mode.Chat, false)
        restored.contextWindow = ""
        assertEquals(1_000_000, restored.buildModel().contextWindowTokens)
    }

    @Test fun presetLeavesLocalSelectedAndCanBeRestoredByStableId() {
        val catalog = demoCatalog().withDeepSeekPreset()
        assertEquals("demo-reason", catalog.selectedModel(Mode.Chat)?.id)
        assertEquals(listOf("deepseek-flash","deepseek-v4-pro"), deepSeekProvider.models.map { it.id })
        val empty = deepSeekProvider.copy(models = emptyList())
        assertEquals(deepSeekProvider.models, localModelChoices(empty, "").available)
        assertEquals(deepSeekProvider.models, addLocalModels(empty, deepSeekProvider.models.map { it.id }.toSet()))
    }
}
