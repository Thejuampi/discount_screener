package com.discountscreener.core.engine

import java.io.Reader
import java.util.regex.Matcher

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
    private val contextRe = Regex("""<context id="([^"]+)">(.*?)</context>""", RegexOption.DOT_MATCHES_ALL)
    private val memberRe = Regex("""<xbrldi:explicitMember[^>]*>([^<]+)</xbrldi:explicitMember>""")
    private val instantRe = Regex("""<instant>([^<]+)</instant>""")
    private val startRe = Regex("""<startDate>([^<]+)</startDate>""")
    private val endRe = Regex("""<endDate>([^<]+)</endDate>""")
    private val factRe = Regex("""<([A-Za-z0-9_-]+:[A-Za-z0-9_-]+)([^>]*)>([^<]+)</""")
    private val contextRefRe = Regex("""contextRef="([^"]+)"""")
    private val unitRefRe = Regex("""unitRef="([^"]+)"""")

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
            scanMatches(reader, contextRe, CONTEXT_MARGIN) { match ->
                var body = match.group(2)
                var members = mutableListOf<String>()
                memberRe.forEachMatch(body) { member ->
                    members += member.group(1).trim()
                }
                var instant = instantRe.find(body)?.groupValues?.get(1)
                var start = startRe.find(body)?.groupValues?.get(1)
                var end = endRe.find(body)?.groupValues?.get(1)
                contexts[match.group(1)] = Context(members, instant, start, end ?: instant)
            }
        }
        var facts = mutableListOf<TaggedFact>()
        openReader().use { reader ->
            scanMatches(reader, factRe, FACT_MARGIN) { match ->
                var attrs = match.group(2)
                var contextId = contextRefRe.find(attrs)?.groupValues?.get(1)
                    ?: return@scanMatches
                var context = contexts[contextId] ?: return@scanMatches
                var end = context.end ?: return@scanMatches
                var value = match.group(3).trim().toDoubleOrNull() ?: return@scanMatches
                var unit = unitRefRe.find(attrs)?.groupValues?.get(1)
                facts += TaggedFact(
                    concept = match.group(1),
                    periodEnd = end,
                    periodStart = context.start,
                    value = value,
                    members = context.members,
                    unit = unit,
                )
            }
        }
        return facts
    }

    // Stream the reader in chunks and apply the regex over a sliding window. A match is emitted
    // only when it ends before the margin tail, so a match cut by a chunk boundary is re-found
    // complete on the next round. Emitted text is dropped; memory stays bounded by the margin.
    // One Matcher scans each window: see [forEachMatch] for why per-match Matchers are poison here.
    private fun scanMatches(
        reader: Reader,
        regex: Regex,
        margin: Int,
        onMatch: (Matcher) -> Unit,
    ) {
        var pattern = regex.toPattern()
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
            var matcher = pattern.matcher(text)
            while (matcher.find()) {
                if (matcher.end() <= cut) {
                    onMatch(matcher)
                } else {
                    keepFrom = minOf(keepFrom, matcher.start())
                    break
                }
            }
            if (text.length - keepFrom > CARRY_CAP) keepFrom = cut
            carry = StringBuilder(text.substring(keepFrom))
        }
        var tail = carry.toString()
        var matcher = pattern.matcher(tail)
        while (matcher.find()) {
            onMatch(matcher)
        }
    }

    private data class Context(
        val members: List<String>,
        val instant: String?,
        val start: String?,
        val end: String?,
    )
}
