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
        assertEquals(listOf("p", "t", "i"), globalDrawerHits(history, " NEEDLE ").map { it.id })
        assertEquals(listOf("t", "i"), globalDrawerHits(history, "different  needle").map { it.id })
        assertEquals(emptyList<HistorySection>(), historySections(history, "absent"))
    }

    @Test fun globalRankingCountsNonOverlappingHitsAndBreaksWeightedTiesByRecency() {
        fun body(id: String, text: String, updatedAt: Long) = Conversation(
            id = id, title = "Chat", updatedAt = updatedAt,
            messages = listOf(Message(role = Role.User, text = text)),
        )
        val fourBodyHits = body("four", "Aa aa AA aA", 1)
        val threeBodyHits = body("three", "aAaAaAa", 3)
        val recentTitle = Conversation(id = "recent", title = "aa", updatedAt = 4)
        val olderTitle = recentTitle.copy(id = "older", updatedAt = 2)
        val twoTitleHits = recentTitle.copy(id = "twice", title = "Aa AA", updatedAt = 0)

        val hits = globalDrawerHits(listOf(threeBodyHits, olderTitle, fourBodyHits, recentTitle, twoTitleHits), "aa")
        assertEquals(listOf("twice", "four", "recent", "three", "older"), hits.map { it.id })
    }

    @Test fun repeatedSearchTermChangesRankAndKeepsTheFirstMessageAnchor() {
        val blueHeavy = Conversation(id = "blue", title = "Chat", updatedAt = 2,
            messages = listOf(Message(role = Role.User, text = "red blue blue blue blue")))
        val first = Message(id = "first", role = Role.User, text = "blue before red")
        val redHeavy = blueHeavy.copy(id = "red", updatedAt = 1, messages = listOf(
            first, Message(id = "later", role = Role.Assistant, text = "red RED"),
        ))
        val missingBlue = blueHeavy.copy(id = "incomplete",
            messages = listOf(Message(role = Role.User, text = "red red red red red")))
        val history = listOf(missingBlue, redHeavy, blueHeavy)

        assertEquals(listOf("blue", "red"), globalDrawerHits(history, "red blue").map { it.id })
        val hits = globalDrawerHits(history, "red blue red")
        assertEquals(listOf("red", "blue"), hits.map { it.id })
        assertEquals(first.id, hits.first().messageId)
        assertEquals("blue", hits.first().needle)
        assertEquals(first.text, hits.first().snippet)
    }

    @Test fun assistantTopicsAreScopedWhileGlobalSearchAndHistoryIncludeAllOwners() {
        val first = Conversation(id = "one", title = "work", assistantId = "a")
        val second = first.copy(id = "two", assistantId = "b")
        val rows = listOf(first, second)
        assertEquals(listOf(first), historySections(rows, "", assistantId = "a").single().rows)
        assertEquals(listOf("one", "two"), globalDrawerHits(rows, "work").map { it.id })
        assertEquals(rows, historySections(rows, "").single().rows)
    }

    @Test fun historyActionTargetMustStillMatchTheQueryAndAssistantById() {
        val target = Conversation(id = "target", title = "Report", assistantId = "a")
        val other = target.copy(id = "other")
        fun targetIn(rows: List<Conversation>, owner: String?) =
            historySections(rows, " report ", assistantId = owner).visibleConversation(target.id)

        assertEquals(target, targetIn(listOf(target, other), "a"))
        assertEquals(null, targetIn(listOf(target.copy(title = "Notes"), other), "a"))
        val moved = target.copy(assistantId = "b")
        assertEquals(null, targetIn(listOf(moved, other), "a"))
        assertEquals(moved, targetIn(listOf(moved, other), null))
        assertEquals(null, targetIn(listOf(other), null))
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
            assertEquals(emptyList<DrawerHit>(), globalDrawerHits(listOf(item), query))
        }
        val hit = globalDrawerHits(listOf(item), "TITLE answer").single()
        assertEquals(item.id, hit.id)
        assertEquals(item.messages.single().id, hit.messageId)
        assertEquals("visible answer", hit.snippet)
    }
}
