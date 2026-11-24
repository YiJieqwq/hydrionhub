package io.hydrion.hub.ui.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import io.hydrion.hub.R
import io.hydrion.hub.model.*
import io.hydrion.hub.provider.chatProtocol
import io.hydrion.hub.provider.route
import java.text.SimpleDateFormat
import java.util.Date

@Composable
internal fun TurnByline(name: String, time: Long, modifier: Modifier = Modifier, align: Alignment.Horizontal = Alignment.Start) {
    val locale = LocalConfiguration.current.locales[0]
    val stamp = remember(time, locale) { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).format(Date(time)) }
    Column(modifier, horizontalAlignment = align) {
        Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
    }
}

@Composable
internal fun ReplyUsageButton(msg: Message) {
    var expanded by remember { mutableStateOf(false) }
    val usage = msg.reportedUsage
    val timing = msg.timing
    val total = usage?.contextTokens
    val unknown = stringResource(R.string.turn_unrecorded)
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.height(40.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(if (total != null) stringResource(R.string.turn_tokens, total) else stringResource(R.string.turn_usage),
                style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.widthIn(min = 224.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.turn_usage), style = MaterialTheme.typography.titleSmall)
                if (total != null) UsageRow(R.string.turn_total, total.toString())
                UsageRow(R.string.turn_input, usage?.inputTokens?.toString() ?: unknown)
                UsageRow(R.string.turn_cached, usage?.cachedInputTokens?.toString() ?: unknown)
                UsageRow(R.string.turn_output, usage?.outputTokens?.toString() ?: unknown)
                UsageRow(R.string.turn_reasoning, usage?.reasoningTokens?.toString() ?: unknown)
                HorizontalDivider()
                val speed = timing?.tokensPerSecond(usage?.outputTokens)
                UsageRow(R.string.turn_tps, speed?.let { stringResource(R.string.turn_tps_value, it) } ?: unknown)
                UsageRow(R.string.turn_ttft, timing?.firstTokenMs?.let { stringResource(R.string.turn_millis, it) } ?: unknown)
                UsageRow(R.string.turn_first_body, timing?.firstBodyMs?.let { stringResource(R.string.turn_millis, it) } ?: unknown)
                UsageRow(R.string.turn_elapsed, timing?.elapsedMs?.let { stringResource(R.string.turn_millis, it) } ?: unknown)
            }
        }
    }
}

@Composable
private fun UsageRow(label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun ReplyTimeline(msg: Message, query: String) {
    if (msg.toolCalls.isEmpty()) {
        if (msg.text.isNotBlank()) AssistantBody(msg.text, query, Mode.Chat)
        return
    }
    val groups = remember(msg.toolCalls) { msg.toolCalls.groupBy { it.bodyOffset ?: 0 }.toSortedMap() }
    var cursor = 0
    for ((position, calls) in groups) {
        val offset = position.coerceIn(cursor, msg.text.length)
        val segment = msg.text.substring(cursor, offset)
        if (segment.isNotBlank()) AssistantBody(segment, query, Mode.Chat)
        key(position) { ReplyToolChain(calls, query) }
        cursor = offset
    }
    val last = msg.text.substring(cursor)
    if (last.isNotBlank()) AssistantBody(last, query, Mode.Chat)
}

@Composable
private fun ReplyToolChain(calls: List<ReplyToolCall>, query: String) {
    val hit = query.isNotBlank() && calls.any { it.name.contains(query, true) }
    var expanded by rememberSaveable(hit) { mutableStateOf(true) }
    MessageCard(LocalMessageStyle.current.renderPalette.assistant, Modifier.fillMaxWidth(), RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Build, null, Modifier.size(16.dp))
                Text(stringResource(R.string.turn_tool_chain, calls.size), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
            if (expanded) calls.forEach { call ->
                val label = when (call.state) {
                    ToolCallState.Requested -> R.string.turn_tool_requested
                    ToolCallState.Running -> R.string.turn_tool_running
                    ToolCallState.Completed -> R.string.turn_tool_done
                    ToolCallState.Failed -> R.string.turn_tool_failed
                    ToolCallState.Incomplete -> R.string.turn_tool_incomplete
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(highlighted(call.name.ifBlank { stringResource(R.string.turn_unnamed_tool) }, query),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun ReplyTranslationBody(chatId: String, msg: Message, query: String, translations: ReplyTranslations) {
    val runs = translations.progress.collectAsState()
    val run = remember(chatId, msg, runs) {
        derivedStateOf { runs.value[msg.id]?.takeIf { it.chatId == chatId && it.source === msg } }
    }.value
    val saved = msg.translation
    if (run == null && saved == null) return
    val language = run?.language ?: saved?.language.orEmpty()
    val value = run?.text ?: saved?.text.orEmpty()
    var expanded by rememberSaveable(msg.id, language, query) { mutableStateOf(true) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = !expanded }) {
                Icon(Icons.Outlined.Translate, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.turn_translation_in, language))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
            }
            if (run != null) TextButton(onClick = { translations.cancel(msg.id) }) {
                Text(stringResource(if (run.failed) android.R.string.ok else R.string.settings_cancel))
            }
        }
        if (expanded) {
            if (run?.failed == true) Text(stringResource(R.string.turn_translation_failed), color = MaterialTheme.colorScheme.error)
            else if (run != null && value.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (value.isNotBlank()) AssistantBody(value, query, Mode.Chat)
        }
    }
}

@Composable
internal fun TranslationDialog(chatId: String, msg: Message, vm: HubViewModel, close: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    var language by rememberSaveable(chatId, msg.id) { mutableStateOf(msg.translation?.language ?: locale.displayLanguage) }
    val catalog by vm.providers.catalog.collectAsState()
    val entry = catalog.selectedEntry(Mode.Chat)?.takeIf { (_, provider) -> provider.chatProtocol() != null }
    val route = entry?.route
    val keyIssue = entry?.let { (model, provider) -> provider.chatKeyIssue(model, vm.providers.sessionKey(provider)) }
    val runs by vm.translations.progress.collectAsState()
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.turn_translate)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(language, { if (it.length <= 80 && it.none(Char::isISOControl)) language = it }, singleLine = true,
                label = { Text(stringResource(R.string.turn_target_language)) })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (choice in listOf("中文", "English", "日本語"))
                    AssistChip(onClick = { language = choice }, label = { Text(choice) })
            }
            if (route == null) Text(stringResource(R.string.turn_translation_model_needed), color = MaterialTheme.colorScheme.error)
            else Text(stringResource(R.string.turn_translation_model, entry.second.name, entry.first.name),
                style = MaterialTheme.typography.bodySmall)
            if (keyIssue != null) Text(keyIssue, color = MaterialTheme.colorScheme.error)
            if (msg.translation != null || runs[msg.id] != null) TextButton(onClick = { vm.translations.clear(chatId, msg); close() }) {
                Text(stringResource(R.string.turn_translation_clear))
            }
        } }, confirmButton = { TextButton(enabled = route != null && keyIssue == null && language.isNotBlank(), onClick = {
            if (route != null && vm.providers.catalog.value.selectedEntry(Mode.Chat)?.route == route)
                vm.translations.start(chatId, msg, language, route)
            close()
        }) { Text(stringResource(R.string.turn_translate)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.settings_cancel)) } })
}
