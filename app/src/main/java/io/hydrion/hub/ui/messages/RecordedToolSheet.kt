package io.hydrion.hub.ui.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.model.ReplyToolCall
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun ShellExitLabel(code: Int) {
    val dark = LocalMessageStyle.current.tone == MessageTone.Dark
    val tint = if (code != 0) MaterialTheme.colorScheme.error else if (dark) Color(0xFF73C878) else Color(0xFF23652D)
    Text(stringResource(R.string.turn_shell_exit, code.toString()), style = MaterialTheme.typography.labelSmall, color = tint)
}

@Composable
internal fun RecordedToolSheet(name: String, status: String, position: String?, call: ReplyToolCall, close: () -> Unit) {
    val detail = remember(call.name, call.arguments, call.result) { call.recordedDetail() }
    var showRecord by rememberSaveable(name, call.slot) { mutableStateOf(false) }
    val insets = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
    TurnActionSheet(close) {
        if (detail == null) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(insets),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RecordHeader(name, null, close)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RecordField(stringResource(R.string.turn_tool_status), status)
                        RawRecord(call)
                        position?.let { RecordField(stringResource(R.string.turn_tool_offset_label), it) }
                    }
                }
            }
        } else LazyColumn(Modifier.fillMaxWidth(), contentPadding = insets, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (detail) {
                is RecordedToolDetail.Shell -> {
                    item { RecordHeader(stringResource(R.string.turn_shell_name), detail.exit, close) }
                    detail.command?.let { command -> item {
                        SelectionContainer { CodeBlock(MarkdownBlock.Code("bash", command), "", chat = true, code = bashText(command)) }
                    } }
                    detail.directory?.let { item { RecordField(stringResource(R.string.turn_shell_directory), it) } }
                    detail.stdout?.let { item { SelectionContainer { CodeBlock(MarkdownBlock.Code("stdout", it), "", chat = true) } } }
                    detail.stderr?.let { item {
                        SelectionContainer { CodeBlock(MarkdownBlock.Code("stderr", it), "", chat = true, labelColor = MaterialTheme.colorScheme.error) }
                    } }
                }
                is RecordedToolDetail.Search -> {
                    item { RecordHeader(stringResource(R.string.chat_tools_web), null, close) }
                    if (detail.queries.isNotEmpty()) item {
                        SelectionContainer { RecordField(stringResource(R.string.turn_search_query), detail.queries.joinToString("\n")) }
                    }
                    if (detail.parameters.isNotEmpty()) item {
                        Text(detail.parameters.entries.joinToString("  ·  ") { (key, value) -> "$key: $value" },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    detail.summary?.let { summary -> item {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                            SelectionContainer {
                                Box(Modifier.fillMaxWidth().padding(12.dp)) { RecordField(stringResource(R.string.turn_search_summary), summary) }
                            }
                        }
                    } }
                    if (detail.sources.isNotEmpty()) {
                        item { Text(stringResource(R.string.turn_search_sources), style = MaterialTheme.typography.titleSmall) }
                        items(detail.sources) { source -> SourceRow(source) }
                    } else if (detail.recordedEmpty) item { Text(stringResource(R.string.turn_search_no_sources)) }
                }
            }
            item { RecordField(stringResource(R.string.turn_tool_status), status) }
            position?.let { item { RecordField(stringResource(R.string.turn_tool_offset_label), it) } }
            item { TextButton(onClick = { showRecord = !showRecord }) {
                Text(stringResource(if (showRecord) R.string.turn_hide_record else R.string.turn_show_record))
            } }
            if (showRecord) item { SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { RawRecord(call) }
            } }
        }
    }
}

@Composable
private fun RecordHeader(title: String, exit: Int?, close: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, fontSize = 15.sp, lineHeight = 18.sp,
                letterSpacing = 0.sp, fontWeight = FontWeight.Medium)
        }
        exit?.let { ShellExitLabel(it) }
        IconButton(onClick = close) { Icon(Icons.Outlined.Close, stringResource(R.string.turn_close_details)) }
    }
}

@Composable
private fun RecordField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 1.43.em, letterSpacing = 0.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, lineHeight = 1.43.em, letterSpacing = 0.sp)
    }
}

@Composable
private fun RawRecord(call: ReplyToolCall) {
    call.arguments?.let { RecordField(stringResource(R.string.turn_tool_arguments), it) }
    call.result?.let { RecordField(stringResource(R.string.turn_tool_result), it) }
}

@Composable
private fun SourceRow(source: RecordedSource) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                source.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                source.url?.let { address ->
                    val primary = MaterialTheme.colorScheme.primary
                    val text = remember(address, primary) {
                        // As in the search providers, only bounded HTTP(S) addresses without user info become links.
                        val url = address.takeIf { it.length <= 2048 }?.toHttpUrlOrNull()
                        buildAnnotatedString {
                            if (url != null && url.username.isEmpty() && url.password.isEmpty()) appendChatLink(address, url.toString(), primary)
                            else append(address)
                        }
                    }
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
                source.snippet?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                source.date?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                source.score?.let { Text(stringResource(R.string.turn_search_score, it.toString()), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

// Style literal text in the recorded command. The code block selects and copies the original string.
private val bashTokens = Regex("""(?m)(?:^|(?<=[;&|]))[ \t]*([\w./-]+)|(?<!\S)(#[^\r\n]*)|('[^']*+'|"(?:[^"\\]++|\\.)*+")|(\$(?:[A-Za-z_]\w*|\{[A-Za-z_]\w*\}))""")

@Composable
private fun bashText(command: String): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(command, colors) {
        buildAnnotatedString {
            append(command)
            for (match in bashTokens.findAll(command)) {
                val color = when {
                    match.groups[1] != null -> colors.primary
                    match.groups[2] != null -> colors.onSurfaceVariant
                    match.groups[3] != null -> colors.tertiary
                    else -> colors.secondary
                }
                addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
            }
        }
    }
}
