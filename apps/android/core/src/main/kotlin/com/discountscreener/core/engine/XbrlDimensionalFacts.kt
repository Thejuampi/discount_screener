package com.discountscreener.core.engine

import java.io.Reader

data class TaggedFact(
    val concept: String,
    val periodEnd: String,
    val periodStart: String?,
    val value: Double,
    val members: List<String>,
    val unit: String?,
)

/**
 * Read annual facts and their explicit members from an instance document.
 * Company-facts JSON drops those members.
 */
object XbrlDimensionalFacts {
    private const val CONTEXT_OPEN = "<context id=\""
    private const val CONTEXT_CLOSE = "</context>"
    private const val MEMBER_OPEN = "<xbrldi:explicitMember"
    private const val MEMBER_CLOSE = "</xbrldi:explicitMember>"

    // A 10-K instance document is tens to hundreds of megabytes. Only a bounded scan window is
    // ever held in memory; the margin must cover the largest single match (a context block or one
    // fact element), never the whole document.
    private const val CONTEXT_MARGIN = 256 * 1024
    private const val FACT_MARGIN = 16 * 1024
    private const val CARRY_CAP = 8 * 1024 * 1024
    private const val CHUNK_CHARS = 256 * 1024

    fun parse(xml: String): List<TaggedFact> = parse { xml.reader() }

    fun parse(openReader: () -> Reader): List<TaggedFact> {
        var contexts = linkedMapOf<String, Context>()
        openReader().use { reader ->
            scanMatches(reader, CONTEXT_MARGIN, ::findContext) { match ->
                var body = match.groups[1]
                contexts[match.groups[0]] = Context(
                    members = parseMembers(body),
                    start = tagValue(body, "startDate"),
                    end = tagValue(body, "endDate") ?: tagValue(body, "instant"),
                )
            }
        }
        var facts = mutableListOf<TaggedFact>()
        openReader().use { reader ->
            scanMatches(reader, FACT_MARGIN, ::findFact) { match ->
                var attrs = match.groups[1]
                var contextId = attrValue(attrs, "contextRef") ?: return@scanMatches
                var context = contexts[contextId] ?: return@scanMatches
                var end = context.end ?: return@scanMatches
                var value = match.groups[2].trim().plainDoubleOrNull() ?: return@scanMatches
                facts += TaggedFact(
                    concept = match.groups[0],
                    periodEnd = end,
                    periodStart = context.start,
                    value = value,
                    members = context.members,
                    unit = attrValue(attrs, "unitRef"),
                )
            }
        }
        return facts
    }

    private class Match(val start: Int, val end: Int, val groups: List<String>)

    // A context block: <context id="ID">BODY</context>. Groups: id, body.
    private fun findContext(text: String, fromIndex: Int): Match? {
        var from = fromIndex
        while (true) {
            var start = text.indexOf(CONTEXT_OPEN, from)
            if (start < 0) return null
            from = start + 1
            var idStart = start + CONTEXT_OPEN.length
            var idEnd = text.indexOf('"', idStart)
            if (idEnd < 0) return null
            if (idEnd == idStart) continue
            if (idEnd + 1 >= text.length || text[idEnd + 1] != '>') continue
            var bodyStart = idEnd + 2
            var close = text.indexOf(CONTEXT_CLOSE, bodyStart)
            if (close < 0) return null
            return Match(
                start = start,
                end = close + CONTEXT_CLOSE.length,
                groups = listOf(text.substring(idStart, idEnd), text.substring(bodyStart, close)),
            )
        }
    }

