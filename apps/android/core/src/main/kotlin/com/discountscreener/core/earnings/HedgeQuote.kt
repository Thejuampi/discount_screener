package com.discountscreener.core.earnings

import kotlin.math.roundToInt

data class HedgeQuote(
    val protectivePutCostBps: Int,
    val putSpreadCostBps: Int?,
    val longStrike: Double,
    val shortStrike: Double?,
)

fun hedgeQuoteOf(rows: List<ChainRow>, move: ImpliedMove, forward: Double): HedgeQuote? {
    if (!forward.isFinite() || forward <= 0.0) return null
    var long = rows.firstOrNull { it.strike == move.strike } ?: return null
    var longMid = midOf(long.put) ?: return null
    var short = rows
        .mapNotNull { row ->
            if (row.strike <= 0.0 || row.strike >= move.strike) return@mapNotNull null
            var mid = midOf(row.put) ?: return@mapNotNull null
            if (mid >= longMid) return@mapNotNull null
            row to mid
        }
        .maxByOrNull { it.first.strike }
        ?.first
    var spread = short?.let { midOf(it.put) }?.let { longMid - it }?.takeIf { it > 0.0 }
    return HedgeQuote(
        protectivePutCostBps = (longMid / forward * 10_000.0).roundToInt(),
        putSpreadCostBps = spread?.let { (it / forward * 10_000.0).roundToInt() },
        longStrike = move.strike,
        shortStrike = spread?.let { short?.strike },
    )
}
