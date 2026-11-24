package io.hydrion.hub.ui.messages

import android.animation.ValueAnimator
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.ui.MotionDurationScale
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.ui.semantics.heading
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import io.hydrion.hub.R
import io.hydrion.hub.model.*
import io.hydrion.hub.provider.chatProtocol
import io.hydrion.hub.provider.route
import kotlinx.coroutines.flow.filterNot
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date

@Composable
internal fun TurnByline(name: String, time: Long, modifier: Modifier = Modifier, align: Alignment.Horizontal = Alignment.Start) {
    val locale = LocalConfiguration.current.locales[0]
    val stamp = remember(time, locale) { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).format(Date(time)) }
    val type = MaterialTheme.typography.bodyMedium.copy(
        fontWeight = FontWeight.Normal,
        lineHeight = 1.43.em,
        letterSpacing = .25.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        textMotion = TextMotion.Animated
    )
    Column(modifier, horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(if (align == Alignment.End) 2.dp else 0.dp)) {
        Text(name, style = type, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stamp, style = type, fontSize = 11.sp,
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

    val visible = remember(msg.id, hasDetails) { MutableTransitionState(false) }
    val action = if (hasDetails) Modifier.clickable(role = UiRole.Button) { visible.targetState = true } else Modifier
    val colors = MaterialTheme.colorScheme
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Box(modifier) {
        Box(action.heightIn(min = 28.dp), contentAlignment = Alignment.Center) {
            val label = if (total != null) stringResource(R.string.turn_tokens, formatBriefTokenCount(total))
                else stringResource(R.string.turn_usage)
            Text(label,
                fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp, color = colors.onSurface.copy(alpha = .5f))
        }
        if (visible.targetState || !visible.isIdle || visible.currentState) Popup(remember(gap) { UsagePosition(gap) }, onDismissRequest = { visible.targetState = false },
            properties = PopupProperties(focusable = true)) {
            val duration = if (chatMotionEnabled()) 180 else 0
            AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(duration, easing = EaseOutCubic)),
                exit = fadeOut(tween(duration, easing = EaseInCubic))) {
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
    var selected by rememberSaveable(msg.id) { mutableStateOf<Int?>(null) }
    val calls = remember(msg.toolCalls) { msg.toolCalls.withIndex().toList() }
    val limit = if (msg.status.isActive) Int.MAX_VALUE else msg.text.length
    val (positioned, unplaced) = remember(calls, limit) {
        calls.partition { (_, call) -> call.bodyOffset != null && call.bodyOffset in 0..limit }
    }
    val groups = remember(positioned) { positioned.groupBy { requireNotNull(it.value.bodyOffset) }.toSortedMap() }
    msg.tool?.let { name ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.lucide_wrench), null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.turn_local_tool), style = MaterialTheme.typography.labelSmall)
            Text(highlighted(name, query), style = MaterialTheme.typography.labelMedium)
        }
    }
    if (msg.thought.isNotBlank() || groups.containsKey(0)) {
        ReplyToolChain(msg.thought, groups[0].orEmpty(), query, thoughtExpanded, toggleThought,
            streamingThought = msg.status.isActive && msg.text.isBlank(), openRecord = { selected = it })
    }
    var cursor = 0
    for ((position, calls) in groups) {
        if (position == 0) continue
        val offset = position.coerceIn(cursor, msg.text.length)
        val segment = msg.text.substring(cursor, offset)
        if (segment.isNotBlank()) AssistantBody(segment, query, Mode.Chat)
        key(position) { ReplyToolChain("", calls, query, false, {}, openRecord = { selected = it }) }
        cursor = offset
    }
    val last = msg.text.substring(cursor)
    if (last.isNotBlank()) AssistantBody(last, query, Mode.Chat)
    if (unplaced.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.turn_tool_position_unknown), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReplyToolChain("", unplaced, query, false, {}, openRecord = { selected = it })
        }
    }
    // A streaming update can move a step to a different text block while its sheet is open.
    selected?.let { id ->
        val call = calls.firstOrNull { it.recordKey == id }?.value ?: return@let
        val offset = call.bodyOffset?.takeIf { it in 0..msg.text.length }
        val position = when (offset) {
            null -> null
            0 -> stringResource(R.string.turn_tool_before_body)
            else -> stringResource(R.string.turn_tool_position, msg.text.codePointCount(0, offset))
        }
        RecordedToolSheet(call.name.ifBlank { stringResource(R.string.turn_unnamed_tool) },
            stringResource(toolStatus(call.state)), position, call) { selected = null }
    }
}

