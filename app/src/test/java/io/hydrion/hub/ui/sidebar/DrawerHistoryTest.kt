package io.hydrion.hub.ui.sidebar

import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Role
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerHistoryTest {
    private val today = LocalDate.of(2026, 10, 5)
    private fun timestamp(day: LocalDate) = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun topicsFilterTitlesWhileGlobalAlsoSearchesVisibleBodies() {
        val yesterday = today.minusDays(1)
        val pinned = Conversation(id = "p", title = "Needle", updatedAt = timestamp(yesterday), pinned = true)
        val other = Conversation(id = "x", title = "different", updatedAt = timestamp(today))
        val text = other.copy(id = "t", messages = listOf(Message(role = Role.User, text = "needle")), updatedAt = other.updatedAt + 1)
        val prompt = other.copy(id = "i", messages = listOf(Message(role = Role.Assistant, prompt = "a needle", imageKeys = listOf("result"))), updatedAt = timestamp(yesterday))
        val history = listOf(other, prompt, pinned, text)
        val all = historySections(history, "")
        assertEquals(listOf(null, today, yesterday), all.map { it.date })
        assertEquals(listOf("p", "t", "x", "i"), all.flatMap { it.rows }.map { it.id })
        assertEquals(listOf("p"), historySections(history, " NEEDLE ").flatMap { it.rows }.map { it.id })
        assertEquals(listOf("p", "t", "i"), historySections(history, " NEEDLE ", global = true).flatMap { it.rows }.map { it.id })
        assertEquals(listOf("t", "i"), historySections(history, "different  needle", global = true).flatMap { it.rows }.map { it.id })
        assertEquals(emptyList<HistorySection>(), historySections(history, "absent"))
    }

    @Test fun assistantTopicsAreScopedWhileGlobalSearchAndHistoryIncludeAllOwners() {
        val first = Conversation(id = "one", title = "work", assistantId = "a")
        val second = first.copy(id = "two", assistantId = "b")
        val rows = listOf(first, second)
        assertEquals(listOf(first), historySections(rows, "", assistantId = "a").single().rows)
        assertEquals(rows, historySections(rows, "work", global = true, assistantId = "a").single().rows)
        assertEquals(rows, historySections(rows, "").single().rows)
    }

    @Test fun emptySearchClaimsEitherHorizontalDirectionAndTogglesOnlyOncePerDrag() {
        for (sign in listOf(-1f, 1f)) {
            fun SearchDrag.move(distance: Int, blank: Boolean = true) =
                advance(distance * sign, 1f, slop = 8f, threshold = 18f, blank = blank)
            val claimed = SearchDrag.Pending.move(9)
            assertEquals(SearchDrag.Pending, SearchDrag.Pending.move(4))
            assertEquals(SearchDrag.Horizontal, claimed)
            assertEquals(SearchDrag.Switched, claimed.move(20))
            assertEquals(SearchDrag.Switched, SearchDrag.Switched.move(-10))
            assertEquals(SearchDrag.Horizontal, claimed.move(50, blank = false))
        }
        val vertical = SearchDrag.Pending.advance(3f, 9f, 8f, 18f, blank = true)
        assertEquals(SearchDrag.Vertical, vertical)
        assertEquals(vertical, vertical.advance(40f, 30f, 8f, 18f, blank = true))
    }

    @Test fun globalSearchDoesNotUseHiddenReasoningToolMetadataOrUnrenderedPrompts() {
        val item = Conversation(title = "title", messages = listOf(
            Message(role = Role.Assistant, text = "visible answer", thought = "secret", tool = "meta", prompt = "unshown"),
        ))
        for (query in listOf("secret", "meta", "unshown", "visible missing")) {
            assertEquals(emptyList<HistorySection>(), historySections(listOf(item), query, global = true))
        }
        assertEquals(listOf(item), historySections(listOf(item), "TITLE answer", global = true).single().rows)
    }
}
