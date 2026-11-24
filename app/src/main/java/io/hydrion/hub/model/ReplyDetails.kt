package io.hydrion.hub.model

/** Client observations measured from starting this generation, on the same monotonic clock. */
data class ReplyTiming(val firstTokenMs: Long?, val firstBodyMs: Long?, val elapsedMs: Long?) {
    fun tokensPerSecond(output: Long?): Double? =
        if (output != null && output >= 0 && elapsedMs != null && elapsedMs > 0) output * 1000.0 / elapsedMs else null
}

enum class ToolCallState { Requested, Running, Completed, Failed, Incomplete }
/** UTF-16 offset into this revision's body, when the protocol supplied an ordering. */
data class ReplyToolCall(val name: String, val state: ToolCallState, val bodyOffset: Int? = null)
data class ReplyTranslation(val language: String, val text: String)

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