    // A fact element: <prefix:Name ATTRS>VALUE</ — a namespaced element with text content.
    // Groups: concept, attrs, value.
    private fun findFact(text: String, fromIndex: Int): Match? {
        var from = fromIndex
        while (true) {
            var start = text.indexOf('<', from)
            if (start < 0) return null
            from = start + 1
            var p = start + 1
            var prefixStart = p
            while (p < text.length && isConceptChar(text[p])) p++
            if (p == prefixStart || p >= text.length || text[p] != ':') continue
            p++
            var localStart = p
            while (p < text.length && isConceptChar(text[p])) p++
            if (p == localStart) continue
            var concept = text.substring(start + 1, p)
            var open = text.indexOf('>', p)
            if (open < 0) return null
            var valueEnd = text.indexOf('<', open + 1)
            if (valueEnd < 0) return null
            if (valueEnd == open + 1) continue
            if (valueEnd + 1 >= text.length || text[valueEnd + 1] != '/') continue
            return Match(
                start = start,
                end = valueEnd + 2,
                groups = listOf(concept, text.substring(p, open), text.substring(open + 1, valueEnd)),
            )
        }
    }

    private fun isConceptChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'

    private fun parseMembers(body: String): List<String> {
        var members = mutableListOf<String>()
        var from = 0
        while (true) {
            var start = body.indexOf(MEMBER_OPEN, from)
            if (start < 0) break
            from = start + 1
            var open = body.indexOf('>', start + MEMBER_OPEN.length)
            if (open < 0) break
            var textEnd = body.indexOf('<', open + 1)
            if (textEnd < 0) break
            if (textEnd == open + 1) continue
            if (!body.startsWith(MEMBER_CLOSE, textEnd)) continue
            members += body.substring(open + 1, textEnd).trim()
            from = textEnd + MEMBER_CLOSE.length
        }
        return members
    }

    // First <tag>VALUE</tag> in body, or null.
    private fun tagValue(body: String, tag: String): String? {
        var openTag = "<$tag>"
        var closeTag = "</$tag>"
        var from = 0
        while (true) {
            var start = body.indexOf(openTag, from)
            if (start < 0) return null
            from = start + 1
            var valueStart = start + openTag.length
            var valueEnd = body.indexOf('<', valueStart)
            if (valueEnd < 0) return null
            if (valueEnd == valueStart) continue
            if (!body.startsWith(closeTag, valueEnd)) continue
            return body.substring(valueStart, valueEnd)
        }
    }

    // First name="VALUE" in attrs, or null.
    private fun attrValue(attrs: String, name: String): String? {
        var marker = "$name=\""
        var from = 0
        while (true) {
            var start = attrs.indexOf(marker, from)
            if (start < 0) return null
            from = start + 1
            var valueStart = start + marker.length
            var valueEnd = attrs.indexOf('"', valueStart)
            if (valueEnd < 0) return null
            if (valueEnd == valueStart) continue
            return attrs.substring(valueStart, valueEnd)
        }
    }

    // Stream the reader in chunks and scan a sliding window. A match is emitted only when it ends
    // before the margin tail, so a match cut by a chunk boundary is re-found complete on the next
    // round. Emitted text is dropped; memory stays bounded by the margin.
    private fun scanMatches(
        reader: Reader,
        margin: Int,
        find: (String, Int) -> Match?,
        onMatch: (Match) -> Unit,
    ) {
        var buf = CharArray(CHUNK_CHARS)
        var carry = StringBuilder()
        while (true) {
            var n = reader.read(buf)
            if (n < 0) break
            carry.append(buf, 0, n)
            if (carry.length <= margin) continue
            var text = carry.toString()
            var cut = text.length - margin
            var keepFrom = cut
            var at = 0
            while (true) {
                var match = find(text, at) ?: break
                if (match.end <= cut) {
                    onMatch(match)
                    at = match.end
                } else {
                    keepFrom = minOf(keepFrom, match.start)
                    break
                }
            }
            if (text.length - keepFrom > CARRY_CAP) keepFrom = cut
            carry = StringBuilder(text.substring(keepFrom))
        }
        var tail = carry.toString()
        var at = 0
        while (true) {
            var match = find(tail, at) ?: break
            onMatch(match)
            at = match.end
        }
    }

    private data class Context(
        val members: List<String>,
        val start: String?,
        val end: String?,
    )
}
