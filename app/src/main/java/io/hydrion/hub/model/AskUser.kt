package io.hydrion.hub.model

import io.hydrion.hub.R
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal const val ASK_USER = "ask_user_input_v0"

internal class AskUserFailure(val label: Int) : IOException("The question could not be completed.")

internal data class UserQuestion(val id: String, val text: String, val options: List<String>, val multiple: Boolean)
internal data class AnswerDraft(val choices: Set<String> = emptySet(), val other: String? = null, val skipped: Boolean = false)

internal fun readQuestions(arguments: String): List<UserQuestion> {
    fun invalid(): Nothing = throw AskUserFailure(R.string.ask_invalid)
    fun text(value: Any?, limit: Int) = (value as? String)?.takeIf { it.isNotBlank() && it.length <= limit &&
        it.none { ch -> ch.isISOControl() && ch !in "\t\n\r" } } ?: invalid()
    val items = try { JSONObject(arguments).optJSONArray("questions") } catch (_: Exception) { null } ?: invalid()
    if (items.length() !in 1..4) invalid()
    return List(items.length()) { index ->
        val item = items.optJSONObject(index) ?: invalid()
        val options = if (item.has("options")) {
            val list = item.optJSONArray("options") ?: invalid()
            if (list.length() > 24) invalid()
            List(list.length()) { text(list.opt(it), 320) }.also { if (it.distinct().size != it.size) invalid() }
        } else emptyList()
        val multiple = when (item.opt("type")) { null, "single" -> false; "multi" -> true; else -> invalid() }
        UserQuestion(text(item.opt("id"), 80), text(item.opt("question"), 1200), options, multiple)
    }.also { if (it.distinctBy(UserQuestion::id).size != it.size) invalid() }
}

internal class AskUserTurn(val chatId: String, val replyId: String, val inbox: AskUserInbox)
internal data class AskTicket(val turn: AskUserTurn, val callId: String, val slot: Int)
internal data class PendingAsk(val ticket: AskTicket, val questions: List<UserQuestion>, val drafts: Map<String, AnswerDraft> = emptyMap()) {
    val ready get() = questions.all { q -> drafts[q.id]?.let { it.skipped || (it.choices.isNotEmpty() || !it.other.isNullOrBlank()) &&
        (it.other == null || it.other.isNotBlank()) } == true }

    fun result(): JSONObject {
        check(ready)
        val answers = JSONObject()
        for (q in questions) {
            val draft = drafts.getValue(q.id)
            val values = q.options.filter { it in draft.choices } + listOfNotNull(draft.other?.trim()?.takeIf(String::isNotEmpty))
            answers.put(q.id, JSONObject().put("type", if (q.multiple) "multi" else "single")
                .put("skipped", draft.skipped).put("custom", draft.other != null && !draft.skipped)
                .put("value", when { draft.skipped -> ""; q.multiple -> JSONArray(values); else -> values.single() }))
        }
        return JSONObject().put("answers", answers)
    }

    override fun toString() = "PendingAsk(questions=" + questions.size + ")"
}

/** Main-scope rendezvous. The live generation owns the ticket and its unsent answers, across chat switches. */
internal class AskUserInbox(private val visibleChat: () -> String, val permitted: (AskUserTurn) -> Boolean) {
    private val open = MutableStateFlow(emptyMap<AskTicket, PendingAsk>())
    val pending = open.asStateFlow()
    private val waiters = mutableMapOf<AskTicket, CompletableDeferred<JSONObject?>>()

    suspend fun await(ticket: AskTicket, questions: List<UserQuestion>): JSONObject? {
        if (!permitted(ticket.turn)) throw AskUserFailure(R.string.ask_disabled)
        val waiter = CompletableDeferred<JSONObject?>()
        check(ticket !in waiters)
        waiters[ticket] = waiter
        open.value += ticket to PendingAsk(ticket, questions)
        try { return waiter.await() }
        finally { remove(ticket) }
    }

    private fun remove(ticket: AskTicket): CompletableDeferred<JSONObject?>? {
        val waiter = waiters.remove(ticket)
        open.value -= ticket
        return waiter
    }

    fun revokeUnavailable() {
        for (ticket in waiters.keys.toList()) if (!permitted(ticket.turn))
            remove(ticket)?.completeExceptionally(AskUserFailure(R.string.ask_disabled))
    }

    private fun editable(ticket: AskTicket) = open.value[ticket]?.takeIf {
        visibleChat() == ticket.turn.chatId && permitted(ticket.turn)
    }

    private fun update(ticket: AskTicket, id: String, edit: (UserQuestion, AnswerDraft) -> AnswerDraft) {
        val request = editable(ticket) ?: return
        val question = request.questions.find { it.id == id } ?: return
        val draft = edit(question, request.drafts[id] ?: AnswerDraft())
        open.value += ticket to request.copy(drafts = request.drafts + (id to draft))
    }

    fun choose(ticket: AskTicket, id: String, choice: String) = update(ticket, id) { q, draft ->
        when {
            choice !in q.options -> draft
            !q.multiple -> AnswerDraft(setOf(choice))
            else -> draft.copy(choices = if (choice in draft.choices) draft.choices - choice else draft.choices + choice, skipped = false)
        }
    }
    fun other(ticket: AskTicket, id: String) = update(ticket, id) { q, draft ->
        AnswerDraft(if (q.multiple) draft.choices else emptySet(), if (draft.other == null) "" else null)
    }
    fun type(ticket: AskTicket, id: String, text: String) = update(ticket, id) { q, draft ->
        if (text.length > 2048 || (q.options.isNotEmpty() && draft.other == null)) draft
        else draft.copy(other = text, skipped = false)
    }
    fun skip(ticket: AskTicket, id: String) = update(ticket, id) { _, draft -> AnswerDraft(skipped = !draft.skipped) }

    fun submit(ticket: AskTicket) {
        val request = editable(ticket)?.takeIf { it.ready } ?: return
        remove(ticket)?.complete(request.result())
    }
    fun cancel(ticket: AskTicket) {
        if (editable(ticket) != null) remove(ticket)?.complete(null)
    }
}
