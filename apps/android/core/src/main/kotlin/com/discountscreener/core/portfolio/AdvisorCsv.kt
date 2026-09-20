package com.discountscreener.core.portfolio

import kotlin.math.abs
import kotlin.math.roundToLong

const val ADVISOR_CSV_POLICY_VERSION = "advisor-csv-import/3"
const val BOOK_AS_OF_META_KEY = "ds_advisor_book_as_of"
const val QTY_SCALE = 10_000L

enum class CsvKind {
    HoldingsSnapshot,
    TradesWindow,
    TradesLedger,
}

enum class RefuseReason {
    TradesWithoutBook,
    MissingBookAsOf,
    EmptyKeep,
    LedgerApplyUnsupported,
    Unreadable,
    AsOfUnparseable,
}

data class CsvTx(
    val symbol: String,
    val side: Side,
    val quantityShares: Double,
    val price: Double,
    val date: String,
)

enum class Side { Buy, Sell }

data class PortfolioLot(
    val symbol: String,
    val quantityTenThousandths: Long,
    val avgCostCents: Long,
    val openedAt: String?,
)

data class ParsedCsv(
    val txs: List<CsvTx>,
    val ignored: Int,
    val format: String,
    val kind: CsvKind,
    val asOf: String?,
    /** Rows that the file format tells us to skip, such as cash or non-trade types. */
    val expectedExclusions: Int = 0,
    /** Rows that fail required symbol or numeric parsing. */
    val parseFailures: Int = 0,
)

data class BookContext(
    val lots: List<PortfolioLot>,
    val bookAsOf: String?,
)

sealed class ImportPlan {
    data class ConfirmHoldingsReplace(
        val format: String,
        val asOf: String,
        val positions: List<PortfolioLot>,
        val remove: List<String>,
        val ignored: Int,
        val expectedExclusions: Int = 0,
        val parseFailures: Int = 0,
    ) : ImportPlan()

    data class ConfirmTradesMerge(
        val format: String,
        val asOf: String,
        val positions: List<PortfolioLot>,
        val remove: List<String>,
        val applied: Int,
        val skipped: Int,
        val ignored: Int,
        val nextBookAsOf: String,
        val expectedExclusions: Int = 0,
        val parseFailures: Int = 0,
    ) : ImportPlan()

    data class Refuse(
        val reason: RefuseReason,
        val format: String,
    ) : ImportPlan()
}

fun sharesToTenThousandths(shares: Double): Long =
    (shares * QTY_SCALE.toDouble()).roundToLong()

fun tenThousandthsToShares(qty: Long): Double = qty.toDouble() / QTY_SCALE.toDouble()

fun parseAdvisorCsv(text: String): ParsedCsv? {
    var clean = text.replace("\uFEFF", "")
    var lines = clean.split(Regex("\r?\n")).filter { it.trim().isNotEmpty() }
    if (lines.size < 2) return null
    lines = listOf(lines[0].replace("\uFEFF", "")) + lines.drop(1)
    return detectAndParse(lines)
}

fun planParsedCsv(text: String, ctx: BookContext): ImportPlan {
    var parsed = parseAdvisorCsv(text) ?: return ImportPlan.Refuse(RefuseReason.Unreadable, "unknown")
    return planAdvisorCsv(parsed, ctx)
}

fun detectAndParse(lines: List<String>): ParsedCsv? {
    var fmt = detectFormat(lines)
    return when (fmt) {
        "coinbase" -> ParsedCsv(emptyList(), 0, "Coinbase", CsvKind.TradesLedger, null)
        "schwab" -> ParsedCsv(emptyList(), 0, "Schwab", CsvKind.TradesLedger, null)
        "jpm_holdings" -> parseJpmHoldings(lines)
        "chase_trades" -> parseChaseTrades(lines)
        else -> ParsedCsv(emptyList(), 0, "genérico", CsvKind.TradesLedger, null)
    }
}

