package com.discountscreener.core.engine

import java.io.Reader

/**
 * Keep only the driver facts a reader can use from a SEC companyfacts document.
 * Skip the rest on the stream so the full tree never sits in RAM.
 *
 * Three cuts, in the order they save the most:
 * 1. A concept outside the driver policy never reaches the output.
 * 2. A fact that no reader accepts - a quarter, a form that is not a 10-K, a
 *    dimensional breakdown - never reaches the output either. Both readers
 *    apply that same filter after the parse, so the parse used to pay for rows
 *    it then threw away.
 * 3. A kept fact carries only the fields the readers ask for. `accn`, `fy` and
 *    `frame` are dead weight, and so are the concept `label` and `description`.
 */
object SecCompanyFactsSieve {
    private val FACT_FIELDS = setOf("end", "start", "val", "filed", "form", "fp", "segment")
    private const val ANNUAL_PERIOD = "FY"

    val defaultAllowedQnames: Set<String>
        get() = SecDriverNormalizationPolicy.retainedQnames + SecResidualFacts.retainedQnames

    val acceptedForms: Set<String>
        get() = SecDriverNormalizationPolicy.acceptedForms + SecResidualFacts.acceptedForms

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
        var chunk = StringBuilder()
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
                chunk.setLength(0)
                if (copyConcept(reader, chunk)) {
                    if (!first) out.append(',')
                    first = false
                    kept = true
                    out.append('"').append(key).append("\":").append(chunk)
                }
            } else {
                reader.skipValue()
            }
        }
        out.append('}')
        return kept
    }

    private fun copyConcept(reader: JsonStreamReader, out: StringBuilder): Boolean {
        reader.expect('{')
        var units = StringBuilder()
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
            if (key == "units" && !kept) {
                kept = copyUnits(reader, units)
            } else {
                reader.skipValue()
            }
        }
        if (!kept) return false
        out.append("{\"units\":{").append(units).append("}}")
        return true
    }

    private fun copyUnits(reader: JsonStreamReader, out: StringBuilder): Boolean {
        reader.expect('{')
        var first = true
        var kept = false
        var facts = StringBuilder()
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
            var unit = reader.readString()
            reader.skipWs()
            reader.expect(':')
            reader.skipWs()
            facts.setLength(0)
            if (copyFactArray(reader, facts)) {
                if (!first) out.append(',')
                first = false
                kept = true
                out.append('"').append(unit).append("\":[").append(facts).append(']')
            }
        }
        return kept
    }

    private fun copyFactArray(reader: JsonStreamReader, out: StringBuilder): Boolean {
        reader.skipWs()
        if (reader.peekChar() != '[') {
            reader.skipValue()
            return false
        }
        reader.expect('[')
        var first = true
        var kept = false
        var fact = StringBuilder()
        while (true) {
            reader.skipWs()
            if (reader.peekChar() == ']') {
                reader.nextChar()
                break
            }
            if (reader.peekChar() == ',') {
                reader.nextChar()
                reader.skipWs()
                if (reader.peekChar() == ']') {
                    reader.nextChar()
                    break
                }
            }
            fact.setLength(0)
            if (copyFact(reader, fact)) {
                if (!first) out.append(',')
                first = false
                kept = true
                out.append(fact)
            }
        }
        return kept
    }

    /**
     * One annual, consolidated, 10-K fact, cut down to the fields a reader asks for.
     *
     * A `segment` that holds a value marks a dimensional breakdown, and no reader wants it. A
     * `segment` that holds null stays in the output, because one reader treats the bare key as a
     * refusal and the other treats a null value as consolidated.
     */
    private fun copyFact(reader: JsonStreamReader, out: StringBuilder): Boolean {
        reader.skipWs()
        if (reader.peekChar() != '{') {
            reader.skipValue()
            return false
        }
        reader.expect('{')
        var fields = StringBuilder()
        var value = StringBuilder()
        var period: String? = null
        var form: String? = null
        var dimensional = false
        var first = true
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
            if (key !in FACT_FIELDS) {
                reader.skipValue()
                continue
            }
            value.setLength(0)
            reader.copyValue(value)
            when (key) {
                "fp" -> period = unquoted(value)
                "form" -> form = unquoted(value)
                "segment" -> dimensional = value.toString() != "null"
            }
            if (!first) fields.append(',')
            first = false
            fields.append('"').append(key).append("\":").append(value)
        }
        if (dimensional || period != ANNUAL_PERIOD || form !in acceptedForms) return false
        out.append('{').append(fields).append('}')
        return true
    }

    private fun unquoted(raw: StringBuilder): String? {
        if (raw.length < 2 || raw[0] != '"' || raw[raw.length - 1] != '"') return null
        return raw.substring(1, raw.length - 1)
    }
}

internal class JsonStreamReader(private val raw: Reader) {
    private val buffer = CharArray(BUFFER_CHARS)
    private val scratch = StringBuilder()
    private var length = 0
    private var at = 0

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
        scratch.setLength(0)
        while (true) {
            var char = nextChar()
            if (char == '"') return scratch.toString()
            if (char == '\\') {
                scratch.append(readEscape())
            } else {
                scratch.append(char)
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

    /**
     * The source is read in blocks, never one char at a time. A companyfacts file holds about four
     * million chars, and a per-char read on a network reader crosses a decoder lock every time.
     */
    private fun peek(): Int {
        if (at >= length && !fill()) return -1
        return buffer[at].code
    }

    private fun next(): Int {
        if (at >= length && !fill()) return -1
        return buffer[at++].code
    }

    private fun fill(): Boolean {
        at = 0
        length = 0
        while (true) {
            var read = raw.read(buffer, 0, buffer.size)
            if (read < 0) return false
            if (read > 0) {
                length = read
                return true
            }
        }
    }

    companion object {
        private const val BUFFER_CHARS = 16 * 1024
    }
}
