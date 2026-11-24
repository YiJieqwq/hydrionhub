package io.hydrion.hub.ui.messages

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role as UiRole
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date

@Composable
internal fun TurnByline(name: String, time: Long, modifier: Modifier = Modifier, align: Alignment.Horizontal = Alignment.Start) {
    val locale = LocalConfiguration.current.locales[0]
    val stamp = remember(time, locale) { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).format(Date(time)) }
    Column(modifier, horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(if (align == Alignment.End) 2.dp else 0.dp)) {
        Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Normal, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), lineHeight = 16.sp, letterSpacing = 0.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stamp, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
    }
}

@Composable
internal fun ReplyUsageButton(msg: Message, modifier: Modifier = Modifier) {
    val usage = msg.reportedUsage
    val timing = msg.timing
    val total = usage?.contextTokens ?: msg.totalTokens?.takeIf { it >= 0 }
    val hasDetails = usage?.inputTokens != null || usage?.cachedInputTokens != null ||
        usage?.outputTokens != null || usage?.reasoningTokens != null ||
        timing?.firstTokenMs != null || timing?.firstBodyMs != null || timing?.elapsedMs != null
    if (total == null && !hasDetails) return

    var open by remember(msg.id, hasDetails) { mutableStateOf(false) }
    val action = if (hasDetails) Modifier.clickable(role = UiRole.Button) { open = true } else Modifier
    val colors = MaterialTheme.colorScheme
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Box(modifier) {
        Box(action.heightIn(min = 28.dp), contentAlignment = Alignment.Center) {
            val label = if (total != null) stringResource(R.string.turn_tokens, formatBriefTokenCount(total))
                else stringResource(R.string.turn_usage)
            Text(label,
                fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp, color = colors.onSurface.copy(alpha = .5f))
        }
        if (open) Popup(remember(gap) { UsagePosition(gap) }, onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true)) {
            Surface(modifier = Modifier.testTag("turn-usage-panel"), shape = RoundedCornerShape(10.dp), color = colors.surface, shadowElevation = 4.dp) {
                Column(Modifier.widthIn(max = 280.dp).width(IntrinsicSize.Max)
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp.dp - 16.dp).coerceAtLeast(1.dp))
                    .verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // The footer can sum input and output even when no separate total was reported.
                    UsageCountRow(R.drawable.lucide_boxes, R.string.turn_total, usage?.totalTokens)
                    UsageCountRow(R.drawable.lucide_arrow_up, R.string.turn_input, usage?.inputTokens)
                    UsageCountRow(R.drawable.lucide_database, R.string.turn_cached, usage?.cachedInputTokens)
                    UsageCountRow(R.drawable.lucide_arrow_down, R.string.turn_output, usage?.outputTokens)
                    UsageCountRow(R.drawable.lucide_brain, R.string.turn_reasoning, usage?.reasoningTokens)
                    timing?.tokensPerSecond(usage?.outputTokens)?.let {
                        UsageRow(R.drawable.lucide_zap, R.string.turn_tps, stringResource(R.string.turn_tps_value, it))
                    }
                    UsageDurationRow(R.drawable.lucide_activity, R.string.turn_ttft, timing?.firstTokenMs)
                    UsageDurationRow(R.drawable.lucide_timer, R.string.turn_first_body, timing?.firstBodyMs)
                    UsageDurationRow(R.drawable.lucide_clock, R.string.turn_elapsed, timing?.elapsedMs)
                }
            }
        }
    }
}

@Composable
private fun formatBriefTokenCount(tokens: Long): String {
    if (tokens < 100_000L) return tokens.toString()
    return stringResource(R.string.turn_count_thousands, BigDecimal.valueOf(tokens, 3))
}

@Composable
private fun UsageCountRow(@DrawableRes icon: Int, label: Int, tokens: Long?) {
    if (tokens != null) UsageRow(icon, label, formatBriefTokenCount(tokens))
}

