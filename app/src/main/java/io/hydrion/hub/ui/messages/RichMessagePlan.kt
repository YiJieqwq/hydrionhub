package io.hydrion.hub.ui.messages

/** IDs follow the order in the whole reply, including body runs separated by tool calls. */
internal data class RenderItem(val kind: String, val source: String)
internal class MathSpan(val start: Int, val end: Int, val slot: Int, val display: Boolean)
internal class MathLine(val text: String, val spans: List<MathSpan>)

internal sealed interface RichFragment {
    class Formula(val line: MathLine) : RichFragment
    class Diagram(val slot: Int) : RichFragment
    class Table(val rows: List<List<MathLine>>) : RichFragment
}

internal class RichMessagePlan(val items: List<RenderItem>, val bodies: Map<Int, Map<Int, RichFragment>>)

private val tableDivider = Regex(":?-{3,}:?")
private val tableColumn = Regex("""(?<!\\)\|""")

private fun mathTable(text: String): List<List<String>>? {
    val lines = text.lines()
    if (lines.size !in 2..26 || '|' !in lines[0] || '|' !in lines[1]) return null
    fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split(tableColumn).map { it.trim() }
    val columns = cells(lines[1])
    if (columns.size !in 2..8 || !columns.all(tableDivider::matches)) return null
    val rows = listOf(cells(lines[0])) + lines.drop(2).map(::cells)
    return rows.takeIf { it.all { row -> row.size == columns.size } }
}

private fun mathLine(text: String, add: (String, String) -> Int): MathLine {
    val spans = mutableListOf<MathSpan>()
    var pos = 0
    while (pos < text.length) {
        if (text[pos] == '\u0060') {
            val start = pos++
            while (pos < text.length && text[pos] == '\u0060') pos++
            val fence = text.substring(start, pos)
            val end = text.indexOf(fence, pos)
            if (end >= 0) pos = end + fence.length
            continue
        }
        val display = text.startsWith("$$", pos) || text.startsWith("\\[", pos)
        val endMarker = when {
            text.startsWith("$$", pos) -> "$$"
            text.startsWith("\\[", pos) -> "\\]"
            text.startsWith("\\(", pos) -> "\\)"
            text[pos] == '$' && text.getOrNull(pos + 1)?.isWhitespace() == false -> "$"
            else -> {
                pos += if (text[pos] == '\\' && pos + 1 < text.length) 2 else 1
                continue
            }
        }
        val start = pos + endMarker.length
        var end = start
        var matched = false
        while (end < text.length && end - start <= 2048) {
            if (text.startsWith(endMarker, end)) {
                matched = end > start && (endMarker != "$" ||
                    (!text[end - 1].isWhitespace() && text.getOrNull(end + 1)?.isDigit() != true))
                break
            }
            if (!display && (text[end] == '\n' || text[end] == '\u0060' ||
                    text.startsWith("\\(", end) || text.startsWith("\\[", end))) break
            end += if (text[end] == '\\' && end + 1 < text.length) 2 else 1
        }
        if (matched) {
            val source = text.substring(start, end)
            if (source.isNotBlank()) {
                val slot = add(if (display) "display" else "inline", source)
                spans += MathSpan(pos, end + endMarker.length, slot, display)
            }
            pos = end + endMarker.length
        } else pos = start
    }
    return MathLine(text, spans)
}

internal fun richMessagePlan(blocks: List<ReplyBlock>): RichMessagePlan? {
    if (blocks.sumOf { if (it is ReplyBlock.Body) it.text.length.toLong() else 0L } > 96000L) return null
    val items = mutableListOf<RenderItem>()
    val bodies = mutableMapOf<Int, Map<Int, RichFragment>>()
    var total = 0
    var diagrams = 0
    fun add(kind: String, source: String): Int {
        if (items.size >= 48 || total + source.length > 48000 || (kind == "mermaid" &&
                (diagrams >= 12 || source.length > 8192 || source.count { it == '\n' } >= 120))) return -1
        total += source.length
        if (kind == "mermaid") diagrams++
        items += RenderItem(kind, source)
        return items.lastIndex
    }
    blocks.forEachIndexed { index, body ->
        if (body !is ReplyBlock.Body || !('$' in body.text || "\\(" in body.text || "\\[" in body.text ||
                "mermaid" in body.text.lowercase())) return@forEachIndexed
        val fragments = mutableMapOf<Int, RichFragment>()
        for ((n, block) in markdownBlocks(body.text).withIndex()) {
            if (block is MarkdownBlock.Code) {
                if (block.closed && block.language.equals("mermaid", true) && block.value.isNotBlank()) {
                    fragments[n] = RichFragment.Diagram(add("mermaid", block.value))
                }
                continue
            }
            val text = when (block) {
                is MarkdownBlock.Paragraph -> block.value
                is MarkdownBlock.Quote -> block.value
                is MarkdownBlock.Heading -> block.value
                else -> continue
            }
            if ('$' !in text && "\\(" !in text && "\\[" !in text) continue
            val cells = if (block is MarkdownBlock.Paragraph) mathTable(text) else null
            if (cells != null) {
                val rows = cells.map { row -> row.map { mathLine(it, ::add) } }
                if (rows.any { row -> row.any { it.spans.isNotEmpty() } }) fragments[n] = RichFragment.Table(rows)
            } else {
                val line = mathLine(text, ::add)
                if (line.spans.isNotEmpty()) fragments[n] = RichFragment.Formula(line)
            }
        }
        if (fragments.isNotEmpty()) bodies[index] = fragments
    }
    return if (bodies.isEmpty()) null else RichMessagePlan(items, bodies)
}