// Live records have a protocol ordinal. Legacy records use their stable position in the saved reply.
private val IndexedValue<ReplyToolCall>.recordKey: Int get() = value.slot ?: -1 - index

private fun toolStatus(state: ToolCallState): Int = when (state) {
    ToolCallState.Requested -> R.string.turn_tool_requested
    ToolCallState.Running -> R.string.turn_tool_running
    ToolCallState.Completed -> R.string.turn_tool_done
    ToolCallState.Failed -> R.string.turn_tool_failed
    ToolCallState.Incomplete -> R.string.turn_tool_incomplete
}

@Composable
private fun ReplyToolChain(thought: String, calls: List<IndexedValue<ReplyToolCall>>, query: String,
    thoughtExpanded: Boolean, toggleThought: () -> Unit, streamingThought: Boolean = false, openRecord: (Int) -> Unit) {
    val hasThought = thought.isNotBlank()
    val firstCall = if (hasThought) 1 else 0
    val size = firstCall + calls.size
    val compact = hasThought && calls.isEmpty() && !streamingThought && !thoughtExpanded
    val width = if (compact) Modifier.width(IntrinsicSize.Max) else Modifier.fillMaxWidth()
    ChatTintedSurface(ChatSurface.ThoughtAndTools, width.testTag(if (hasThought) "message-thought" else "message-tool-chain")) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (hasThought) {
                TimelineStep(R.drawable.thinking_idea, stringResource(R.string.turn_thinking), query, 0, size,
                    detail = thought, open = thoughtExpanded, toggle = toggleThought)
            }
            calls.forEachIndexed { i, step ->
                key(step.recordKey) {
                    val call = step.value
                    TimelineStep(toolIcon(call.name), call.name.ifBlank { stringResource(R.string.turn_unnamed_tool) },
                        query, firstCall + i, size, status = stringResource(toolStatus(call.state)),
                        detail = call.result, open = call.result != null, toggle = { openRecord(step.recordKey) })
                }
            }
        }
    }
}

private fun toolIcon(name: String) = when (name) {
    "search_web" -> R.drawable.lucide_earth
    "web_search", "web_search_call", "web_search_preview", "chat_search", "builtin_search" -> R.drawable.lucide_search
    "file_search", "file_search_call" -> R.drawable.lucide_file_search
    "bash_code_execution" -> R.drawable.lucide_terminal
    "code_execution", "text_editor_code_execution", "code_interpreter", "code_interpreter_call" -> R.drawable.lucide_code
    "image_generation_call" -> R.drawable.lucide_image
    else -> R.drawable.lucide_wrench
}

// Keep the Material focus and hover feedback on these rows while dropping only the press ripple.
private data object StepIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource) = ripple().create(object : InteractionSource {
        override val interactions = interactionSource.interactions.filterNot { it is PressInteraction }
    })
}

@Composable
private fun chatMotionEnabled(): Boolean {
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    return (scale?.scaleFactor ?: 1f) > 0f && ValueAnimator.areAnimatorsEnabled()
}

private val cardEasing = CubicBezierEasing(.2f, .8f, .2f, 1f)

@Composable
private fun ExpandingCardBody(visible: Boolean, content: @Composable () -> Unit) {
    if (chatMotionEnabled()) {
        AnimatedVisibility(visible, enter = expandVertically(tween(300, easing = cardEasing), expandFrom = Alignment.Top),
            exit = shrinkVertically(tween(300, easing = cardEasing), shrinkTowards = Alignment.Top)) { content() }
    } else if (visible) content()
}

