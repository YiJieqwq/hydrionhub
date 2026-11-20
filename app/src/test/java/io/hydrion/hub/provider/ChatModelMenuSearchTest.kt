package io.hydrion.hub.provider

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
        assertEquals(TextFieldValue(), picker.searchQuery)
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

    @Test fun pausingSearchAndSwitchingProvidersPreservesQueryAndSelection() {
        val picker = ChatModelPickerState()
        picker.open()
        picker.models("first", startSearching = true)
        val query = TextFieldValue("  模型名  ", selection = TextRange(4, 2), composition = TextRange(2, 5))
        picker.updateSearchQuery(query)
        picker.submitSearch()
        assertEquals(1, picker.searchSubmissions)

        picker.back()
        picker.updateSearchQuery(TextFieldValue("late IME edit"))
        picker.submitSearch()
        val parked = query.copy(composition = null)
        assertEquals(parked, picker.searchQuery)
        assertEquals(0, picker.searchSubmissions)
        picker.search()
        assertEquals(parked, picker.searchQuery)

        picker.providers()
        picker.models("second")
        picker.search()
        assertEquals("second", picker.browsedProviderId)
        assertEquals(parked, picker.searchQuery)
    }

    @Test fun leavingModelSelectionDiscardsAPausedOrActiveQuery() {
        val picker = ChatModelPickerState()
        val exits = listOf<Pair<() -> Unit, ChatModelPickerState.Page?>>(
            { picker.back(); picker.back() } to ChatModelPickerState.Page.Controls,
            { picker.back(); picker.dismiss() } to null,
            picker::dismiss to null,
            picker::open to ChatModelPickerState.Page.Controls, // The list arrow while searching or a model selection.
            { picker.back(); picker.open() } to ChatModelPickerState.Page.Controls, // The same arrow after pressing ×.
        )
        for ((exit, expectedPage) in exits) {
            picker.open()
            picker.models("provider", startSearching = true)
            assertEquals(TextFieldValue(), picker.searchQuery)
            picker.updateSearchQuery(TextFieldValue("test"))
            exit()
            assertEquals(expectedPage, picker.page)
            picker.updateSearchQuery(TextFieldValue("late edit"))
            assertEquals(TextFieldValue(), picker.searchQuery)
            picker.dismiss() // The controls arrow leaves the composer as the only active surface.
            assertEquals(null, picker.page)
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
