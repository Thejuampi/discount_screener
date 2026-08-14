package com.discountscreener.core.plan

import com.discountscreener.core.model.HistoricalCandle
import kotlin.math.abs

data class SupportResistance(
    val supportsCents: List<Long> = emptyList(),
    val resistancesCents: List<Long> = emptyList(),
)

/**
 * Pivot highs/lows clustered within 0.5%. Port of `engine.rs::find_support_resistance`.
 */
fun findSupportResistance(candles: List<HistoricalCandle>, lookback: Int = 5): SupportResistance {
    var n = candles.size
    if (n < lookback * 2 + 1) return SupportResistance()
    var latest = candles[n - 1].closeCents
    var pivotsHigh = ArrayList<Long>()
    var pivotsLow = ArrayList<Long>()
    var i = lookback
    while (i < n - lookback) {
        var high = candles[i].highCents
        var low = candles[i].lowCents
        var isHigh = true
        var isLow = true
        var j = i - lookback
        while (j <= i + lookback) {
            if (j != i) {
                if (candles[j].highCents > high) isHigh = false
                if (candles[j].lowCents < low) isLow = false
            }
            j += 1
        }
        if (isHigh) pivotsHigh.add(high)
        if (isLow) pivotsLow.add(low)
        i += 1
    }
    var highs = clusterLevels(pivotsHigh)
    var lows = clusterLevels(pivotsLow)
    var resistances = highs.filter { it > latest }.sortedBy { it - latest }.take(3)
    var supports = lows.filter { it < latest }.sortedBy { latest - it }.take(3)
    return SupportResistance(supportsCents = supports, resistancesCents = resistances)
}

private fun clusterLevels(raw: List<Long>): List<Long> {
    var sorted = raw.sorted()
    var out = ArrayList<Long>()
    for (x in sorted) {
        var last = out.lastOrNull()
        if (last == null || abs(x - last).toDouble() / last.coerceAtLeast(1L).toDouble() > 0.005) {
            out.add(x)
        }
    }
    return out
}
