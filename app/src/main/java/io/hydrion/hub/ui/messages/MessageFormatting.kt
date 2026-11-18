package io.hydrion.hub.ui.messages

import kotlin.math.max
import kotlin.math.roundToInt

internal sealed interface MarkdownBlock {
    data class Paragraph(val value: String) : MarkdownBlock
    data class Heading(val value: String, val level: Int) : MarkdownBlock
    data class Quote(val value: String) : MarkdownBlock
    data class Code(val language: String, val value: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private val heading = Regex("""^(#{1,6})\s+(.+)""")
private val bullet = Regex("""^(\s*)[-*+]\s+""")

internal fun markdownBlocks(markdown: String): List<MarkdownBlock> = buildList {
    val lines = markdown.lines()
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) add(MarkdownBlock.Paragraph(paragraph.joinToString("\n")))
        paragraph.clear()
    }
    var pos = 0
    while (pos < lines.size) {
        val line = lines[pos++]
        val trimmed = line.trimStart()
        val title = heading.matchEntire(trimmed)
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = mutableListOf<String>()
                while (pos < lines.size && !lines[pos].trimStart().startsWith("```")) code += lines[pos++]
                if (pos < lines.size) pos++
                add(MarkdownBlock.Code(trimmed.removePrefix("```").trim(), code.joinToString("\n")))
            }
            title != null -> {
                flush()
                add(MarkdownBlock.Heading(title.groupValues[2], title.groupValues[1].length))
            }
            trimmed.startsWith(">") -> {
                flush()
                val quote = mutableListOf(trimmed.drop(1).trimStart())
                while (pos < lines.size && lines[pos].trimStart().startsWith(">")) {
                    quote += lines[pos++].trimStart().drop(1).trimStart()
                }
                add(MarkdownBlock.Quote(quote.joinToString("\n")))
            }
            trimmed in listOf("---", "***", "___") -> { flush(); add(MarkdownBlock.Rule) }
            line.isBlank() -> flush()
            else -> paragraph += bullet.replaceFirst(line, "$1• ")
        }
    }
    flush()
}

internal data class Extent(val width: Int, val height: Int)
private val sizePattern = Regex("""\s*(\d{1,5})\s*[xX×]\s*(\d{1,5})\s*""")
private val ratioPattern = Regex("""\s*(\d{1,5})\s*:\s*(\d{1,5})\s*""")

// Custom models may declare only one of these capabilities. The ratio, if declared,
// determines the output shape; the selected size supplies its longest edge.
internal fun outputSize(parameters: Map<String, String>): Extent {
    val size = sizePattern.matchEntire(parameters["size"].orEmpty())
    val width = size?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 1024
    val height = size?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 } ?: 1024
    val ratio = ratioPattern.matchEntire(parameters["aspect"].orEmpty()) ?: return Extent(width, height)
    val rw = ratio.groupValues[1].toInt()
    val rh = ratio.groupValues[2].toInt()
    if (rw == 0 || rh == 0) return Extent(width, height)
    val edge = max(width, height)
    return if (rw >= rh) Extent(edge, (edge.toDouble() * rh / rw).roundToInt().coerceAtLeast(1))
    else Extent((edge.toDouble() * rw / rh).roundToInt().coerceAtLeast(1), edge)
}

