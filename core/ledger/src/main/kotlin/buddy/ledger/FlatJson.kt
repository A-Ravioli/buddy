package buddy.ledger

/**
 * Encoder and decoder for a flat `Map<String, String>` as a JSON object. The ledger
 * stores [Event.structured] this way. Deliberately tiny: no nesting, no numbers, no
 * dependency. Anything richer belongs in the entity graph, not in the raw ledger.
 */
object FlatJson {
    fun encode(map: Map<String, String>): String {
        if (map.isEmpty()) return "{}"
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map.toSortedMap()) {
            if (!first) sb.append(',')
            first = false
            quote(sb, k)
            sb.append(':')
            quote(sb, v)
        }
        return sb.append('}').toString()
    }

    fun decode(json: String): Map<String, String> = Parser(json.trim()).parseObject()

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseObject(): Map<String, String> {
            expect('{')
            skipWs()
            val out = LinkedHashMap<String, String>()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                skipWs()
                val value = parseString()
                out[key] = value
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("expected ',' or '}' but found '$c'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> fail("bad escape \\$e")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else fail("unexpected end")
        private fun next(): Char = peek().also { i++ }
        private fun expect(c: Char) {
            val n = next()
            if (n != c) fail("expected '$c' but found '$n'")
        }
        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
        private fun fail(msg: String): Nothing = throw IllegalArgumentException("FlatJson at $i: $msg")
    }
}
