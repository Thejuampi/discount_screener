package com.discountscreener.core.engine

import java.io.Reader

/**
 * Keep only driver QNames from a SEC companyfacts document.
 * Skip the rest on the stream so the full tree never sits in RAM.
 */
object SecCompanyFactsSieve {
    val defaultAllowedQnames: Set<String>
        get() = SecDriverNormalizationPolicy.retainedQnames + SecResidualFacts.retainedQnames

    fun sieve(
        input: Reader,
        allowedQnames: Set<String> = defaultAllowedQnames,
    ): String {
        var reader = JsonStreamReader(input)
        reader.skipWs()
        reader.expect('{')
        var out = StringBuilder()
        out.append("{\"facts\":{")
        while (true) {
            reader.skipWs()
            if (reader.peekChar() == '}') {
                reader.nextChar()
                break
            }
            if (reader.peekChar() == ',') {
                reader.nextChar()
                reader.skipWs()
                if (reader.peekChar() == '}') {
                    reader.nextChar()
                    break
                }
            }
            var key = reader.readString()
            reader.skipWs()
            reader.expect(':')
            reader.skipWs()
            if (key == "facts") {
                copyFacts(reader, out, allowedQnames)
            } else {
                reader.skipValue()
            }
        }
        out.append("}}")
        return out.toString()
    }

    private fun copyFacts(
        reader: JsonStreamReader,
        out: StringBuilder,
        allowedQnames: Set<String>,
    ) {
        reader.expect('{')
        var firstTaxonomy = true
        while (true) {
            reader.skipWs()
            if (reader.peekChar() == '}') {
                reader.nextChar()
                break
            }
            if (reader.peekChar() == ',') {
                reader.nextChar()
                reader.skipWs()
                if (reader.peekChar() == '}') {
                    reader.nextChar()
                    break
                }
            }
            var key = reader.readString()
            reader.skipWs()
            reader.expect(':')
            reader.skipWs()
            if (key == "us-gaap" || key == "dei") {
                var chunk = StringBuilder()
                var kept = copyTaxonomy(reader, chunk, key, allowedQnames)
                if (kept) {
                    if (!firstTaxonomy) out.append(',')
                    firstTaxonomy = false
                    out.append(chunk)
                }
            } else {
                reader.skipValue()
            }
        }
    }

    private fun copyTaxonomy(
        reader: JsonStreamReader,
        out: StringBuilder,
        taxonomy: String,
        allowedQnames: Set<String>,
    ): Boolean {
        reader.expect('{')
        out.append('"').append(taxonomy).append("\":{")
        var first = true
        var kept = false
        while (true) {
            reader.skipWs()
            if (reader.peekChar() == '}') {
                reader.nextChar()
                break
            }
            if (reader.peekChar() == ',') {
                reader.nextChar()
                reader.skipWs()
                if (reader.peekChar() == '}') {
                    reader.nextChar()
                    break
                }
            }
            var key = reader.readString()
            reader.skipWs()
            reader.expect(':')
            reader.skipWs()
            if (key in allowedQnames) {
                if (!first) out.append(',')
                first = false
                kept = true
                out.append('"').append(key).append("\":")
                reader.copyValue(out)
            } else {
                reader.skipValue()
            }
        }
        out.append('}')
        return kept
    }
}

internal class JsonStreamReader(private val raw: Reader) {
    private var peeked = UNREAD

    fun peekChar(): Char {
        var code = peek()
        if (code < 0) error("unexpected end of companyfacts")
        return code.toChar()
    }

    fun nextChar(): Char {
        var code = next()
        if (code < 0) error("unexpected end of companyfacts")
        return code.toChar()
    }

    fun skipWs() {
        while (true) {
            var code = peek()
            if (code < 0) return
            var char = code.toChar()
            if (char != ' ' && char != '\n' && char != '\r' && char != '\t') return
            next()
        }
    }

    fun expect(wanted: Char) {
        skipWs()
        var got = nextChar()
        if (got != wanted) error("expected '$wanted' in companyfacts, got '$got'")
    }