@Composable
private fun UsageDurationRow(@DrawableRes icon: Int, label: Int, millis: Long?) {
    if (millis == null) return
    val value = if (millis < 1000L) stringResource(R.string.turn_millis, millis)
        else stringResource(R.string.turn_seconds, BigDecimal.valueOf(millis, 3))
    UsageRow(icon, label, value)
}

private class UsagePosition(val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = if (above >= gap) above else anchorBounds.bottom + gap
        return IntOffset(x.coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)),
            y.coerceIn(gap, (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap)))
    }
}

@Composable
private fun UsageRow(@DrawableRes icon: Int, label: Int, value: String) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(12.dp), tint = ink.copy(alpha = .5f))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), Modifier.weight(1f, fill = false), fontSize = 12.sp, lineHeight = 16.sp,
            letterSpacing = 0.sp, color = ink.copy(alpha = .8f))
        Text(value, Modifier.padding(start = 4.dp), fontSize = 12.sp, lineHeight = 16.sp,
            letterSpacing = 0.sp, color = ink.copy(alpha = .8f))
    }
}

@Composable
internal fun ReplyTimeline(msg: Message, query: String, thoughtExpanded: Boolean, toggleThought: () -> Unit) {
    val limit = if (msg.status.isActive) Int.MAX_VALUE else msg.text.length
    val (positioned, unplaced) = remember(msg.toolCalls, limit) {
        msg.toolCalls.partition { it.bodyOffset != null && it.bodyOffset in 0..limit }
    }
    val groups = remember(positioned) { positioned.groupBy { requireNotNull(it.bodyOffset) }.toSortedMap() }
    msg.tool?.let { name ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.lucide_wrench), null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.turn_local_tool), style = MaterialTheme.typography.labelSmall)
            Text(highlighted(name, query), style = MaterialTheme.typography.labelMedium)
        }
    }
    if (msg.thought.isNotBlank() || groups.containsKey(0)) {
        ReplyToolChain(msg.text, msg.thought, groups[0].orEmpty(), query, thoughtExpanded, toggleThought)
    }
    var cursor = 0
    for ((position, calls) in groups) {
        if (position == 0) continue
        val offset = position.coerceIn(cursor, msg.text.length)
        val segment = msg.text.substring(cursor, offset)
        if (segment.isNotBlank()) AssistantBody(segment, query, Mode.Chat)
        key(position) { ReplyToolChain(msg.text, "", calls, query, false, {}) }
        cursor = offset
    }
    val last = msg.text.substring(cursor)
    if (last.isNotBlank()) AssistantBody(last, query, Mode.Chat)
    if (unplaced.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.turn_tool_position_unknown), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReplyToolChain(msg.text, "", unplaced, query, false, {})
        }
    }
}

@Composable
private fun ReplyToolChain(body: String, thought: String, calls: List<ReplyToolCall>, query: String,
    thoughtExpanded: Boolean, toggleThought: () -> Unit) {
    val hasThought = thought.isNotBlank()
    val firstCall = if (hasThought) 1 else 0
    val size = firstCall + calls.size
    val compact = hasThought && size == 1 && !thoughtExpanded
    val width = if (compact) Modifier.width(IntrinsicSize.Max) else Modifier.fillMaxWidth()
    ChatTintedSurface(ChatSurface.ThoughtAndTools, width.testTag(if (hasThought) "message-thought" else "message-tool-chain")) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (hasThought) {
                TimelineStep(R.drawable.lucide_lightbulb, stringResource(R.string.turn_thinking), query, 0, size,
                    detail = thought, open = thoughtExpanded, toggle = toggleThought)
            }
            calls.forEachIndexed { i, call ->
                key(i, call.name) {
                    var open by rememberSaveable { mutableStateOf(false) }
                    val status = when (call.state) {
                        ToolCallState.Requested -> R.string.turn_tool_requested
                        ToolCallState.Running -> R.string.turn_tool_running
                        ToolCallState.Completed -> R.string.turn_tool_done
                        ToolCallState.Failed -> R.string.turn_tool_failed
                        ToolCallState.Incomplete -> R.string.turn_tool_incomplete
                    }
                    val offset = call.bodyOffset?.takeIf { it in 0..body.length }
                    val detail = when {
                        !open || offset == null -> null
                        offset == 0 -> stringResource(R.string.turn_tool_before_body)
                        else -> stringResource(R.string.turn_tool_position, body.codePointCount(0, offset))
                    }
                    TimelineStep(toolIcon(call.name), call.name.ifBlank { stringResource(R.string.turn_unnamed_tool) },
                        query, firstCall + i, size, stringResource(status), detail, open, toggle = offset?.let { { open = !open } })
                }
            }
        }
    }
}

