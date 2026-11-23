package io.hydrion.hub.ui.messages

import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Role
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

private val openingFence = Regex("""([ \t]*)(`{3,}|~{3,})(.*)""")
private val closingFence = Regex("""([ \t]*)(`{3,}|~{3,})[ \t]*""")
private fun String.isFenceMargin() = length <= 3 && '\t' !in this

private fun String.withoutFenceIndent(width: Int): String {
    var index = 0
    while (index < width && index < length) {
        when (this[index]) {
            ' ' -> index++
            '\t' -> return " ".repeat(4 - width) + substring(index + 1)
            else -> break
        }
    }
    return substring(index)
}

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
        val fence = openingFence.matchEntire(line)?.takeUnless {
            val (_, marker, info) = it.destructured
            marker.first() == '`' && '`' in info
        }
        when {
            fence != null -> {
                flush()
                val (margin, marker, info) = fence.destructured
                // Deeper fences can occur in existing list messages. Keep their literal indentation.
                val indent = if (margin.isFenceMargin()) margin.length else 0
                val code = mutableListOf<String>()
                while (pos < lines.size) {
                    val bodyLine = lines[pos++]
                    val end = closingFence.matchEntire(bodyLine)
                    if (end != null) {
                        val (endMargin, endMarker) = end.destructured
                        if ((endMargin.isFenceMargin() || endMargin == margin) && endMarker.startsWith(marker)) break
                    }
                    code += bodyLine.withoutFenceIndent(indent)
                }
                val language = info.trimStart().takeWhile { !it.isWhitespace() }
                add(MarkdownBlock.Code(language, code.joinToString("\n")))
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

internal val inlineMarkdown = Regex("""(`[^`\n]+`|\*\*[^*]+\*\*|__[^_]+__|\*[^*\n]+\*|_[^_\n]+_|~~[^~\n]+~~|\[[^\]\n]+]\(https?://[^\s)]+\))""")

internal fun MatchResult.inlineLabel(): String = when {
    value.startsWith('[') -> value.substring(1, value.indexOf("]("))
    value.startsWith("**") || value.startsWith("__") || value.startsWith("~~") -> value.substring(2, value.length - 2)
    else -> value.substring(1, value.lastIndex)
}

internal fun Message.searchableBody(): String {
    if (role == Role.User || text.isEmpty()) return text
    fun inlineText(source: String) = inlineMarkdown.replace(source) { it.inlineLabel() }
    return markdownBlocks(text).mapNotNull { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> inlineText(block.value)
            is MarkdownBlock.Heading -> inlineText(block.value)
            is MarkdownBlock.Quote -> inlineText(block.value)
            is MarkdownBlock.Code -> if (block.language.isEmpty()) block.value else "${block.language}\n${block.value}"
            MarkdownBlock.Rule -> null
        }
    }.joinToString("\n")
}

internal fun Message.containsVisibleBody(needle: String) = searchableBody().contains(needle, ignoreCase = true) ||
    (imageKeys.isNotEmpty() && prompt.contains(needle, ignoreCase = true))

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

