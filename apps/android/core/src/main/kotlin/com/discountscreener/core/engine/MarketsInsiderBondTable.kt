package com.discountscreener.core.engine

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val US_DATE = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US)
private val ROW_RE = Regex("""<tr class="table__tr">(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL))
private val CELL_RE = Regex("""<td[^>]*>(.*?)</td>""", setOf(RegexOption.DOT_MATCHES_ALL))
private val TAG_RE = Regex("""<[^>]+>""")
private val OPTION_RE = Regex(
    """<option value="(\d+)"[^>]*>([^<]*)</option>""",
    setOf(RegexOption.IGNORE_CASE),
)
private val SPACE_RE = Regex("""\s+""")
private val PAREN_RE = Regex("""\([^)]*\)""")
private val NON_TOKEN_RE = Regex("""[^a-z0-9]+""")
private val LEGAL_NAME_TOKENS = setOf(
    "inc", "corp", "corporation", "ltd", "llc", "co", "company", "plc",
    "sa", "ag", "the", "and",
)

fun parseMarketsInsiderBorrowerId(html: String, companyName: String): String? {
    var wanted = unescapeHtml(companyName).trim()
    if (wanted.isBlank()) return null
    var options = OPTION_RE.findAll(html).map { match ->
        match.groupValues[1] to unescapeHtml(match.groupValues[2]).trim()
    }.toList()
    var exact = options.filter { it.second.equals(wanted, ignoreCase = true) }
        .map { it.first }
        .distinct()
    if (exact.size == 1) return exact.single()
    if (exact.size > 1) return null
    var key = normalizeIssuerName(wanted)
    if (key.isBlank()) return null
    var matched = options.filter { normalizeIssuerName(it.second) == key }
        .map { it.first }
        .distinct()
    return matched.singleOrNull()
}

fun parseMarketsInsiderBondTable(html: String): List<IssuerInstrumentQuote> {
    return ROW_RE.findAll(html).mapNotNull { row ->
        var cells = CELL_RE.findAll(row.groupValues[1]).map { cell ->
            flattenHtml(cell.groupValues[1])
        }.toList()
        if (cells.size < 6) return@mapNotNull null
        var yieldBps = parsePercentBps(cells[3]) ?: return@mapNotNull null
        var maturity = parseUsDate(cells[5]) ?: return@mapNotNull null
        IssuerInstrumentQuote(
            yieldBps = yieldBps,
            maturityDate = maturity,
            currency = cells[1].ifBlank { null },
        )
    }.toList()
}

internal fun normalizeIssuerName(raw: String): String {
    var cleaned = unescapeHtml(raw).lowercase()
    cleaned = cleaned.replace("&", " ")
    cleaned = PAREN_RE.replace(cleaned, " ")
    var tokens = NON_TOKEN_RE.replace(cleaned, " ")
        .split(" ")
        .filter { it.isNotBlank() && it !in LEGAL_NAME_TOKENS }
    return tokens.joinToString(" ")
}

private fun parsePercentBps(raw: String): Int? {
    var token = raw.trim().removeSuffix("%").trim()
    if (token.isEmpty() || token == "-") return null
    var pct = token.toDoubleOrNull() ?: return null
    if (!pct.isFinite()) return null
    return (pct * 100.0).roundToInt().takeIf { it in 0..5_000 }
}

private fun parseUsDate(raw: String): String? {
    var parsed = runCatching { LocalDate.parse(raw.trim(), US_DATE) }.getOrNull() ?: return null
    return parsed.toString()
}

private fun flattenHtml(raw: String): String =
    unescapeHtml(TAG_RE.replace(raw, " ")).replace(SPACE_RE, " ").trim()

private fun unescapeHtml(raw: String): String =
    raw.replace("&amp;", "&")
        .replace("&nbsp;", " ")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
