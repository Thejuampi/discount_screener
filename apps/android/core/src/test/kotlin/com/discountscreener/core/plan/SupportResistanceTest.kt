package com.discountscreener.core.plan

import com.discountscreener.core.model.HistoricalCandle
import kotlin.test.Test
import kotlin.test.assertEquals

class SupportResistanceTest {
    @Test
    fun nearest_support_is_the_pivot_low_below_the_last_close() {
        var candles = (0..20).map { i ->
            var close = when (i) {
                10 -> 8_000L
                else -> 10_000L
            }
            candle(close, i.toLong(), high = if (i == 10) 8_100L else 10_200L, low = if (i == 10) 7_900L else 9_800L)
        }
        var sr = findSupportResistance(candles, lookback = 5)
        assertEquals(7_900L, sr.supportsCents.first())
    }
}

internal fun candle(
    close: Long,
    i: Long,
    high: Long = close + 50,
    low: Long = close - 50,
): HistoricalCandle = HistoricalCandle(
    epochSeconds = 1_700_000_000L + i * 86_400L,
    openCents = close,
    highCents = high,
    lowCents = low,
    closeCents = close,
    volume = 1_000_000L,
)
