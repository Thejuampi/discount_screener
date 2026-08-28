package com.discountscreener.core.earnings

import kotlin.math.abs
import kotlin.math.roundToInt

const val PUT_SPREAD_WIDTH = 0.05

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
    var target = move.strike * (1.0 - PUT_SPREAD_WIDTH)
    var short = rows
        .filter { it.strike > 0.0 && it.strike < move.strike }
        .minWithOrNull(compareBy({ abs(it.strike - target) }, { it.strike }))
    var spread = short?.let { midOf(it.put) }?.let { longMid - it }?.takeIf { it > 0.0 }
    return HedgeQuote(
        protectivePutCostBps = (longMid / forward * 10_000.0).roundToInt(),
        putSpreadCostBps = spread?.let { (it / forward * 10_000.0).roundToInt() },
        longStrike = move.strike,
        shortStrike = spread?.let { short?.strike },
    )
}
