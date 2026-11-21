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

    @Test fun compactWindowBadgeTruncatesToOneDecimalAtEachUnit() {
        val examples = mapOf(999 to "999", 1000 to "1k", 128_500 to "128.5k", 999_999 to "999.9k",
            1_000_000 to "1M", 1_048_576 to "1M", 1_299_999 to "1.2M")
        examples.forEach { (tokens, display) -> assertEquals(display, browseTokenCount(tokens)) }
    }

    @Test fun draftEditDismissalOnlyClosesEffortAndLeavesModelSearchIntact() {
        val picker = ChatModelPickerState()
        picker.open()
        picker.dismissEffort()
        assertEquals(null, picker.page)

        picker.open()
        picker.models()
        val query = TextFieldValue("model", selection = TextRange(1, 3))
        picker.updateSearchQuery(query)
        picker.dismissEffort()
        assertEquals(ChatModelPickerState.Page.Search, picker.page)
        assertEquals(query, picker.searchQuery)
    }

    @Test fun editorReturnKeepsTheQueryAndConsumesItsScrollSnapshotOnce() {
        val picker = ChatModelPickerState()
        val query = TextFieldValue("  Atlas  ", selection = TextRange(5, 2))
        val browsing = PickerScrollAnchor("model:provider/second", 4, 13)
        val filtered = PickerScrollAnchor("model:provider/first", 2, 7)
        for (page in listOf(ChatModelPickerState.Page.Search, ChatModelPickerState.Page.Models)) {
            picker.open()
            picker.models()
            picker.updateSearchQuery(TextFieldValue("old"))
            assertEquals(ChatModelPickerState.SearchScroll.Filter, picker.pendingSearchScroll)
            val snapshot = PickerBrowseSnapshot(page, query, browsing, filtered, 240f)
            picker.restoreBrowsing(snapshot)
            assertEquals(page, picker.page)
            assertEquals(query, picker.searchQuery)
            assertEquals(null, picker.pendingSearchScroll)
            val nextVisit = PickerBrowseSnapshot(page, query, browsing, filtered, 240f)
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

    @Test fun changingAndClearingSearchKeepsTheGroupedListOpen() {
        val picker = ChatModelPickerState()
        picker.open()
        picker.models()
        picker.updateSearchQuery(TextFieldValue("abc"))
        picker.clearSearchQuery()
        assertEquals(ChatModelPickerState.Page.Search, picker.page)
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
        for (system in listOf(false, true)) {
            picker.open()
            picker.models()
            assertEquals(TextFieldValue(), picker.searchQuery)
            picker.updateSearchQuery(TextFieldValue("pending"))
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

    @Test fun oneListUsesEligibleRoutesAcrossProvidersAndOmitsEmptyGroups() {
        val hidden = first.copy(id = "disabled", enabled = false)
        val image = first.copy(id = "image", profile = ModelProfile(type = ModelType.Image))
        val p = deepSeekProvider.copy(id = "a", name = "First", models = choices + hidden + image)
        val q = p.copy(id = "b", name = "Second", models = listOf(first))
        val catalog = ModelCatalog(listOf(p, q, q.copy(id = "off", enabled = false)))
        val entries = catalog.entries(Mode.Chat)
        val all = modelMenuRows(entries, "")
        assertEquals(listOf(null, first.id, second.id, null, first.id), all.map { it.model?.id })
        assertEquals(all.size, all.map { it.key }.distinct().size)
        assertEquals(listOf("a", "a", "b", "b"), modelMenuRows(entries, "route-X").map { it.provider.id })
        assertEquals(listOf(null, first.id), modelMenuRows(entries, "SeCoNd").map { it.model?.id })
        assertTrue(modelMenuRows(entries, "disabled").isEmpty())
        assertTrue(modelMenuRows(entries, "image").isEmpty())
    }
}
