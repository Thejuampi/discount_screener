package com.discountscreener.core.engine

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val US_DATE = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US)
private const val OPTION_OPEN = "<option value=\""
private const val OPTION_CLOSE = "</option>"
private const val ROW_OPEN = "<tr class=\"table__tr\">"
private const val ROW_CLOSE = "</tr>"
private const val CELL_OPEN = "<td"
private const val CELL_CLOSE = "</td>"
private val LEGAL_NAME_TOKENS = setOf(
    "inc", "corp", "corporation", "ltd", "llc", "co", "company", "plc",
    "sa", "ag", "the", "and",
)

data class MarketsInsiderBorrower(val id: String, val name: String)

fun parseMarketsInsiderBorrowerOptions(html: String): List<MarketsInsiderBorrower> {
    var options = mutableListOf<MarketsInsiderBorrower>()
    var from = 0
    while (true) {
        var start = html.indexOf(OPTION_OPEN, from, ignoreCase = true)
        if (start < 0) break
        from = start + 1
        var idStart = start + OPTION_OPEN.length
        var idEnd = idStart
        while (idEnd < html.length && html[idEnd] in '0'..'9') idEnd++
        if (idEnd == idStart || idEnd >= html.length || html[idEnd] != '"') continue
        var tagClose = html.indexOf('>', idEnd + 1)
        if (tagClose < 0) break
        var textEnd = html.indexOf('<', tagClose + 1)
        if (textEnd < 0) break
        if (!html.startsWith(OPTION_CLOSE, textEnd, ignoreCase = true)) continue
        var name = unescapeHtml(html.substring(tagClose + 1, textEnd)).trim()
        options += MarketsInsiderBorrower(html.substring(idStart, idEnd), name)
        from = textEnd + OPTION_CLOSE.length
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
    var from = 0
    while (true) {
        var rowStart = html.indexOf(ROW_OPEN, from)
        if (rowStart < 0) break
        var bodyStart = rowStart + ROW_OPEN.length
        var rowEnd = html.indexOf(ROW_CLOSE, bodyStart)
        if (rowEnd < 0) break
        from = rowEnd + ROW_CLOSE.length
        var cells = parseCells(html, bodyStart, rowEnd)
        if (cells.size < 6) continue
        var yieldBps = parsePercentBps(cells[3]) ?: continue
        var maturity = parseUsDate(cells[5]) ?: continue
        quotes += IssuerInstrumentQuote(
            yieldBps = yieldBps,
            maturityDate = maturity,
            currency = cells[1].ifBlank { null },
        )
    }
    return quotes
}

private fun parseCells(html: String, rowStart: Int, rowEnd: Int): List<String> {
    var cells = mutableListOf<String>()
    var from = rowStart
    while (true) {
        var cellStart = html.indexOf(CELL_OPEN, from)
        if (cellStart < 0 || cellStart >= rowEnd) break
        var open = html.indexOf('>', cellStart + CELL_OPEN.length)
        if (open < 0 || open >= rowEnd) break
        var close = html.indexOf(CELL_CLOSE, open + 1)
        if (close < 0 || close >= rowEnd) break
        cells += flattenHtml(html.substring(open + 1, close))
        from = close + CELL_CLOSE.length
    }
    return cells
}

internal fun normalizeIssuerName(raw: String): String {
    var cleaned = unescapeHtml(raw).lowercase()
    cleaned = cleaned.replace("&", " ")
    cleaned = stripParens(cleaned)
    var tokens = mutableListOf<String>()
    var token = StringBuilder()
    for (c in cleaned) {
        if (c in 'a'..'z' || c in '0'..'9') {
            token.append(c)
        } else if (token.isNotEmpty()) {
            tokens += token.toString()
            token = StringBuilder()
        }
    }
    if (token.isNotEmpty()) tokens += token.toString()
    return tokens.filter { it !in LEGAL_NAME_TOKENS }.joinToString(" ")
}

private fun stripParens(raw: String): String {
    var out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        if (raw[i] == '(') {
            var close = raw.indexOf(')', i + 1)
            if (close >= 0) {
                out.append(' ')
                i = close + 1
                continue
            }
        }
        out.append(raw[i])
        i++
    }
    return out.toString()
}

private fun parsePercentBps(raw: String): Int? {
    var token = raw.trim().removeSuffix("%").trim()
    if (token.isEmpty() || token == "-") return null
    var pct = token.plainDoubleOrNull() ?: return null
    if (!pct.isFinite()) return null
    return (pct * 100.0).roundToInt().takeIf { it in 0..5_000 }
}

private fun parseUsDate(raw: String): String? {
    var parsed = runCatching { LocalDate.parse(raw.trim(), US_DATE) }.getOrNull() ?: return null
    return parsed.toString()
}

private fun flattenHtml(raw: String): String = collapseSpaces(unescapeHtml(stripTags(raw)))

private fun stripTags(raw: String): String {
    var out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        if (raw[i] == '<') {
            var close = raw.indexOf('>', i + 1)
            if (close > i + 1) {
                out.append(' ')
                i = close + 1
                continue
            }
        }
        out.append(raw[i])
        i++
    }
    return out.toString()
}

private fun collapseSpaces(raw: String): String {
    var out = StringBuilder(raw.length)
    var pendingSpace = false
    for (c in raw) {
        if (c.isWhitespace()) {
            pendingSpace = true
        } else {
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.append(c)
        }
    }
    return out.toString()
}

private fun unescapeHtml(raw: String): String =
    raw.replace("&amp;", "&")
        .replace("&nbsp;", " ")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
