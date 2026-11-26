package io.hydrion.hub.ui.messages

import io.hydrion.hub.model.Message
import io.hydrion.hub.model.ReplyStep
import io.hydrion.hub.model.ReplyToolCall
import io.hydrion.hub.model.isActive

internal sealed interface ReplyBlock {
    data class Body(val text: String) : ReplyBlock
    data class Chain(val steps: List<ReplyStep>, val unplaced: Boolean = false) : ReplyBlock
}

internal fun Message.replyBlocks(calls: Map<Int, ReplyToolCall>): List<ReplyBlock> {
    val order = replySteps.ifEmpty {
        // Legacy replies retain their one aggregate thought and any known tool positions.
        buildList {
            if (thought.isNotBlank()) add(ReplyStep.Thought(0, 0, 0, thought.length))
            for (slot in calls.keys) add(ReplyStep.Tool(slot))
        }
    }
    val placed = sortedMapOf<Int, MutableList<ReplyStep>>()
    val unplaced = mutableListOf<ReplyStep>()
    for (step in order) {
        val offset = when (step) {
            is ReplyStep.Thought -> {
                if ((step.start until step.end).all { thought[it].isWhitespace() }) continue
                step.bodyOffset
            }
            is ReplyStep.Tool -> calls[step.slot]?.bodyOffset
        }
        if (offset == null || offset < 0 || (!status.isActive && offset > text.length)) unplaced += step
        else placed.getOrPut(offset.coerceAtMost(text.length)) { mutableListOf() } += step
    }
    return buildList {
        var chain = mutableListOf<ReplyStep>()
        var cursor = 0
        fun emitBody(end: Int) {
            val body = text.substring(cursor, end)
            cursor = end
            if (body.isBlank()) return
            if (chain.isNotEmpty()) {
                add(ReplyBlock.Chain(chain))
                chain = mutableListOf()
            }
            add(ReplyBlock.Body(body))
        }
        for ((offset, steps) in placed) {
            emitBody(offset)
            chain.addAll(steps)
        }
        emitBody(text.length)
        if (chain.isNotEmpty()) add(ReplyBlock.Chain(chain))
        if (unplaced.isNotEmpty()) add(ReplyBlock.Chain(unplaced, unplaced = true))
    }
}
