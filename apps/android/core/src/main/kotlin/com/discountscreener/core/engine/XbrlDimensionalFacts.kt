package com.discountscreener.core.engine

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
    fun parse(xml: String): List<TaggedFact> {
        var contexts = linkedMapOf<String, Context>()
        var contextRe = Regex("""<context id="([^"]+)">(.*?)</context>""", RegexOption.DOT_MATCHES_ALL)
        for (match in contextRe.findAll(xml)) {
            var body = match.groupValues[2]
            var members = Regex("""<xbrldi:explicitMember[^>]*>([^<]+)</xbrldi:explicitMember>""")
                .findAll(body)
                .map { it.groupValues[1].trim() }
                .toList()
            var instant = Regex("""<instant>([^<]+)</instant>""").find(body)?.groupValues?.get(1)
            var start = Regex("""<startDate>([^<]+)</startDate>""").find(body)?.groupValues?.get(1)
            var end = Regex("""<endDate>([^<]+)</endDate>""").find(body)?.groupValues?.get(1)
            contexts[match.groupValues[1]] = Context(members, instant, start, end ?: instant)
        }
        var facts = mutableListOf<TaggedFact>()
        var factRe = Regex("""<([A-Za-z0-9_-]+:[A-Za-z0-9_-]+)([^>]*)>([^<]+)</""")
        for (match in factRe.findAll(xml)) {
            var attrs = match.groupValues[2]
            var contextId = Regex("""contextRef="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                ?: continue
            var context = contexts[contextId] ?: continue
            var end = context.end ?: continue
            var value = match.groupValues[3].trim().toDoubleOrNull() ?: continue
            var unit = Regex("""unitRef="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            facts += TaggedFact(
                concept = match.groupValues[1],
                periodEnd = end,
                periodStart = context.start,
                value = value,
                members = context.members,
                unit = unit,
            )
        }
        return facts
    }

    private data class Context(
        val members: List<String>,
        val instant: String?,
        val start: String?,
        val end: String?,
    )
}
