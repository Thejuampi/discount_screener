package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.PricingCandle
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.WeekFields

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
        period = semanticPeriod(),
    )

    private fun PricingCandle.semanticPeriod(): String {
        if (range == ChartRange.Day || range == ChartRange.Week) {
            return "epoch:${candle.epochSeconds}"
        }

        val date = Instant.ofEpochSecond(candle.epochSeconds).atZone(ZoneOffset.UTC).toLocalDate()
        return when (range) {
            ChartRange.Month -> "day:$date"
            ChartRange.Year -> {
                val iso = WeekFields.ISO
                "week:${date.get(iso.weekBasedYear())}-${date.get(iso.weekOfWeekBasedYear())}"
            }
            ChartRange.FiveYears,
            ChartRange.TenYears -> "month:${date.year}-${date.monthValue}"
            ChartRange.Day,
            ChartRange.Week -> error("intraday ranges use exact epoch identity")
        }
    }

    private data class PricingCandleKey(
        val symbol: String,
        val range: ChartRange,
        val period: String,
    )
}