fun planAdvisorCsv(parsed: ParsedCsv, ctx: BookContext): ImportPlan {
    if (parsed.kind == CsvKind.TradesLedger) {
        return ImportPlan.Refuse(RefuseReason.LedgerApplyUnsupported, parsed.format)
    }
    if (parsed.kind == CsvKind.HoldingsSnapshot) {
        var asOf = parsed.asOf ?: return ImportPlan.Refuse(RefuseReason.AsOfUnparseable, parsed.format)
        if (parsed.txs.isEmpty()) {
            return ImportPlan.Refuse(RefuseReason.EmptyKeep, parsed.format)
        }
        var holdings = aggregateToPositions(parsed.txs)
        if (holdings.isEmpty()) {
            return ImportPlan.Refuse(RefuseReason.EmptyKeep, parsed.format)
        }
        var keep = holdings.map { it.symbol }.toSet()
        var remove = ctx.lots.map { it.symbol }.filter { it !in keep }.sorted()
        return ImportPlan.ConfirmHoldingsReplace(
            format = parsed.format,
            asOf = asOf,
            positions = holdings,
            remove = remove,
            ignored = parsed.ignored,
            expectedExclusions = parsed.expectedExclusions,
            parseFailures = parsed.parseFailures,
        )
    }
    if (ctx.lots.isEmpty()) {
        return ImportPlan.Refuse(RefuseReason.TradesWithoutBook, parsed.format)
    }
    var bookAsOf = ctx.bookAsOf?.takeIf { it.isNotBlank() }
        ?: return ImportPlan.Refuse(RefuseReason.MissingBookAsOf, parsed.format)
    var merged = mergeTradesOntoLots(ctx.lots, parsed.txs, bookAsOf)
    var mergedKeep = merged.positions.map { it.symbol }.toSet()
    var closed = ctx.lots.map { it.symbol }.filter { it !in mergedKeep }.sorted()
    return ImportPlan.ConfirmTradesMerge(
        format = parsed.format,
        asOf = bookAsOf,
        positions = merged.positions,
        remove = closed,
        applied = merged.applied,
        skipped = merged.skipped,
        ignored = parsed.ignored,
        nextBookAsOf = nextBookAsOf(bookAsOf, merged.appliedDates),
        expectedExclusions = parsed.expectedExclusions,
        parseFailures = parsed.parseFailures,
    )
}

fun nextBookAsOf(prior: String, appliedDates: List<String>): String {
    if (appliedDates.isEmpty()) return prior
    var latest = appliedDates.maxOrNull() ?: return prior
    return if (latest > prior) latest else prior
}

internal fun detectFormat(lines: List<String>): String {
    var head = lines.take(6).joinToString("\n").lowercase()
    if (head.contains("transaction type") && head.contains("quantity transacted")) return "coinbase"
    var first = lines[0].lowercase()
    if (first.contains("action") && first.contains("symbol") &&
        (first.contains("fees & comm") || first.contains("amount"))
    ) {
        return "schwab"
    }
    if (first.contains("asset class") && first.contains("unit cost") &&
        first.contains("ticker") && first.contains("quantity") && first.contains("as of")
    ) {
        return "jpm_holdings"
    }
    if (first.contains("trade date") && first.contains("price usd") && first.contains("type")) {
        return "chase_trades"
    }
    return "generic"
}

internal fun splitCsvLine(line: String, delim: Char = ','): List<String> {
    var out = ArrayList<String>()
    var cur = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        var ch = line[i]
        if (ch == '"') {
            if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                cur.append('"')
                i++
            } else {
                inQuotes = !inQuotes
            }
        } else if (ch == delim && !inQuotes) {
            out.add(cur.toString().trim())
            cur = StringBuilder()
        } else {
            cur.append(ch)
        }
        i++
    }
    out.add(cur.toString().trim())
    return out
}

internal fun normalizeUsNum(raw: String): Double {
    var s = raw.replace(Regex("[\$\\s\"]"), "")
    if (s.isEmpty()) return Double.NaN
    s = when {
        s.contains('.') && s.contains(',') -> s.replace(",", "")
        Regex("^-?\\d{1,3}(,\\d{3})+$").matches(s) -> s.replace(",", "")
        s.contains(',') && !s.contains('.') -> s.replace(",", ".")
        else -> s
    }
    return s.toDoubleOrNull() ?: Double.NaN
}

internal fun normalizeUsDate(raw: String): String {
    var s = raw.trim().replace("\"", "")
    if (s.isEmpty()) return ""
    if (s.matches(Regex("^\\d{4}-\\d{2}-\\d{2}.*"))) return s.take(10)
    var m = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})").find(s) ?: return ""
    var month = m.groupValues[1].padStart(2, '0')
    var day = m.groupValues[2].padStart(2, '0')
    var year = m.groupValues[3]
    return "$year-$month-$day"
}

