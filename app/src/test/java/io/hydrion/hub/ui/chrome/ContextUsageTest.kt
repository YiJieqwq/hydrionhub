package io.hydrion.hub.ui.chrome

import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.Role
import io.hydrion.hub.model.TokenUsage
import io.hydrion.hub.provider.CONTEXT_WINDOW_PARAMETER
import io.hydrion.hub.provider.requestParameters
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

    @Test fun compactCountsRetainTheUnderlyingReadingAndUnknownState() {
        assertEquals("0", ContextUsage(0, 1000).label)
        assertEquals("999", ContextUsage(999, 1000).label)
        assertEquals("1.0k", ContextUsage(1000, 8000).label)
        val reading = ContextUsage(123400, 150000)
        assertEquals("123.4k", reading.label)
        assertTrue(reading.high)
        assertEquals(123400L, reading.tokens)
        assertNull(ContextUsage(null, 150000).label)
        assertEquals("0.0k", ContextUsage(0, 1000, estimated = true).label)
    }

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
        assertEquals("800", changed.label)
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
        assertEquals("120", reading.label)
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
        assertEquals("71", contextUsage(history, flash to deepSeekProvider).label)
    }

    @Test fun switchingToUnmeasuredResponsesProviderHasUnknownUsage() {
        val openAI = deepSeekProvider.copy(id = "my-openai", baseUrl = "https://api.openai.com/v1")
        val history = Conversation(messages = listOf(reply(71)))
        val reading = contextUsage(history, flash to openAI)
        assertNull(reading.tokens)
        assertNull(reading.usage)
        assertFalse(reading.estimated)
        val edited = flash.copy(profile = flash.profile.copy(externalId = "replacement-id"))
        val oldTurn = reply(71).copy(parameters = mapOf("model" to flash.profile.externalId))
        assertNull(contextUsage(Conversation(messages = listOf(oldTurn)), edited to deepSeekProvider).tokens)
    }

    @Test fun reportedTotalIsOneDisjointSegmentAndOverageIsOnlyClampedForDrawing() {
        val counts = TokenUsage(inputTokens = 800, outputTokens = 450, cachedInputTokens = 270, reasoningTokens = 300)
        val model = flash.copy(profile = flash.profile.copy(contextWindowTokens = 1000))
        fun reading(usage: TokenUsage) = contextUsage(Conversation(messages = listOf(reply(null).copy(usage = usage))), model to deepSeekProvider)
        val over = reading(counts)
        assertEquals(1250L, over.count(ContextPart.Unattributed))
        assertEquals(0L, over.count(ContextPart.Free))
        assertEquals(1.25, over.share(over.tokens)!!, 0.0)
        assertEquals(1f, over.fraction, 0f)
        assertTrue(ContextPart.entries.filterNot { it == ContextPart.Free || it == ContextPart.Unattributed }.all { over.count(it) == null })
        val partial = reading(TokenUsage(inputTokens = 800))
        assertTrue(ContextPart.entries.all { partial.count(it) == null })
        val zero = reading(TokenUsage(totalTokens = 0))
        assertEquals(0L, zero.count(ContextPart.Unattributed))
        assertEquals(1000L, zero.count(ContextPart.Free))
    }

    @Test fun unsupportedModelAndAmbiguousLegacySuggestionHaveNoUpperLimit() {
        val model = flash.copy(id = "opaque", profile = flash.profile.copy(externalId = "opaque"))
        val entry = model to deepSeekProvider
        val message = reply(30).copy(remoteModel = remote.copy(modelId = model.id),
            parameters = mapOf(CONTEXT_WINDOW_PARAMETER to "128000"))
        val single = Conversation(messages = listOf(message))
        for (selected in listOf(entry, null)) {
            val unknown = contextUsage(single, selected)
            assertEquals(30L, unknown.tokens)
            assertNull(unknown.window)
            assertNull(unknown.share(unknown.tokens))
            assertNull(unknown.count(ContextPart.Free))
        }
        assertFalse(CONTEXT_WINDOW_PARAMETER in model.requestParameters(emptyMap()))
        fun restored(parameters: Map<String, String>) = contextUsage(single.copy(messages = listOf(message.copy(parameters = parameters))), null)
        assertEquals(64_000, restored(mapOf(CONTEXT_WINDOW_PARAMETER to "64000")).window)
        val configured = model.copy(profile = model.profile.copy(contextWindowTokens = 128000))
        assertEquals(128_000, restored(configured.requestParameters(emptyMap())).window)
    }

    @Test fun localEstimateUsesOnlyVisibleContextAfterAnchorAndAssistantLimit() {
        val local = demoCatalog().providers.single { it.id == "demo-chat" }
        val model = local.models.first()
        val messages = (1..6).map { Message(id = "m" + it, role = Role.User, text = "Message " + it) }
        val history = Conversation(messages = messages, contextStart = "m3")
        val reading = contextUsage(history, model to local, messageLimit = 2)
        assertTrue(reading.estimated)
        assertEquals(520L, reading.count(ContextPart.Messages))
        assertNull(reading.count(ContextPart.Unattributed))
        assertNull(reading.count(ContextPart.Free))
        assertEquals(1040L, contextUsage(history, model to local).tokens)
    }
}
