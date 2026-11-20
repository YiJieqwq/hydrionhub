package io.hydrion.hub.provider

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.hydrion.hub.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelMenuSearchTest {
    private val first = ProviderModel("local-01", "常用模型", profile = ModelProfile(externalId = "route-X"))
    private val second = ProviderModel("local-02", "常用模型")
    private val choices = listOf(first, second)

    @Test fun editorReturnKeepsTheQueryAndConsumesItsScrollSnapshotOnce() {
        val picker = ChatModelPickerState()
        val query = TextFieldValue("  Atlas  ", selection = TextRange(5, 2))
        val browsing = PickerScrollAnchor("model:provider/second", 4, 13)
        val filtered = PickerScrollAnchor("model:provider/first", 2, 7)
        for (page in listOf(ChatModelPickerState.Page.Search, ChatModelPickerState.Page.Models)) {
            picker.open()
            picker.models("provider", startSearching = true)
            picker.updateSearchQuery(TextFieldValue("old"))
            assertEquals(ChatModelPickerState.SearchScroll.Filter, picker.pendingSearchScroll)
            val snapshot = PickerBrowseSnapshot(page, "provider", query, browsing, filtered, 240f)
            picker.restoreBrowsing(snapshot)
            assertEquals(page, picker.page)
            assertEquals("provider", picker.browsedProviderId)
            assertEquals(query, picker.searchQuery)
            assertEquals(null, picker.pendingSearchScroll)
            val nextVisit = PickerBrowseSnapshot(page, "provider", query, browsing, filtered, 240f)
            picker.restoreBrowsing(nextVisit)
            picker.consumeBrowseRestore(snapshot) // A late layout from the first visit cannot consume the second.
            assertSame(nextVisit, picker.pendingBrowseRestore)
            picker.consumeBrowseRestore(nextVisit)
            assertEquals(null, picker.pendingBrowseRestore)
            assertEquals(query, picker.searchQuery)
            picker.updateSearchQuery(query.copy(selection = TextRange(1, 3)))
            assertEquals(null, picker.pendingSearchScroll)
            picker.updateSearchQuery(TextFieldValue("Atlas X"))
            assertEquals(ChatModelPickerState.SearchScroll.Filter, picker.pendingSearchScroll)
            picker.consumeSearchScroll()
            assertEquals(null, picker.pendingSearchScroll)
            picker.submitSearch()
            assertEquals(ChatModelPickerState.SearchScroll.Submit, picker.pendingSearchScroll)
        }
    }

    @Test fun returnScrollAnchorFollowsTheModelAcrossReorderAndFallsBackIfRemoved() {
        val anchor = PickerScrollAnchor("model:p/b", 2, 15)
        assertEquals(0, anchor.resolveIndex(listOf("model:p/b", "model:p/a", "model:p/c")))
        assertEquals(2, anchor.resolveIndex(listOf("model:q/b", "model:p/a", "model:p/c", "model:p/d")))
        assertEquals(1, anchor.resolveIndex(listOf("model:p/a", "model:p/c")))
        assertEquals(0, anchor.resolveIndex(emptyList()))
        assertEquals(15, anchor.offset)
    }

    @Test fun enteringAndChoosingAnotherProviderUsesTheSameSearchEditor() {
        val picker = ChatModelPickerState()
        picker.open()
        picker.models("first", startSearching = true)
        assertEquals(ChatModelPickerState.Page.Search, picker.page)
        assertEquals(TextFieldValue(), picker.searchQuery)
        val value = TextFieldValue("模型名称", selection = TextRange(4, 2), composition = TextRange(1, 3))
        picker.updateSearchQuery(value)
        picker.providers()
        picker.updateSearchQuery(value.copy(selection = TextRange(1)))
        assertEquals(ChatModelPickerState.Page.Providers, picker.page)
        picker.models("second")
        assertEquals("second", picker.browsedProviderId)
        assertEquals(ChatModelPickerState.Page.Search, picker.page)
        assertEquals(value.copy(selection = TextRange(1)), picker.searchQuery)
        assertEquals(ChatModelPickerState.SearchScroll.Filter, picker.pendingSearchScroll)
    }

    @Test fun changingSearchTextFromProvidersShowsMatchesButClearingDoesNotNavigate() {
        val picker = ChatModelPickerState()
        picker.open()
        picker.models("p", startSearching = true)
        picker.updateSearchQuery(TextFieldValue("abc"))
        picker.providers()
        picker.clearSearchQuery()
        assertEquals(ChatModelPickerState.Page.Providers, picker.page)
        assertEquals(TextFieldValue(), picker.searchQuery)
        picker.updateSearchQuery(TextFieldValue("Atlas"))
        assertEquals(ChatModelPickerState.Page.Search, picker.page)
        picker.clearSearchQuery()
        assertEquals(TextFieldValue(), picker.searchQuery)
        assertEquals(null, picker.pendingSearchScroll)
        assertEquals(ChatModelPickerState.Page.Search, picker.page)
    }

    @Test fun editorBackReturnsToEffortAndAppBackDismissesFromEitherPage() {
        val picker = ChatModelPickerState()
        for (providers in listOf(false, true)) for (system in listOf(false, true)) {
            picker.open()
            picker.models("first", startSearching = true)
            assertEquals(TextFieldValue(), picker.searchQuery)
            picker.updateSearchQuery(TextFieldValue("pending"))
            if (providers) picker.providers()
            assertTrue(picker.modelListNavigation)
            if (system) picker.systemBack() else picker.open()
            assertEquals(if (system) null else ChatModelPickerState.Page.Controls, picker.page)
            assertFalse(picker.modelListNavigation)
            picker.updateSearchQuery(TextFieldValue("late input"))
            assertEquals(TextFieldValue(), picker.searchQuery)
            assertEquals(null, picker.pendingSearchScroll)
        }
    }

    @Test fun systemBackFromOptionsReturnsToControlsAndFromControlsDismisses() {
        val picker = ChatModelPickerState()
        picker.open()
        picker.options(Capability("effort", "Effort", listOf("Low", "High")))
        assertFalse(picker.modelListNavigation)
        picker.systemBack()
        assertEquals(ChatModelPickerState.Page.Controls, picker.page)
        assertFalse(picker.modelListNavigation)
        picker.systemBack()
        assertEquals(null, picker.page)
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