private fun parseJpmHoldings(lines: List<String>): ParsedCsv? {
    var headers = splitCsvLine(lines[0]).map { it.lowercase().replace("\"", "") }
    var iClass = headers.indexOf("asset class")
    var iTicker = headers.indexOf("ticker")
    var iQty = headers.indexOf("quantity")
    var iUnit = headers.indexOf("unit cost")
    var iAcq = headers.indexOf("acquisition date")
    var iAsOf = headers.indexOf("as of")
    if (iTicker < 0 || iQty < 0 || iUnit < 0) return null
    var txs = ArrayList<CsvTx>()
    var ignored = 0
    var expectedExclusions = 0
    var parseFailures = 0
    var asOf: String? = null
    for (i in 1 until lines.size) {
        var cols = splitCsvLine(lines[i])
        var assetClass = cols.getOrElse(iClass) { "" }.replace("\"", "").lowercase()
        var symbol = cols.getOrElse(iTicker) { "" }.replace("\"", "").uppercase()
        if (iAsOf >= 0 && asOf == null) {
            var rowAsOf = normalizeUsDate(cols.getOrElse(iAsOf) { "" })
            if (rowAsOf.isNotEmpty()) asOf = rowAsOf
        }
        if (symbol.isEmpty() || symbol == "QACDS" || assetClass.startsWith("cash")) {
            ignored++
            expectedExclusions++
            continue
        }
        if (!symbol.matches(Regex("^[A-Z][A-Z0-9.]*$"))) {
            ignored++
            parseFailures++
            continue
        }
        var quantity = normalizeUsNum(cols.getOrElse(iQty) { "" })
        var price = normalizeUsNum(cols.getOrElse(iUnit) { "" })
        if (!quantity.isFinite() || quantity <= 0 || !price.isFinite() || price <= 0) {
            ignored++
            parseFailures++
            continue
        }
        var date = if (iAcq >= 0) normalizeUsDate(cols.getOrElse(iAcq) { "" }) else ""
        txs.add(CsvTx(symbol, Side.Buy, quantity, price, date))
    }
    return ParsedCsv(
        txs = txs,
        ignored = ignored,
        format = "J.P. Morgan",
        kind = CsvKind.HoldingsSnapshot,
        asOf = asOf,
        expectedExclusions = expectedExclusions,
        parseFailures = parseFailures,
    )
}

private fun parseChaseTrades(lines: List<String>): ParsedCsv? {
    var headers = splitCsvLine(lines[0]).map { it.lowercase().replace("\"", "") }
    var iDate = headers.indexOf("trade date")
    var iType = headers.indexOf("type")
    var iTicker = headers.indexOf("ticker")
    var iPrice = headers.indexOf("price usd")
    var iQty = headers.indexOf("quantity")
    if (iDate < 0 || iType < 0 || iTicker < 0 || iPrice < 0 || iQty < 0) return null
    var buy = setOf("buy", "reinvest")
    var sell = setOf("sell")
    var txs = ArrayList<CsvTx>()
    var ignored = 0
    var expectedExclusions = 0
    var parseFailures = 0
    for (i in 1 until lines.size) {
        var cols = splitCsvLine(lines[i])
        var type = cols.getOrElse(iType) { "" }.replace("\"", "").lowercase()
        var symbol = cols.getOrElse(iTicker) { "" }.replace("\"", "").uppercase()
        var side = when {
            type in buy -> Side.Buy
            type in sell -> Side.Sell
            else -> null
        }
        if (side == null || symbol.isEmpty() || !symbol.matches(Regex("^[A-Z][A-Z0-9.]*$"))) {
            ignored++
            if (side == null) {
                expectedExclusions++
            } else {
                parseFailures++
            }
            continue
        }
        var quantity = abs(normalizeUsNum(cols.getOrElse(iQty) { "" }))
        var price = normalizeUsNum(cols.getOrElse(iPrice) { "" })
        if (!quantity.isFinite() || quantity <= 0 || !price.isFinite() || price <= 0) {
            ignored++
            parseFailures++
            continue
        }
        var date = normalizeUsDate(cols.getOrElse(iDate) { "" })
        txs.add(CsvTx(symbol, side, quantity, price, date))
    }
    return ParsedCsv(
        txs = txs,
        ignored = ignored,
        format = "Chase",
        kind = CsvKind.TradesWindow,
        asOf = null,
        expectedExclusions = expectedExclusions,
        parseFailures = parseFailures,
    )
}

