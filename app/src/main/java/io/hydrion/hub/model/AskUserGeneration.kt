package io.hydrion.hub.model

import io.hydrion.hub.R
import io.hydrion.hub.provider.ChatProtocol
import io.hydrion.hub.provider.LocalModelRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

internal fun Message.endUnanswered(final: Generation) = copy(status = final, toolCalls = toolCalls.map { call ->
    if (call.name == ASK_USER && (call.state == ToolCallState.Requested || call.state == ToolCallState.Running))
        call.copy(state = if (final == Generation.Error) ToolCallState.Failed else ToolCallState.Incomplete)
    else call
})

/** Each HTTP response finishes before waiting. Only a submitted answer can initiate a follow-up request. */
internal fun questionRounds(protocol: ChatProtocol, initial: LocalModelRequest, request: Message, turn: AskUserTurn,
    budget: Int?, stream: ChatStream) = flow {
    val seen = mutableSetOf<String>()
    val privacy = ToolRecordText(initial.headers)
    var body = initial.body
    var prefix: Message? = null
    var requests = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!turn.inbox.permitted(turn)) throw AskUserFailure(R.string.ask_disabled)
        val wire = AskWireRound()
        val next = LocalModelRequest(initial.model, initial.headers, body, initial.parameters, initial.url, wire)
        val base = prefix
        val firstSlot = (base?.toolCalls?.maxOfOrNull { it.slot ?: 0 } ?: -1) + 1
        var terminal: Message? = null
        requests++
        stream(protocol, next, request).collect { part ->
            val snapshot = if (base == null) part else part.copy(text = base.text + part.text, thought = base.thought + part.thought,
                toolCalls = base.toolCalls + part.toolCalls.mapIndexed { i, call ->
                    call.copy(slot = firstSlot + (call.slot ?: i), bodyOffset = call.bodyOffset?.plus(base.text.length))
                })
            if (snapshot.status == Generation.Completed) terminal = snapshot else emit(snapshot)
        }
        var live = terminal ?: throw AskUserFailure(R.string.ask_invalid)
        if (wire.calls.isEmpty()) {
            if (live.toolCalls.drop(base?.toolCalls?.size ?: 0).any { it.name == ASK_USER }) throw AskUserFailure(R.string.ask_invalid)
            emit(live)
            break
        }
        live = live.copy(status = Generation.Generating)
        emit(live)
        if (budget != null && requests >= budget) throw AskUserFailure(R.string.ask_budget)
        val prompts = wire.calls.map { call ->
            if (!seen.add(call.id)) throw AskUserFailure(R.string.ask_invalid)
            val safe = privacy.arguments(call.arguments) ?: throw AskUserFailure(R.string.ask_invalid)
            val questions = readQuestions(call.arguments)
            if (readQuestions(safe) != questions) throw AskUserFailure(R.string.ask_invalid)
            questions
        }
        val answers = mutableMapOf<String, JSONObject>()
        for ((index, call) in wire.calls.withIndex()) {
            val slot = firstSlot + call.slot
            if (live.toolCalls.singleOrNull { it.slot == slot }?.name != ASK_USER) throw AskUserFailure(R.string.ask_invalid)
            fun update(state: ToolCallState, result: String? = null) = live.copy(toolCalls = live.toolCalls.map {
                if (it.slot == slot) it.copy(state = state, result = result) else it
            })
            live = update(ToolCallState.Running)
            emit(live)
            val answer = turn.inbox.await(AskTicket(turn, call.id, slot), prompts[index])
            if (answer == null) {
                emit(live.endUnanswered(Generation.Stopped))
                return@flow
            }
            if (!turn.inbox.permitted(turn)) throw AskUserFailure(R.string.ask_disabled)
            answers[call.id] = answer
            live = update(ToolCallState.Completed, privacy.display(answer))
            emit(live)
        }
        prefix = live
        body = wire.continueBody(body, answers, protocol == ChatProtocol.ChatCompletionsHttp)
    }
}
