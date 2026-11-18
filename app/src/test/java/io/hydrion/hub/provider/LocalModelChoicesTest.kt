package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import org.junit.Assert.*
import org.junit.Test

class LocalModelChoicesTest {
    @Test fun searchUsesTheConfiguredModelTypeExternalIdAndAbilities() {
        val model = ProviderModel("vector", "本地向量", profile = ModelProfile(
            type = ModelType.Embedding, externalId = "embed-v3", abilities = setOf("structured")))
        val provider = demoCatalog().providers.first().copy(models = listOf(model))
        for (query in listOf("Embedding", "嵌入", "embed-v3", "结构化")) {
            assertEquals(listOf(model), localModelChoices(provider, query).configured)
        }
        assertTrue(localModelChoices(provider, "Chat").configured.isEmpty())
    }

    @Test fun defaultsOfferAtLeastOneRelevantLocalPreset() {
        for (provider in demoCatalog().providers) {
            val choices = localModelChoices(provider, "")
            assertEquals(provider.models, choices.configured)
            assertTrue(choices.available.isNotEmpty())
            assertTrue(choices.available.all { preset -> preset.capabilities.all { it in supportedCapabilities(provider.mode) } })
        }
    }

    @Test fun searchMatchesDisplayNameOrActualCapabilitiesAcrossBothSections() {
        val chat = demoCatalog().providers.first { it.mode == Mode.Chat }
        val byName = localModelChoices(chat, "  MINI  ")
        assertEquals(listOf("Hydrion Mini"), byName.configured.map { it.name })
        assertTrue(byName.available.isEmpty())
        val byCapability = localModelChoices(chat, "速度")
        assertEquals(listOf("Hydrion"), byCapability.configured.map { it.name })
        assertEquals(listOf("Hydrion Chat Standard"), byCapability.available.map { it.name })
        assertEquals(ModelChoices(emptyList(), emptyList()), localModelChoices(chat, "does not exist"))
    }

    @Test fun filteredBatchOnlyAddsVisiblePresetsAndRepeatedClicksAreIdempotent() {
        val provider = demoCatalog().providers.first().copy(id = "draft-provider", models = emptyList())
        val filteredIds = localModelChoices(provider, "速度").available.map { it.id }.toSet()
        val added = addLocalModels(provider, filteredIds + "not-a-preset")
        assertEquals(listOf("Hydrion", "Hydrion Chat Standard"), added.map { it.name })
        val next = provider.copy(models = added)
        assertEquals(added, addLocalModels(next, filteredIds))
        assertTrue(localModelChoices(next, "速度").available.isEmpty())
        assertEquals(listOf("Hydrion Mini"), localModelChoices(next, "").available.map { it.name })
    }

    @Test fun duplicateChecksHonorEditedIdentityAndManualNamesButStayLocalToTheProvider() {
        val empty = demoCatalog().providers.first().copy(id = "p1", models = emptyList())
        val preset = localModelPresets(Mode.Chat).first()
        val renamed = addLocalModels(empty, setOf(preset.id)).single().copy(name = "Modified", capabilities = emptyList())
        val edited = empty.copy(models = listOf(renamed))
        assertEquals(listOf(renamed), addLocalModels(edited, setOf(preset.id)))
        val manual = empty.copy(models = listOf(ProviderModel("m", "  HYDRION  ")))
        assertTrue(localModelChoices(manual, "").available.none { it.id == preset.id })
        val other = addLocalModels(empty.copy(id = "p2"), setOf(preset.id)).single()
        assertNotEquals(renamed.id, other.id)
        val foreignModeId = localModelPresets(Mode.Image).first().id
        assertTrue(addLocalModels(empty, setOf(foreignModeId)).isEmpty())
    }
}