private fun toolIcon(name: String) = when (name) {
    "web_search", "web_search_preview" -> R.drawable.lucide_search
    "file_search" -> R.drawable.lucide_file_search
    "code_interpreter" -> R.drawable.lucide_terminal
    else -> R.drawable.lucide_wrench
}

@Composable
private fun TimelineStep(@DrawableRes icon: Int, label: String, query: String, index: Int, count: Int,
    status: String? = null, detail: String? = null, open: Boolean = false, toggle: (() -> Unit)? = null) {
    val ink = LocalContentColor.current
    val dark = LocalMessageStyle.current.tone == MessageTone.Dark
    val line = ink.copy(alpha = .16f)
    val state = stringResource(if (open) R.string.turn_expanded else R.string.turn_collapsed)
    val interaction = if (toggle == null) Modifier else Modifier.clickable(onClick = toggle).semantics { stateDescription = state }
    Row(Modifier.fillMaxWidth().drawBehind {
        val x = 12.dp.toPx()
        val middle = size.height / 2
        val cut = 12.dp.toPx()
        if (index > 0) drawLine(line, Offset(x, 0f), Offset(x, (middle - cut).coerceAtLeast(0f)), 1.dp.toPx())
        if (index + 1 < count) drawLine(line, Offset(x, (middle + cut).coerceAtMost(size.height)), Offset(x, size.height), 1.dp.toPx())
    }.then(interaction).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = ink.copy(alpha = .78f))
        }
        Spacer(Modifier.width(8.dp))
        Text(highlighted(label, query), Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 13.sp,
            lineHeight = 18.sp, letterSpacing = 0.sp, color = ink, overflow = TextOverflow.Ellipsis, maxLines = 2)
        if (status != null) Text(status, Modifier.padding(start = 8.dp), fontSize = 11.sp, lineHeight = 14.sp,
            letterSpacing = 0.sp, color = if (dark) ink else ink.copy(alpha = .65f))
        if (toggle != null) Icon(painterResource(if (open) R.drawable.lucide_chevron_up else if (status == null)
            R.drawable.lucide_chevron_down else R.drawable.lucide_chevron_right), null, Modifier.padding(start = 6.dp).size(14.dp),
            tint = ink.copy(alpha = .55f))
    }
    if (open && detail != null) SelectionContainer {
        Text(highlighted(detail, query), Modifier.fillMaxWidth().drawBehind {
            val x = 12.dp.toPx()
            if (index + 1 < count) drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }.padding(start = 32.dp, bottom = 8.dp), fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.sp,
            color = if (dark) ink else ink.copy(alpha = .8f))
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
    ChatTintedSurface(ChatSurface.Translation, Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.lucide_languages), null, Modifier.size(18.dp))
                Text(stringResource(R.string.turn_translation_in, language), Modifier.weight(1f),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Icon(painterResource(if (expanded) R.drawable.lucide_chevron_up else R.drawable.lucide_chevron_down),
                    null, Modifier.size(14.dp))
            }
            if (run != null) TextButton(onClick = { translations.cancel(msg.id) }) {
                Text(stringResource(if (run.failed) android.R.string.ok else R.string.settings_cancel))
            }
            if (expanded) {
                if (run?.failed == true) Text(stringResource(R.string.turn_translation_failed), color = MaterialTheme.colorScheme.error)
                else if (run != null && value.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (value.isNotBlank()) MarkdownBody(value, query, Modifier.fillMaxWidth(),
                    MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp))
            }
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
