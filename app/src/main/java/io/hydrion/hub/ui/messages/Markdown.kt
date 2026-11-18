package io.hydrion.hub.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val inline = Regex("""(`[^`\n]+`|\*\*[^*]+\*\*|__[^_]+__|\*[^*\n]+\*|_[^_\n]+_|~~[^~\n]+~~|\[[^\]\n]+]\(https?://[^\s)]+\))""")

private fun AnnotatedString.Builder.markMatches(query: String, highlight: Color) {
    if (query.isBlank()) return
    val value = toString()
    var index = 0
    while (index < value.length) {
        val match = value.indexOf(query, index, ignoreCase = true)
        if (match < 0) break
        index = match + query.length
        addStyle(SpanStyle(background = highlight), match, index)
    }
}

@Composable
internal fun highlighted(value: String, query: String): AnnotatedString {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = .32f)
    return remember(value, query, tint) {
        buildAnnotatedString { append(value); markMatches(query, tint) }
    }
}

@Composable
private fun richText(value: String, query: String): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    val style = LocalMessageStyle.current.renderPalette.assistant
    return remember(value, query, colors, style) {
        buildAnnotatedString {
            var start = 0
            for (match in inline.findAll(value)) {
                append(value.substring(start, match.range.first))
                val token = match.value
                when {
                    token.startsWith('`') -> withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace, background = style.background.messageColor(), color = style.text.messageColor()
                    )) { append(token.substring(1, token.lastIndex)) }
                    token.startsWith("**") || token.startsWith("__") ->
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.drop(2).dropLast(2)) }
                    token.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(token.drop(2).dropLast(2))
                    }
                    token.startsWith('[') -> {
                        val end = token.indexOf("](")
                        val url = token.substring(end + 2, token.length - 1)
                        withLink(LinkAnnotation.Url(url)) {
                            withStyle(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)) {
                                append(token.substring(1, end))
                            }
                        }
                    }
                    else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.lastIndex))
                    }
                }
                start = match.range.last + 1
            }
            append(value.substring(start))
            markMatches(query, colors.primary.copy(alpha = .32f))
        }
    }
}

private val listLine = Regex("""^(\s*)(•|[0-9]{1,6}[.)])\s+(.+)""")

@Composable
private fun Paragraph(value: String, query: String) {
    val lines = remember(value) { value.lines().map { it to listLine.matchEntire(it) } }
    val markerLength = lines.maxOfOrNull { it.second?.groupValues?.get(2)?.length ?: 0 } ?: 0
    val style = MaterialTheme.typography.bodyLarge
    if (markerLength == 0) {
        Text(richText(value, query), style = style)
        return
    }
    val gutter = with(LocalDensity.current) { (markerLength * 9).sp.toDp() } + 7.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { (line, match) ->
            if (match == null) {
                Text(richText(line, query), style = style)
            } else {
                val indent = (match.groupValues[1].length.coerceAtMost(12) * 5).dp
                Row(Modifier.fillMaxWidth().padding(start = indent)) {
                    Text(match.groupValues[2], Modifier.width(gutter), style = style)
                    Text(richText(match.groupValues[3], query), Modifier.weight(1f), style = style)
                }
            }
        }
    }
}

@Composable
internal fun MarkdownBody(value: String, query: String) {
    val parsed = remember(value) { markdownBlocks(value) }
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            parsed.forEach { block ->
                when (block) {
                    is MarkdownBlock.Paragraph -> Paragraph(block.value, query)
                    is MarkdownBlock.Heading -> Text(
                        richText(block.value, query),
                        style = if (block.level < 3) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    is MarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                        Spacer(Modifier.width(3.dp).fillMaxHeight().background(LocalMessageStyle.current.renderPalette.assistant.border.messageColor()))
                        Text(richText(block.value, query), Modifier.padding(start = 12.dp), color = LocalContentColor.current)
                    }
                    is MarkdownBlock.Code -> CodeBlock(block, query)
                    MarkdownBlock.Rule -> HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code, query: String) {
    val clipboard = LocalClipboardManager.current
    val palette = LocalMessageStyle.current.renderPalette.assistant
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.background.messageColor())) {
        Row(Modifier.fillMaxWidth().background(palette.border.messageColor().copy(alpha = .35f)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(block.language.ifBlank { "代码" }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { clipboard.setText(AnnotatedString(block.value)) }) {
                Icon(Icons.Outlined.ContentCopy, "复制代码", Modifier.size(16.dp))
            }
        }
        Text(highlighted(block.value, query), Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
