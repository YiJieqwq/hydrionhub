package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatModelMenuSearchTest {
    private val first = ProviderModel("local-01", "常用模型", profile = ModelProfile(externalId = "route-X"))
    private val second = ProviderModel("local-02", "常用模型")
    private val choices = listOf(first, second)

    @Test fun enteringFromTheEffortCardSearchesOnceAndCancelReturnsToBrowsing() {
        val picker = ChatModelPickerState()
        picker.open()
        assertEquals(ChatModelPickerState.Page.Controls, picker.page)
        picker.models("first", startSearching = true)
        assertEquals(ChatModelPickerState.Page.Search, picker.page)
        assertEquals("", picker.searchQuery)
        picker.back()
        assertEquals(ChatModelPickerState.Page.Models, picker.page)
        picker.providers()
        picker.models("second")
        assertEquals(ChatModelPickerState.Page.Models, picker.page)
        picker.search()
        assertEquals(ChatModelPickerState.Page.Search, picker.page)
        picker.open()
        assertEquals(ChatModelPickerState.Page.Controls, picker.page)
    }

    @Test fun aQueryLivesOnlyForOneExplicitSearch() {
        val picker = ChatModelPickerState()
        val exits = listOf<() -> Unit>(picker::back, picker::dismiss, picker::providers, picker::open)
        for (exit in exits) {
            picker.models("provider")
            picker.search()
            assertEquals("", picker.searchQuery)
            picker.updateSearchQuery("草稿以外的查询")
            picker.submitSearch()
            assertEquals(1, picker.searchSubmissions)
            exit()
            picker.updateSearchQuery("late IME edit")
            picker.submitSearch()
            assertEquals("", picker.searchQuery)
            assertEquals(0, picker.searchSubmissions)
        }
    }

    @Test fun matchesNamesAndBothIdsWithoutChangingOrder() {
        assertSame(choices, matchingMenuModels(choices, "  "))
        assertEquals(choices, matchingMenuModels(choices, " 用模 "))
        assertEquals(listOf(first), matchingMenuModels(choices, " UTE-x "))
        assertEquals(listOf(second), matchingMenuModels(choices, "CAL-02"))
        assertEquals(emptyList<ProviderModel>(), matchingMenuModels(choices, "absent"))
    }

    @Test fun onlyEnabledChatModelsFromTheBrowsedProviderCanMatch() {
        val hidden = first.copy(id = "disabled", enabled = false)
        val image = first.copy(id = "image", profile = ModelProfile(type = ModelType.Image))
        val provider = deepSeekProvider.copy(id = "selected", models = choices + hidden + image)
        val other = provider.copy(id = "other", models = listOf(first.copy(id = "other-only")))
        val disabled = other.copy(id = "offline", enabled = false)
        val catalog = ModelCatalog(listOf(provider, other, disabled))
        val candidates = catalog.entries(Mode.Chat).filter { it.second.id == provider.id }.map { it.first }
        assertEquals(choices, matchingMenuModels(candidates, "模型"))
        assertEquals(emptyList<ProviderModel>(), matchingMenuModels(candidates, "other-only"))
    }
}
