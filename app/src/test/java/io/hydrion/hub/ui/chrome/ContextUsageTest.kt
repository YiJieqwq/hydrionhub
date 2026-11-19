package io.hydrion.hub.ui.chrome

import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.Role
import io.hydrion.hub.model.TokenUsage
import io.hydrion.hub.provider.CONTEXT_WINDOW_PARAMETER
import io.hydrion.hub.provider.deepSeekProvider
import io.hydrion.hub.provider.demoCatalog
import org.junit.Assert.*
import org.junit.Test

class ContextUsageTest {
    private val flash = deepSeekProvider.models.single { it.id == "deepseek-flash" }
    private val pro = deepSeekProvider.models.single { it.id == "deepseek-v4-pro" }
    private val remote = RemoteModel(deepSeekProvider.id, flash.id)
    private fun reply(total: Long?, at: Long = 1) = Message(role = Role.Assistant, model = "同名模型",
        remoteModel = remote, totalTokens = total, createdAt = at)

    @Test fun usesInclusiveThresholdWithoutRoundingNearlyFullWindowsToRed() {
        assertFalse(ContextUsage(799_999, 1_000_000).high)
        assertTrue(ContextUsage(800_000, 1_000_000).high)
        assertFalse(ContextUsage(4, 6).high)
        assertTrue(ContextUsage(5, 6).high)
        assertEquals(1f, ContextUsage(Long.MAX_VALUE, 1000).fraction, 0f)
        assertFalse(ContextUsage(null, 1000).high)
    }

    @Test fun recalculatesLastReadingForTheSelectedWindowAndRetainsItWhileStreaming() {
        val breakdown = TokenUsage(inputTokens = 700, outputTokens = 100, totalTokens = 800,
            cachedInputTokens = 90, reasoningTokens = 10)
        val recorded = reply(null, 2).copy(usage = breakdown,
            parameters = mapOf(CONTEXT_WINDOW_PARAMETER to "1200"))
        val newReply = reply(null, 3).copy(status = Generation.Generating)
        val history = Conversation(messages = listOf(reply(600), recorded, newReply))
        val small = flash.copy(profile = flash.profile.copy(contextWindowTokens = 1000))
        val large = small.copy(profile = small.profile.copy(contextWindowTokens = 2000))
        assertTrue(contextUsage(history, small to deepSeekProvider).high)
        val changed = contextUsage(history, large to deepSeekProvider)
        assertEquals(breakdown, changed.usage)
        assertEquals(2000, changed.window)
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
        val reading = contextUsage(Conversation(messages = listOf(retried, laterInTranscript)), flash to deepSeekProvider)
        assertEquals("120 tokens", reading.label)
        assertEquals(TokenUsage(totalTokens = 120), reading.usage)
    }

    @Test fun selectsTheReportByConversationProviderAndModelDespiteIdenticalDisplayNames() {
        val flashReading = TokenUsage(inputTokens = 68, outputTokens = 3, totalTokens = 71)
        val proReading = TokenUsage(totalTokens = 202)
        val flashReply = reply(null).copy(usage = flashReading)
        val proReply = reply(null, 2).copy(remoteModel = RemoteModel(remote.providerId, pro.id), usage = proReading)
        val otherProvider = reply(777, 3).copy(remoteModel = RemoteModel("other-provider", flash.id))
        val userMessage = reply(888, 4).copy(role = Role.User)
        val transcript = Conversation(messages = listOf(flashReply, proReply, otherProvider, userMessage))
        val selectedFlash = flash.copy(name = "同名模型") to deepSeekProvider
        val selectedPro = pro.copy(name = "同名模型") to deepSeekProvider
        assertEquals(flashReading, contextUsage(transcript, selectedFlash).usage)
        assertEquals(proReading, contextUsage(transcript, selectedPro).usage)
        assertEquals(flashReading, contextUsage(transcript, selectedFlash).usage)
        val separateChat = Conversation(messages = listOf(proReply))
        val missing = contextUsage(separateChat, selectedFlash)
        assertNull(missing.tokens)
        assertNull(missing.usage)
        assertFalse(missing.estimated)
    }

    @Test fun terminalReplyWithNoReportOrPartialReportSupersedesThePriorTotal() {
        val selected = flash to deepSeekProvider
        val old = reply(90)
        val partial = TokenUsage(inputTokens = 2)
        for (status in listOf(Generation.Completed, Generation.Stopped, Generation.Error)) {
            val latest = reply(null, 2).copy(status = status)
            val history = Conversation(messages = listOf(old, latest))
            val missing = contextUsage(history, selected)
            assertNull(missing.tokens)
            assertNull(missing.usage)
            val incomplete = contextUsage(history.copy(messages = listOf(old, latest.copy(usage = partial))), selected)
            assertNull(incomplete.tokens)
            assertEquals(partial, incomplete.usage)
            val zero = contextUsage(history.copy(messages = listOf(old, latest.copy(usage = TokenUsage(totalTokens = 0)))), selected)
            assertEquals(0L, zero.tokens)
            assertEquals(TokenUsage(totalTokens = 0), zero.usage)
        }
    }

    @Test fun derivesAnEffectiveTotalOnlyFromCompleteValidComponents() {
        val reported = TokenUsage(inputTokens = 60, outputTokens = 11, totalTokens = 73)
        val separated = reported.copy(totalTokens = null)
        for ((usage, expected) in listOf(
            reported to 73L,
            separated to 71L,
            TokenUsage(inputTokens = 0, outputTokens = 0) to 0L,
            TokenUsage(inputTokens = 60) to null,
            TokenUsage(outputTokens = 11) to null,
            TokenUsage(inputTokens = Long.MAX_VALUE, outputTokens = 1) to null,
            TokenUsage(inputTokens = 10, outputTokens = -1) to null
        )) {
            assertEquals(expected, usage.contextTokens)
            val history = Conversation(messages = listOf(reply(null).copy(usage = usage)))
            val reading = contextUsage(history, flash to deepSeekProvider)
            assertEquals(expected, reading.tokens)
            assertEquals(usage, reading.usage)
        }
        assertNull(separated.totalTokens)
    }

    @Test fun switchingBackToLocalShowsOnlyItsEstimate() {
        val localProvider = demoCatalog().providers.single { it.id == "demo-chat" }
        val local = localProvider.models.first() to localProvider
        val empty = contextUsage(Conversation(), local)
        assertTrue(empty.estimated)
        assertEquals(0L, empty.tokens)
        assertNull(empty.usage)
        val history = Conversation(messages = listOf(reply(71)))
        val estimate = contextUsage(history, local)
        assertTrue(estimate.estimated)
        assertNull(estimate.usage)
        assertEquals("71 tokens", contextUsage(history, flash to deepSeekProvider).label)
    }
}
