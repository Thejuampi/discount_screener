package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.PricingCandle
import kotlin.test.Test
import kotlin.test.assertEquals

class PricingHistoryMergeTest {
    @Test
    fun merge_overwrites_existing_duplicates_with_incoming_candle() {
        val existing = pricingCandle(
            symbol = "AAPL",
            range = ChartRange.Month,
            epoch = 100,
            close = 10_000,
        )
        val duplicateIncoming = pricingCandle(
            symbol = "AAPL",
            range = ChartRange.Month,
            epoch = 100,
            close = 20_000,
        )

        val merged = PricingHistoryMerge.merge(
            existing = listOf(existing),
            incoming = listOf(duplicateIncoming),
        )

        assertEquals(listOf(duplicateIncoming), merged)
    }

    @Test
    fun merge_accepts_new_candles_for_distinct_keys() {
        val existing = pricingCandle("AAPL", ChartRange.Month, epoch = 100)
        val newEpoch = pricingCandle("AAPL", ChartRange.Month, epoch = 200)
        val newRange = pricingCandle("AAPL", ChartRange.Year, epoch = 100)
        val newSymbol = pricingCandle("MSFT", ChartRange.Month, epoch = 100)

        val merged = PricingHistoryMerge.merge(
            existing = listOf(existing),
            incoming = listOf(newSymbol, newRange, newEpoch),
        )

        assertEquals(listOf(existing, newEpoch, newRange, newSymbol), merged)
    }

    @Test
    fun merge_sorts_out_of_order_input_by_symbol_range_and_epoch() {
        val zYearLate = pricingCandle("ZZZ", ChartRange.Year, epoch = 300)
        val aWeekLate = pricingCandle("AAA", ChartRange.Week, epoch = 300)
        val aDayLate = pricingCandle("AAA", ChartRange.Day, epoch = 300)
        val aDayEarly = pricingCandle("AAA", ChartRange.Day, epoch = 100)

        val merged = PricingHistoryMerge.merge(
            existing = listOf(zYearLate, aWeekLate),
            incoming = listOf(aDayLate, aDayEarly),
        )

        assertEquals(listOf(aDayEarly, aDayLate, aWeekLate, zYearLate), merged)
    }

    private fun pricingCandle(
        symbol: String,
        range: ChartRange,
        epoch: Long,
        close: Long = 1_000,
    ) = PricingCandle(
        symbol = symbol,
        range = range,
        candle = HistoricalCandle(
            epochSeconds = epoch,
            openCents = close,
            highCents = close,
            lowCents = close,
            closeCents = close,
            volume = 1,
        ),
    )
}
