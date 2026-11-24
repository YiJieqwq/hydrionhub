package io.hydrion.hub.model

import io.hydrion.hub.provider.LocalModelRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatCompletionsEventsTest {
    private val request = Message(role = Role.Assistant, status = Generation.Sending)
    private val done = "data: [DONE]\n\n"

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/chat-completions/$name.sse")).readText(Charsets.UTF_8)

    private fun frame(choices: String, usage: String = "null") =
        """data: {"object":"chat.completion.chunk","id":"chatcmpl-test","model":"fixture","created":0,"choices":[$choices],"usage":$usage}""" + "\n\n"

    private fun choice(delta: String = "{}", reason: String? = null, index: Int = 0) =
        """{"index":$index,"delta":$delta,"finish_reason":${reason?.let { JSONObject.quote(it) } ?: "null"}}"""

    private suspend fun read(wire: String): List<Message> {
        val seen = mutableListOf<Message>()
        readChatCompletionsEvents(wire.reader().buffered(), request) { seen += it }
        return seen
    }

    private suspend fun failure(wire: String): Pair<Message, String> {
        val seen = mutableListOf<Message>()
        try {
            readChatCompletionsEvents(wire.reader().buffered(), request) { seen += it }
        } catch (error: IOException) {
            assertFalse(seen.any { it.status == Generation.Completed })
            return seen.last() to error.message.orEmpty()
        }
        throw AssertionError("Expected a stream error")
    }

    @Test fun openAiStopWaitsForUsageOnlyChunkBeforeCompletion() = runBlocking {
        val seen = read(fixture("openai-stop"))
        assertTrue(seen.any { it.status == Generation.Generating && it.text == "Schedule " })
        val last = seen.last()
        assertEquals(Generation.Completed, last.status)
        assertEquals("Schedule confirmed.", last.text)
        assertEquals("", last.thought)
        assertEquals(TokenUsage(inputTokens = 41, outputTokens = 13, totalTokens = 54,
            cachedInputTokens = 7, reasoningTokens = 0), last.usage)
        assertEquals(54L, last.totalTokens)
    }

    @Test fun deepSeekReasoningAndCacheHitTokensAreReadFromProviderFields() = runBlocking {
        val seen = read(fixture("deepseek-stop"))
        assertTrue(seen.any { it.thought == "Checked " && it.text.isEmpty() })
        assertTrue(seen.any { it.text == "周" && it.status == Generation.Generating })
        val last = seen.last()
        assertEquals(Generation.Completed, last.status)
        assertEquals("周一。", last.text)
        assertEquals("Checked the calendar.", last.thought)
        assertEquals(TokenUsage(inputTokens = 24, outputTokens = 8, totalTokens = 32,
            cachedInputTokens = 6, reasoningTokens = 3), last.usage)
    }

    @Test fun validatingBeforeTheStreamOrReceivingOnlyMetadataNeverCreatesTokenLatencies() {
        var now = 500L
        val timer = ReplyObservation { now }
        val error = Message(role = Role.Assistant, status = Generation.Error)
        now += 4
        assertNull(timer.record(error, fromStream = false).timing)
        assertEquals(ReplyTiming(null, null, null),
            timer.record(error.copy(status = Generation.Generating, usage = TokenUsage(inputTokens = 0)), true).timing)
        now += 3
        assertEquals(ReplyTiming(null, null, 7), timer.record(error, false).timing)
    }

    @Test fun fragmentedParallelCallsKeepTheirOrderAndDiscardMalformedArguments() = runBlocking {
        val prefix = frame(choice("""{"content":"Plan."}"""))
        val first = frame(choice("""{"tool_calls":[{"index":1,"function":{"name":"wea"}},{"index":0,"function":{"name":"time"}}]}"""))
        val second = frame(choice("""{"tool_calls":[{"index":1,"function":{"name":"ther","arguments":"fixture payload"}}]}""", "tool_calls"))
        val (reply, error) = failure(prefix + first + second + done)
        assertEquals("Plan.", reply.text)
        assertEquals(listOf(ReplyToolCall("time", ToolCallState.Requested, 5, slot = 0),
            ReplyToolCall("weather", ToolCallState.Requested, 5, slot = 1)), reply.toolCalls)
        assertFalse(reply.toString().contains("fixture payload"))
        assertTrue(error.contains("tool_calls"))
    }

    @Test fun refusalIsVisibleAndOtherChoicesCannotWriteOrFinishThePrimaryReply() = runBlocking {
        val other = choice("""{"content":"discarded","reasoning_content":"discarded"}""", "stop", index = 1)
        val first = frame(other + "," + choice("""{"refusal":"Cannot "}"""))
        val last = read(first + frame(choice("""{"refusal":"assist."}""", "stop")) + done).last()
        assertEquals("Cannot assist.", last.text)
        assertEquals("", last.thought)
        assertEquals(Generation.Completed, last.status)
        assertNull(last.usage)
        val (pending, _) = failure(first + done)
        assertEquals("Cannot ", pending.text)
    }

    @Test fun onlyReportedCountsAreStoredAndLatestKnownUsageSurvivesNullChunks() = runBlocking {
        val stop = frame(choice(reason = "stop"))
        val zero = read(frame("", """{"total_tokens":0}""") + stop + done).last()
        assertEquals(TokenUsage(totalTokens = 0), zero.usage)
        assertEquals(0L, zero.totalTokens)
        val partial = read(frame("", """{"prompt_tokens":7}""") + stop + done).last()
        assertEquals(TokenUsage(inputTokens = 7), partial.usage)
        assertNull(partial.totalTokens)
        val sum = read(stop + frame("", """{"prompt_tokens":7,"completion_tokens":3}""") + done).last()
        assertEquals(TokenUsage(inputTokens = 7, outputTokens = 3), sum.usage)
        assertEquals(10L, sum.totalTokens)
        val latest = read(frame("", """{"prompt_tokens":7,"prompt_tokens_details":{"cached_tokens":2}}""") +
            stop + frame("", """{"completion_tokens":4,"total_tokens":11}""") + done).last()
        assertEquals(TokenUsage(inputTokens = 7, outputTokens = 4, totalTokens = 11,
            cachedInputTokens = 2), latest.usage)
        val unknown = read(stop + done).last()
        assertNull(unknown.usage)
        assertNull(unknown.totalTokens)
    }

    @Test fun lengthAndToolCallsKeepPartialTextAndTrailingUsageInFailure() = runBlocking {
        for ((fixtureName, reason, text) in listOf(
            Triple("length", "length", "Unfinished"),
            Triple("tool-calls", "tool_calls", "Looking up Monday.")
        )) {
            val (partial, error) = failure(fixture(fixtureName))
            assertTrue(error, error.contains(reason))
            assertTrue(error, error.any { it in '\u4E00'..'\u9FFF' })
            assertEquals(text, partial.text)
            assertEquals(if (reason == "length") 13L else 20L, partial.usage?.totalTokens)
            assertNull(partial.tool)
        }
    }

    @Test fun unsupportedAndAbortedFinishReasonsKeepTextAndExplainTheActualReason() = runBlocking {
        for (reason in listOf("function_call", "content_filter", "insufficient_system_resource", "aborted", "future_reason")) {
            val (partial, error) = failure(frame(choice("""{"content":"Partial."}""")) +
                frame(choice(reason = reason)) + done)
            assertTrue(error, error.contains(reason))
            assertTrue(error, error.any { it in '\u4E00'..'\u9FFF' })
            assertEquals("Partial.", partial.text)
        }
    }

    @Test fun aToolDeltaCannotTurnIntoACompletedTextResponseWithAStopMarker() = runBlocking {
        val delta = """{"tool_calls":[{"index":0,"type":"function","id":"call_test","function":{"name":"calendar_day","arguments":"{}"}}]}"""
        val (partial, error) = failure(frame(choice(delta)) + frame(choice(reason = "stop")) + done)
        assertTrue(error.contains("tool_calls"))
        assertTrue(partial.text.isEmpty())
        assertNull(partial.tool)
    }

    @Test fun doneAloneEofAfterStopMalformedJsonAndWrongProtocolCannotSucceed() = runBlocking {
        val started = frame(choice("""{"content":"Received."}"""))
        for (tail in listOf(done, "", frame(choice(reason = "stop")), "data: {\n\n",
            """data: {"type":"response.completed","response":{"status":"completed"}}""" + "\n\n" + done)) {
            val (partial, error) = failure(started + tail)
            assertEquals("Received.", partial.text)
            assertTrue(error.isNotBlank())
        }
    }

    @Test fun errorEventInsideHttp200IsRedactedWithoutLosingThePartialAnswer() = runBlocking {
        val echo = "fixture-only-header"
        val wire = frame(choice("""{"content":"Received."}""")) +
            """data: {"error":{"message":"service busy ($echo)","type":"server_error","code":"server_error"}}""" + "\n\n"
        val endpoint = URL("https://fixture.invalid/v1/chat/completions")
        var disconnected = false
        val fake = object : HttpURLConnection(endpoint) {
            override fun connect() = Unit
            override fun disconnect() { disconnected = true }
            override fun usingProxy() = false
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getContentType() = "text/event-stream"
            override fun getInputStream() = wire.byteInputStream()
        }
        val prepared = LocalModelRequest("fixture", mapOf("Authorization" to "Bearer $echo"), JSONObject(),
            emptyMap(), endpoint.toString())
        val seen = mutableListOf<Message>()
        val error = runCatching { ResponsesTransport { fake }.streamChatCompletions(prepared, request).toList(seen) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("service busy"))
        assertFalse(error?.message.orEmpty().contains(echo))
        assertFalse(seen.any { it.status == Generation.Completed })
        assertEquals("Received.", seen.last().text)
        assertTrue(disconnected)
    }
}
