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

data class MarketsInsiderBorrower(val id: String, val name: String)

fun parseMarketsInsiderBorrowerOptions(html: String): List<MarketsInsiderBorrower> {
    var options = mutableListOf<MarketsInsiderBorrower>()
    OPTION_RE.forEachMatch(html) { match ->
        options += MarketsInsiderBorrower(match.group(1), unescapeHtml(match.group(2)).trim())
    }
    return options
}

fun selectMarketsInsiderBorrowerId(
    options: List<MarketsInsiderBorrower>,
    companyName: String,
): String? {
    var wanted = unescapeHtml(companyName).trim()
    if (wanted.isBlank()) return null
    var exact = options.filter { it.name.equals(wanted, ignoreCase = true) }
        .map { it.id }
        .distinct()
    if (exact.size == 1) return exact.single()
    if (exact.size > 1) return null
    var key = normalizeIssuerName(wanted)
    if (key.isBlank()) return null
    var matched = options.filter { normalizeIssuerName(it.name) == key }
        .map { it.id }
        .distinct()
    return matched.singleOrNull()
}

fun parseMarketsInsiderBorrowerId(html: String, companyName: String): String? =
    selectMarketsInsiderBorrowerId(parseMarketsInsiderBorrowerOptions(html), companyName)

fun parseMarketsInsiderBondTable(html: String): List<IssuerInstrumentQuote> {
    var quotes = mutableListOf<IssuerInstrumentQuote>()
    ROW_RE.forEachMatch(html) { row ->
        var body = row.group(1)
        var cells = mutableListOf<String>()
        CELL_RE.forEachMatch(body) { cell ->
            cells += flattenHtml(cell.group(1))
        }
        if (cells.size < 6) return@forEachMatch
        var yieldBps = parsePercentBps(cells[3]) ?: return@forEachMatch
        var maturity = parseUsDate(cells[5]) ?: return@forEachMatch
        quotes += IssuerInstrumentQuote(
            yieldBps = yieldBps,
            maturityDate = maturity,
            currency = cells[1].ifBlank { null },
        )
    }
    return quotes
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
