package io.hydrion.hub.ui.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.model.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun AskUserCard(call: ReplyToolCall, form: PendingAsk?, inbox: AskUserInbox) {
    if (form == null) {
        if (call.state == ToolCallState.Completed && call.result != null) AnswerSummary(call)
        return
    }
    val ticket = form.ticket
    Column(Modifier.fillMaxWidth().padding(stepBodyPadding).testTag("ask-form"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (q in form.questions) key(q.id) {
            val draft = form.drafts[q.id] ?: AnswerDraft()
            Text(q.text, style = MaterialTheme.typography.titleSmall)
            for (option in q.options) {
                AnswerChoice(option, option in draft.choices, q.multiple) { inbox.choose(ticket, q.id, option) }
            }
            if (q.options.isNotEmpty()) {
                AnswerChoice(stringResource(R.string.ask_other), draft.other != null, q.multiple) { inbox.other(ticket, q.id) }
            }
            if (!draft.skipped && (q.options.isEmpty() || draft.other != null)) {
                OutlinedTextField(draft.other.orEmpty(), { inbox.type(ticket, q.id, it) },
                    Modifier.fillMaxWidth().testTag("ask-text:" + q.id), label = { Text(stringResource(R.string.ask_input)) }, maxLines = 4)
            }
            AnswerChoice(stringResource(R.string.ask_skip), draft.skipped, false) { inbox.skip(ticket, q.id) }
        }
        Text(stringResource(R.string.ask_panel_help), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { inbox.submit(ticket) }, enabled = form.ready, modifier = Modifier.testTag("ask-submit")) {
                Text(stringResource(R.string.ask_submit))
            }
            TextButton(onClick = { inbox.cancel(ticket) }, modifier = Modifier.testTag("ask-cancel")) { Text(stringResource(R.string.ask_cancel)) }
        }
    }
}

@Composable
private fun AnswerChoice(label: String, selected: Boolean, multiple: Boolean, select: () -> Unit) {
    val click = if (multiple) Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { select() })
        else Modifier.selectable(selected, role = Role.RadioButton, onClick = select)
    Row(Modifier.fillMaxWidth().then(click).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (multiple) Checkbox(selected, onCheckedChange = null) else RadioButton(selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AnswerSummary(call: ReplyToolCall) {
    val questions = remember(call.arguments) { runCatching { readQuestions(call.arguments.orEmpty()) }.getOrDefault(emptyList()) }
    val answers = remember(call.result) { runCatching { JSONObject(call.result.orEmpty()).getJSONObject("answers") }.getOrNull() }
    Column(Modifier.fillMaxWidth().padding(stepBodyPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (q in questions) {
            val answer = answers?.optJSONObject(q.id) ?: continue
            val value = when (val raw = answer.opt("value")) {
                is JSONArray -> List(raw.length()) { raw.optString(it) }.joinToString(", ")
                is String -> raw
                else -> continue
            }
            Text(q.text, style = MaterialTheme.typography.labelLarge)
            Text(if (answer.optBoolean("skipped")) stringResource(R.string.ask_skipped) else value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
