package io.hydrion.hub.json

/** Android and desktop JSONTokener both accept non-JSON values. Validate grammar and depth first. */
internal class JsonSyntax(private val text: String, private val invalid: () -> Nothing) {
    private var offset = 0

    fun check() {
        value(0)
        whitespace()
        if (offset != text.length) invalid()
    }

    private fun value(depth: Int) {
        whitespace()
        when (text.getOrNull(offset)) {
            '{' -> container('}', depth)
            '[' -> container(']', depth)
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> offset = (Number.matchAt(text, offset) ?: invalid()).range.last + 1
            else -> invalid()
        }
    }

    private fun container(end: Char, depth: Int) {
        if (depth >= 32) invalid()
        offset++
        whitespace()
        if (take(end)) return
        do {
            whitespace()
            if (end == '}') {
                string()
                whitespace()
                if (!take(':')) invalid()
            }
            value(depth + 1)
            whitespace()
        } while (take(','))
        if (!take(end)) invalid()
    }

    private fun string() {
        if (!take('"')) invalid()
        while (offset < text.length) {
            when (text[offset++]) {
                '"' -> return
                '\\' -> when (text.getOrNull(offset++)) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) {
                        val digit = text.getOrNull(offset++) ?: invalid()
                        if (digit !in "0123456789abcdefABCDEF") invalid()
                    }
                    else -> invalid()
                }
                in '\u0000'..'\u001f' -> invalid()
            }
        }
        invalid()
    }

    private fun literal(word: String) {
        if (!text.startsWith(word, offset)) invalid()
        offset += word.length
    }

    private fun whitespace() {
        while (offset < text.length && text[offset] in " \t\r\n") offset++
    }

    private fun take(c: Char): Boolean {
        if (text.getOrNull(offset) != c) return false
        offset++
        return true
    }

    private companion object {
        val Number = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
    }
}