    fun readString(): String {
        skipWs()
        expectQuote()
        var out = StringBuilder()
        while (true) {
            var char = nextChar()
            if (char == '"') return out.toString()
            if (char == '\\') {
                out.append(readEscape())
            } else {
                out.append(char)
            }
        }
    }

    fun skipValue() {
        skipWs()
        when (peekChar()) {
            '"' -> skipString()
            '{' -> skipObject()
            '[' -> skipArray()
            else -> skipLiteral()
        }
    }

    fun copyValue(out: StringBuilder) {
        skipWs()
        when (peekChar()) {
            '"' -> copyString(out)
            '{' -> copyObject(out)
            '[' -> copyArray(out)
            else -> copyLiteral(out)
        }
    }

    private fun skipString() {
        expectQuote()
        while (true) {
            var char = nextChar()
            if (char == '"') return
            if (char == '\\') nextChar()
        }
    }

    private fun copyString(out: StringBuilder) {
        expectQuote()
        out.append('"')
        while (true) {
            var char = nextChar()
            out.append(char)
            if (char == '"') return
            if (char == '\\') out.append(nextChar())
        }
    }

    private fun skipObject() {
        expect('{')
        while (true) {
            skipWs()
            if (peekChar() == '}') {
                nextChar()
                return
            }
            if (peekChar() == ',') {
                nextChar()
                skipWs()
                if (peekChar() == '}') {
                    nextChar()
                    return
                }
            }
            skipString()
            skipWs()
            expect(':')
            skipValue()
        }
    }

    private fun copyObject(out: StringBuilder) {
        expect('{')
        out.append('{')
        var first = true
        while (true) {
            skipWs()
            if (peekChar() == '}') {
                nextChar()
                out.append('}')
                return
            }
            if (peekChar() == ',') {
                nextChar()
                skipWs()
                if (peekChar() == '}') {
                    nextChar()
                    out.append('}')
                    return
                }
            }
            if (!first) out.append(',')
            first = false
            copyString(out)
            skipWs()
            expect(':')
            out.append(':')
            copyValue(out)
        }
    }

    private fun skipArray() {
        expect('[')
        while (true) {
            skipWs()
            if (peekChar() == ']') {
                nextChar()
                return
            }
            if (peekChar() == ',') {
                nextChar()
                skipWs()
                if (peekChar() == ']') {
                    nextChar()
                    return
                }
            }
            skipValue()
        }
    }

    private fun copyArray(out: StringBuilder) {
        expect('[')
        out.append('[')
        var first = true
        while (true) {
            skipWs()
            if (peekChar() == ']') {
                nextChar()
                out.append(']')
                return
            }
            if (peekChar() == ',') {
                nextChar()
                skipWs()
                if (peekChar() == ']') {
                    nextChar()
                    out.append(']')
                    return
                }
            }
            if (!first) out.append(',')
            first = false
            copyValue(out)
        }
    }

    private fun skipLiteral() {
        while (true) {
            var code = peek()
            if (code < 0) return
            var char = code.toChar()
            if (char.isWhitespace() || char == ',' || char == '}' || char == ']') return
            next()
        }
    }

    private fun copyLiteral(out: StringBuilder) {
        while (true) {
            var code = peek()
            if (code < 0) return
            var char = code.toChar()
            if (char.isWhitespace() || char == ',' || char == '}' || char == ']') return
            out.append(nextChar())
        }
    }

    private fun expectQuote() {
        var got = nextChar()
        if (got != '"') error("expected string in companyfacts, got '$got'")
    }

    private fun readEscape(): Char = when (val char = nextChar()) {
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'b' -> '\b'
        'f' -> '\u000c'
        'u' -> {
            var hex = CharArray(4) { nextChar() }.concatToString()
            hex.toInt(16).toChar()
        }
        else -> char
    }

    private fun peek(): Int {
        if (peeked == UNREAD) peeked = raw.read()
        return peeked
    }

    private fun next(): Int {
        if (peeked != UNREAD) {
            var value = peeked
            peeked = UNREAD
            return value
        }
        return raw.read()
    }

    companion object {
        private const val UNREAD = -2
    }
}
