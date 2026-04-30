package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.PricingCandle

object PricingHistoryMerge {
    fun merge(
        existing: List<PricingCandle>,
        incoming: List<PricingCandle>,
    ): List<PricingCandle> {
        val candlesByKey = LinkedHashMap<PricingCandleKey, PricingCandle>()
        existing.forEach { candle ->
            candlesByKey[candle.key()] = candle
        }
        incoming.forEach { candle ->
            candlesByKey[candle.key()] = candle
        }
        return candlesByKey.values.sortedWith(pricingCandleOrder)
    }

    private val pricingCandleOrder = compareBy<PricingCandle>(
        { it.symbol },
        { it.range },
        { it.candle.epochSeconds },
    )

    private fun PricingCandle.key(): PricingCandleKey = PricingCandleKey(
        symbol = symbol,
        range = range,
        epochSeconds = candle.epochSeconds,
    )

    private data class PricingCandleKey(
        val symbol: String,
        val range: ChartRange,
        val epochSeconds: Long,
    )
}
