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

    @Test fun pinnedThenRecentDaysAndSearchAcrossTitleTextAndPrompt() {
        val yesterday = today.minusDays(1)
        val pinned = Conversation(id = "p", title = "Needle", updatedAt = timestamp(yesterday), pinned = true)
        val other = Conversation(id = "x", title = "different", updatedAt = timestamp(today))
        val text = other.copy(id = "t", messages = listOf(Message(role = Role.User, text = "needle")), updatedAt = other.updatedAt + 1)
        val prompt = other.copy(id = "i", messages = listOf(Message(role = Role.Assistant, prompt = "a needle")), updatedAt = timestamp(yesterday))
        val history = listOf(other, prompt, pinned, text)
        val all = historySections(history, "")
        assertEquals(listOf(null, today, yesterday), all.map { it.date })
        assertEquals(listOf("p", "t", "x", "i"), all.flatMap { it.rows }.map { it.id })
        assertEquals(listOf("p", "t", "i"), historySections(history, " NEEDLE ").flatMap { it.rows }.map { it.id })
        assertEquals(emptyList<HistorySection>(), historySections(history, "absent"))
    }
}
