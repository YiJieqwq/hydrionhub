package io.hydrion.hub.ui.messages

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

@Composable
internal fun RichBlock(fragment: RichFragment, block: MarkdownBlock, images: Map<Int, RichImage>, query: String, style: TextStyle) {
    when (fragment) {
        is RichFragment.Chart -> androidx.compose.runtime.key((block as MarkdownBlock.Code).value) { MessageChartCard(fragment.data) }
        is RichFragment.Diagram -> {
            val code = block as MarkdownBlock.Code
            val image = images[fragment.slot]
            if (image == null) CodeBlock(code, query, chat = true)
            else Image(image.bitmap, code.value, Modifier.fillMaxWidth().heightIn(max = 560.dp)
                .aspectRatio(image.width / image.height).testTag("rich-diagram"))
        }
        is RichFragment.Formula -> {
            val s = fragment.line.spans.singleOrNull()
            if (s?.display == true && (fragment.line.text.take(s.start) + fragment.line.text.drop(s.end)).isBlank()) {
                val image = images[s.slot]
                if (image == null) CodeBlock(MarkdownBlock.Code("LaTeX", fragment.line.text), query, chat = true)
                else Image(image.bitmap, fragment.line.text, Modifier.fillMaxWidth().heightIn(max = 400.dp)
                    .aspectRatio(image.width / image.height).testTag("rich-display-math"))
            } else RichLine(fragment.line, query, style, images)
        }
        is RichFragment.Table -> BoxWithConstraints(Modifier.fillMaxWidth().testTag("rich-math-table")) {
            val width = (maxWidth / fragment.rows.first().size).coerceAtLeast(80.dp)
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                for ((rowIndex, row) in fragment.rows.withIndex()) {
                    Row {
                        for (cell in row) Box(Modifier.width(width).padding(horizontal = 6.dp, vertical = 4.dp)) {
                            RichLine(cell, query, if (rowIndex == 0) style.copy(fontWeight = FontWeight.SemiBold) else style, images)
                        }
                    }
                    HorizontalDivider(Modifier.width(width * row.size))
                }
            }
        }
    }
}

@Composable
private fun RichLine(line: MathLine, query: String, style: TextStyle, images: Map<Int, RichImage>) {
    val inline = mutableMapOf<String, InlineTextContent>()
    val content = buildAnnotatedString {
        var pos = 0
        for (span in line.spans) {
            append(richText(line.text.substring(pos, span.start), query, true))
            val literal = line.text.substring(span.start, span.end)
            val image = images[span.slot]
            if (image == null || image.width > 400) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(literal) }
            } else {
                val id = span.slot.toString()
                inline[id] = InlineTextContent(Placeholder((image.width / 16).em, (image.height / 16).em,
                    PlaceholderVerticalAlign.TextCenter)) {
                    Image(image.bitmap, null, Modifier.fillMaxSize().testTag("rich-inline-math"))
                }
                appendInlineContent(id, literal)
            }
            pos = span.end
        }
        append(richText(line.text.substring(pos), query, true))
    }
    Text(content, style = style, inlineContent = inline)
}