@Composable
private fun TimelineStep(@DrawableRes icon: Int, label: String, query: String, index: Int, count: Int,
    status: String? = null, detail: String? = null, open: Boolean = false, toggle: (() -> Unit)? = null) {
    val ink = LocalContentColor.current
    val dark = LocalMessageStyle.current.tone == MessageTone.Dark
    val line = ink.copy(alpha = .16f)
    val state = stringResource(if (open) R.string.turn_expanded else R.string.turn_collapsed)
    val interaction = if (toggle == null) Modifier else Modifier.clickable(
        interactionSource = null, indication = StepIndication, role = UiRole.Button,
        onClickLabel = if (status != null) stringResource(R.string.turn_tool_details) else null, onClick = toggle
    ).then(if (status == null) Modifier.semantics { stateDescription = state } else Modifier)
    Row(Modifier.fillMaxWidth().drawBehind {
        val x = 12.dp.toPx()
        val middle = size.height / 2
        val cut = 12.dp.toPx()
        if (index > 0) drawLine(line, Offset(x, 0f), Offset(x, (middle - cut).coerceAtLeast(0f)), 1.dp.toPx())
        if (index + 1 < count) drawLine(line, Offset(x, (middle + cut).coerceAtMost(size.height)), Offset(x, size.height), 1.dp.toPx())
    }.then(interaction).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(if (status == null) 18.dp else 16.dp), tint = ink.copy(alpha = .78f))
        }
        Spacer(Modifier.width(8.dp))
        Text(highlighted(label, query), Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 13.sp,
            lineHeight = 1.43.em, letterSpacing = 0.sp, color = ink, overflow = TextOverflow.Ellipsis, maxLines = 2)
        if (status != null) Text(status, Modifier.padding(start = 8.dp), fontSize = 11.sp, lineHeight = 14.sp,
            letterSpacing = 0.sp, color = if (dark) ink else ink.copy(alpha = .65f))
        if (toggle != null) Icon(painterResource(if (status != null) R.drawable.lucide_chevron_right
            else if (open) R.drawable.lucide_chevron_up else R.drawable.lucide_chevron_down),
            null, Modifier.padding(start = 6.dp).size(16.dp), tint = ink.copy(alpha = .55f))
    }
    ExpandingCardBody(open && detail != null) {
        if (detail == null) return@ExpandingCardBody
        Box(Modifier.fillMaxWidth().drawBehind {
            if (index + 1 < count) {
                val x = 12.dp.toPx()
                drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
        }.padding(start = 32.dp, top = 4.dp, bottom = 8.dp)) {
            val bodyInk = if (dark) ink else ink.copy(alpha = .8f)
            if (status == null) SelectionContainer {
                Text(highlighted(detail, query), Modifier.fillMaxWidth(), fontSize = 12.5.sp,
                    lineHeight = 16.5.sp, letterSpacing = 0.sp, color = bodyInk)
            } else {
                Text(highlighted(detail, query), Modifier.fillMaxWidth().clickable(interactionSource = null,
                    indication = StepIndication, role = UiRole.Button, onClickLabel = stringResource(R.string.turn_tool_details),
                    onClick = { toggle?.invoke() }), fontSize = 12.sp, lineHeight = 16.8.sp, letterSpacing = 0.sp,
                    color = bodyInk, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RecordedToolSheet(name: String, status: String, position: String?, call: ReplyToolCall, close: () -> Unit) {
    TurnActionSheet(close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(name, Modifier.semantics { heading() }, fontSize = 15.sp, lineHeight = 18.sp,
                        letterSpacing = 0.sp, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = close) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.turn_close_details))
                }
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((field, value) in listOf(R.string.turn_tool_status to status, R.string.turn_tool_arguments to call.arguments,
                        R.string.turn_tool_result to call.result, R.string.turn_tool_offset_label to position)) {
                        if (value == null) continue
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(field), fontSize = 12.sp, lineHeight = 1.43.em, letterSpacing = 0.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, fontSize = 12.sp, lineHeight = 1.43.em, letterSpacing = 0.sp)
                        }
                    }
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
            ExpandingCardBody(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (run?.failed == true) Text(stringResource(R.string.turn_translation_failed), color = MaterialTheme.colorScheme.error)
                    else if (run != null && value.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (value.isNotBlank()) MarkdownBody(value, query, Modifier.fillMaxWidth(),
                        MaterialTheme.typography.bodyMedium.copy(fontSize = 15.5.sp, lineHeight = 21.7.sp, letterSpacing = 0.sp), chat = true)
                }
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
