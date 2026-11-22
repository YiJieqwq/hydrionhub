package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderDraftKeyTest {
    private val first = newProvider(Mode.Chat).copy(name = "Fixture", baseUrl = "https://first.invalid/v1")
    private val second = first.copy(baseUrl = "https://second.invalid/v1")
    private val token = "synthetic-first-key"

    private fun store() = ProviderStore(object : ProviderCatalogStorage {
        private val records = mutableMapOf<String, String>()
        override fun initialCatalog() = ModelCatalog(emptyList())
        override fun read(key: String) = records[key]
        override fun write(key: String, value: String) { records[key] = value }
    })

    @Test fun reopeningAStaleSnapshotDoesNotPrefillTheCurrentOriginsKey() {
        val store = store()
        store.save(second, "synthetic-second-key", null)
        val stale = store.catalog.value.providers.single()
        store.save(first, token, null)
        val current = store.catalog.value.providers.single()
        val navigation = ProviderNavigation()

        navigation.open(stale, store)
        val draft = requireNotNull(navigation.edit)
        assertEquals("", draft.apiKey)
        assertEquals("", draft.keyForCurrentAddress(stale))
        navigation.open(current, store)
        assertEquals(token, requireNotNull(navigation.edit).apiKey)
    }

    @Test fun editingThePathKeepsTheKeyButChangingHostOrPortHidesIt() {
        val store = store()
        store.save(first, token, null)
        val draft = requireNotNull(ProviderNavigation().apply { open(first, store) }.edit)
        val sameOrigin = first.copy(baseUrl = "https://FIRST.invalid:443/other/path")
        draft.provider = sameOrigin
        assertEquals(token, draft.apiKey)
        assertEquals(token, draft.keyForCurrentAddress())

        for (newAddress in listOf(second, first.copy(baseUrl = "https://first.invalid:8443/v1"))) {
            draft.provider = newAddress
            assertEquals("", draft.apiKey)
            assertEquals("", draft.keyForCurrentAddress(newAddress))
            assertThrows(IllegalStateException::class.java) { draft.keyForCurrentAddress(sameOrigin) }
            draft.provider = first
            assertEquals(token, draft.apiKey)
            assertEquals(token, draft.keyForCurrentAddress())
        }
    }

    @Test fun unsavedModelHeadersStayWithTheAddressWhereTheyWereEntered() {
        val store = store()
        store.save(first, "", null)
        val draft = requireNotNull(ProviderNavigation().apply { open(first, store) }.edit)
        val field = "X-Fixture"
        val atFirst = mapOf(field to "synthetic-first-header")
        val atSecond = mapOf(field to "synthetic-second-header")
        val emptyValues = mapOf(field to "")
        val model = ProviderModel("draft-model", "Draft model", profile = ModelProfile(headers = atFirst))
        draft.finishModel(model, makeDefault = false)

        fun switchTo(address: Provider) {
            draft.provider = draft.provider.copy(baseUrl = address.baseUrl)
        }
        fun modelForEdit() = draft.modelForCurrentAddress(draft.provider.models.single())
        fun assertHeaders(expected: Map<String, String>) {
            assertEquals(expected, modelForEdit().profile.headers)
            assertEquals(expected, draft.providerForSave().models.single().profile.headers)
        }

        switchTo(second)
        assertHeaders(emptyValues)
        switchTo(first)
        assertHeaders(atFirst)

        switchTo(second)
        val edited = modelForEdit()
        draft.finishModel(edited.copy(profile = edited.profile.copy(headers = atSecond)), makeDefault = false)
        assertHeaders(atSecond)
        switchTo(first)
        assertFalse(modelForEdit().profile.headers.containsValue(atSecond.getValue(field)))
        assertFalse(draft.providerForSave().models.single().profile.headers.containsValue(atSecond.getValue(field)))
        switchTo(second)
        assertHeaders(atSecond)
        assertEquals(emptyList<ProviderModel>(), store.catalog.value.providers.single().models)
    }

    @Test fun enteringAKeyBeforeTheUrlBindsItToTheFirstUsedAddress() {
        val blank = first.copy(baseUrl = "")
        val draft = requireNotNull(ProviderNavigation().apply { open(blank, store()) }.edit)
        draft.apiKey = token
        assertEquals(token, draft.apiKey)
        draft.provider = first
        assertThrows(IllegalStateException::class.java) { draft.keyForCurrentAddress(second) }
        assertEquals(token, draft.keyForCurrentAddress(first))

        draft.provider = second
        assertEquals("", draft.apiKey)
        assertEquals("", draft.keyForCurrentAddress())
        draft.provider = first
        assertEquals(token, draft.keyForCurrentAddress())
    }
}
