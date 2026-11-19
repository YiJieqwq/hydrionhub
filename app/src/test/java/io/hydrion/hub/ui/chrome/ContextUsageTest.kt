package io.hydrion.hub.ui.chrome

import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.Role
import io.hydrion.hub.provider.CONTEXT_WINDOW_PARAMETER
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.ProviderModel
import org.junit.Assert.*
import org.junit.Test

class ContextUsageTest {
    private val remote = RemoteModel("deepseek", "deepseek-flash")
    private fun reply(total: Long?) = Message(role = Role.Assistant, remoteModel = remote, totalTokens = total)

    @Test fun usesInclusiveThresholdWithoutRoundingNearlyFullWindowsToRed() {
        assertFalse(ContextUsage(799_999, 1_000_000).high)
        assertTrue(ContextUsage(800_000, 1_000_000).high)
        assertFalse(ContextUsage(4, 6).high)
        assertTrue(ContextUsage(5, 6).high)
        assertEquals(1f, ContextUsage(Long.MAX_VALUE, 1000).fraction, 0f)
        assertFalse(ContextUsage(null, 1000).high)
    }

    @Test fun recalculatesLastReadingForTheSelectedWindowAndRetainsItWhileStreaming() {
        val newReply = reply(null).copy(status = Generation.Generating)
        val history = Conversation(messages = listOf(reply(600), reply(800), newReply))
        val small = ProviderModel("example", "Example", profile = ModelProfile(contextWindowTokens = 1000))
        val large = small.copy(profile = small.profile.copy(contextWindowTokens = 2000))
        assertTrue(contextUsage(history, small).high)
        val changed = contextUsage(history, large)
        assertEquals("800 tokens", changed.label)
        assertEquals(0.4f, changed.fraction, 0.00001f)
        assertFalse(changed.high)
    }

    @Test fun firstRemoteReplyUsesActualUsageAndFallsBackToPersistedWindow() {
        val missing = contextUsage(Conversation(messages = listOf(reply(null))), null)
        assertNull(missing.tokens)
        assertEquals(1_000_000, missing.window)
        val stored = reply(90).copy(parameters = mapOf(CONTEXT_WINDOW_PARAMETER to "100"))
        assertTrue(contextUsage(Conversation(messages = listOf(stored)), null).high)
    }

    @Test fun retryingAnOlderMessageUpdatesTheLatestReading() {
        val retried = reply(120).copy(createdAt = 3)
        val laterInTranscript = reply(90).copy(createdAt = 2)
        assertEquals("120 tokens", contextUsage(Conversation(messages = listOf(retried, laterInTranscript)), null).label)
    }
}
