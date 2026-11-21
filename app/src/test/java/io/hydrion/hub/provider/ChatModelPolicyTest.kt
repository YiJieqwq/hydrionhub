package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import org.junit.Assert.*
import org.junit.Test

class ChatModelPolicyTest {
    @Test fun sharedChatDefaultDoesNotEraseConversationPinsOrImageSelection() {
        val initial = demoCatalog()
        val choices = initial.entries(Mode.Chat)
        val preferred = choices.last().route
        val pins = initial.initialRoutes() + (Mode.Chat to preferred)
        val shared = initial.copy(perChatModel = false).forConversation(pins)
        assertEquals(choices.first().route, shared.selectedEntry(Mode.Chat)?.route)
        assertEquals(pins[Mode.Image], shared.selectedEntry(Mode.Image)?.route)
        val reopened = decodeCatalog(encodeCatalog(shared).toString())
        assertFalse(reopened.perChatModel)
        assertEquals(preferred, reopened.copy(perChatModel = true).forConversation(pins).selectedEntry(Mode.Chat)?.route)
        val legacy = encodeCatalog(initial).apply { remove("perChatModel"); put("formatVersion", 5) }
        assertTrue(decodeCatalog(legacy.toString()).perChatModel)
    }

    @Test fun reorderingSkipsImageSlotsAndKeepsTheChosenDefaultAndModelIds() {
        val initial = demoCatalog()
        val (chat, image) = initial.providers
        val second = chat.copy(id = "other")
        val stored = initial.copy(providers = listOf(chat, image, second)).normalized()
        val moved = stored.movedChatProvider(second.id, -1)
        assertEquals(listOf(second, image, chat), moved.providers)
        assertEquals(stored.defaults, moved.defaults)
        assertSame(moved, moved.movedChatProvider(image.id, 1))
        assertSame(moved, moved.movedChatProvider(second.id, -1))
    }
}
