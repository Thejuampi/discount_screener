package com.discountscreener.core.engine

import kotlin.math.roundToInt

data class FredDgs10Observation(
    val asOfDate: String,
    val yieldBps: Int,
)

object FredDgs10Parser {
    val MIN_YIELD_BPS: Int
        get() = ValuationPolicy.current.market.rfMinYieldBps
    val MAX_YIELD_BPS: Int
        get() = ValuationPolicy.current.market.rfMaxYieldBps

    fun latest(csv: String): FredDgs10Observation {
        var last: FredDgs10Observation? = null
        for (rawLine in csv.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("observation_date", ignoreCase = true)) continue
            var parts = line.split(',')
            if (parts.size < 2) continue
            var date = parts[0].trim()
            var yieldText = parts[1].trim()
            if (yieldText.isEmpty() || yieldText == ".") continue
            var percent = yieldText.toDoubleOrNull()
                ?: throw IllegalArgumentException("FRED DGS10 yield is not numeric: $yieldText")
            var bps = (percent * 100.0).roundToInt()
            if (bps < MIN_YIELD_BPS || bps > MAX_YIELD_BPS) {
                throw IllegalArgumentException("FRED DGS10 yield out of range: $bps bps")
            }
            last = FredDgs10Observation(asOfDate = date, yieldBps = bps)
        }
        return last ?: throw IllegalArgumentException("FRED DGS10 csv has no numeric yield")
    }
}
