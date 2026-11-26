package io.hydrion.hub.model

/** Client observations measured from starting this generation, on the same monotonic clock. */
data class ReplyTiming(val firstTokenMs: Long?, val firstBodyMs: Long?, val elapsedMs: Long?) {
    fun tokensPerSecond(output: Long?): Double? =
        if (output != null && output >= 0 && elapsedMs != null && elapsedMs > 0) output * 1000.0 / elapsedMs else null
}

enum class ToolCallState { Requested, Running, Completed, Failed, Incomplete }
/** UTF-16 offset into this revision's body, when the protocol supplied an ordering. */
data class ReplyToolCall(
    val name: String, val state: ToolCallState, val bodyOffset: Int? = null,
    val arguments: String? = null, val result: String? = null, val slot: Int? = null
) {
    // A message may be included in diagnostics. Tool contents are only for the local transcript.
    override fun toString() = "ReplyToolCall(slot=$slot, state=$state)"
}
data class ReplyTranslation(val language: String, val text: String)

/** Ordered references into this revision. Text offsets are UTF-16, end is exclusive.
 * Tool slots point at the existing record so later status/results never create another step. */
sealed interface ReplyStep {
    val slot: Int
    data class Thought(override val slot: Int, val bodyOffset: Int, val start: Int, val end: Int) : ReplyStep
    data class Tool(override val slot: Int) : ReplyStep
}

internal class ReplyObservation(private val clockMs: () -> Long) {
    private val start = clockMs()
    private var receivedSnapshot = false
    private var firstToken: Long? = null
    private var firstBody: Long? = null

    fun record(reply: Message, fromStream: Boolean): Message {
        if (fromStream) receivedSnapshot = true
        if (!receivedSnapshot) return reply
        val elapsed = (clockMs() - start).coerceAtLeast(0)
        if (firstToken == null && (reply.text.isNotEmpty() || reply.thought.isNotEmpty() || reply.toolCalls.isNotEmpty()))
            firstToken = elapsed
        if (firstBody == null && reply.text.isNotBlank()) firstBody = elapsed
        return reply.copy(timing = ReplyTiming(firstToken, firstBody, elapsed.takeUnless { reply.status.isActive }))
    }
}