internal fun aggregateToPositions(txs: List<CsvTx>): List<PortfolioLot> {
    var sorted = txs.sortedWith { a, b ->
        when {
            a.date.isNotEmpty() && b.date.isNotEmpty() -> a.date.compareTo(b.date)
            else -> 0
        }
    }
    var acc = LinkedHashMap<String, Acc>()
    for (tx in sorted) {
        var cur = acc[tx.symbol] ?: Acc(0.0, 0.0, null)
        if (tx.side == Side.Buy) {
            var newQty = cur.qty + tx.quantityShares
            cur.avgCost = (cur.avgCost * cur.qty + tx.price * tx.quantityShares) / newQty
            if (cur.qty == 0.0) cur.openedAt = tx.date.ifEmpty { null }
            cur.qty = newQty
        } else {
            cur.qty -= tx.quantityShares
            if (cur.qty <= 0.000001) {
                cur.qty = 0.0
                cur.avgCost = 0.0
                cur.openedAt = null
            }
        }
        acc[tx.symbol] = cur
    }
    return acc.mapNotNull { (symbol, a) -> positionOf(symbol, a) }
}

data class MergeResult(
    val positions: List<PortfolioLot>,
    val applied: Int,
    val skipped: Int,
    val appliedDates: List<String>,
)

fun mergeTradesOntoLots(
    lots: List<PortfolioLot>,
    trades: List<CsvTx>,
    bookAsOf: String,
): MergeResult {
    var acc = LinkedHashMap<String, Acc>()
    for (lot in lots) {
        acc[lot.symbol] = Acc(
            qty = tenThousandthsToShares(lot.quantityTenThousandths),
            avgCost = lot.avgCostCents / 100.0,
            openedAt = lot.openedAt,
        )
    }
    var sorted = trades.sortedWith { a, b ->
        when {
            a.date.isNotEmpty() && b.date.isNotEmpty() -> a.date.compareTo(b.date)
            else -> 0
        }
    }
    var applied = 0
    var skipped = 0
    var appliedDates = ArrayList<String>()
    for (tx in sorted) {
        var cur = acc[tx.symbol] ?: Acc(0.0, 0.0, null)
        if (cur.openedAt == null && tx.side == Side.Buy && tx.date.isNotEmpty()) {
            cur.openedAt = tx.date
        }
        if (tx.date.isEmpty() || tx.date <= bookAsOf) {
            acc[tx.symbol] = cur
            continue
        }
        if (tx.side == Side.Buy) {
            var newQty = cur.qty + tx.quantityShares
            if (newQty <= 0) {
                skipped++
                continue
            }
            cur.avgCost = if (cur.qty > 0) {
                (cur.avgCost * cur.qty + tx.price * tx.quantityShares) / newQty
            } else {
                tx.price
            }
            cur.qty = newQty
            applied++
            appliedDates.add(tx.date)
        } else {
            if (tx.quantityShares > cur.qty + 0.000001) {
                skipped++
                acc[tx.symbol] = cur
                continue
            }
            cur.qty -= tx.quantityShares
            if (cur.qty <= 0.000001) {
                cur.qty = 0.0
                cur.avgCost = 0.0
                cur.openedAt = null
            }
            applied++
            appliedDates.add(tx.date)
        }
        acc[tx.symbol] = cur
    }
    var positions = acc.mapNotNull { (symbol, a) -> positionOf(symbol, a) }
    return MergeResult(positions, applied, skipped, appliedDates)
}

private class Acc(var qty: Double, var avgCost: Double, var openedAt: String?)

private fun positionOf(symbol: String, acc: Acc): PortfolioLot? {
    if (!acc.qty.isFinite() || !acc.avgCost.isFinite()) return null
    var quantityTenThousandths = sharesToTenThousandths(acc.qty)
    var avgCostCents = (acc.avgCost * 100.0).roundToLong()
    if (quantityTenThousandths <= 0L || avgCostCents <= 0L) return null
    return PortfolioLot(
        symbol = symbol,
        quantityTenThousandths = quantityTenThousandths,
        avgCostCents = avgCostCents,
        openedAt = acc.openedAt,
    )
}
