package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.PricingCandle
import kotlin.test.Test
import kotlin.test.assertEquals

class PricingHistoryMergeTest {
    @Test
    fun merge_replaces_month_candles_that_share_a_utc_calendar_day() {
        val existing = pricingCandle("AAPL", ChartRange.Month, epoch = 1_704_067_200, close = 10_000)
        val incoming = pricingCandle("AAPL", ChartRange.Month, epoch = 1_704_110_400, close = 20_000)

        val merged = PricingHistoryMerge.merge(existing = listOf(existing), incoming = listOf(incoming))

        assertEquals(listOf(incoming), merged)
    }

    @Test
    fun merge_replaces_year_candles_that_share_an_iso_week() {
        val existing = pricingCandle("AAPL", ChartRange.Year, epoch = 1_704_067_200, close = 10_000)
        val incoming = pricingCandle("AAPL", ChartRange.Year, epoch = 1_704_326_400, close = 20_000)

        val merged = PricingHistoryMerge.merge(existing = listOf(existing), incoming = listOf(incoming))

        assertEquals(listOf(incoming), merged)
    }

    @Test
    fun merge_replaces_long_range_candles_that_share_a_utc_year_month() {
        val existing = pricingCandle("AAPL", ChartRange.FiveYears, epoch = 1_704_067_200, close = 10_000)
        val incoming = pricingCandle("AAPL", ChartRange.FiveYears, epoch = 1_706_659_200, close = 20_000)

        val merged = PricingHistoryMerge.merge(existing = listOf(existing), incoming = listOf(incoming))

        assertEquals(listOf(incoming), merged)
    }

    @Test
    fun merge_keeps_distinct_intraday_candles_on_the_same_utc_day() {
        val early = pricingCandle("AAPL", ChartRange.Day, epoch = 1_704_067_200, close = 10_000)
        val late = pricingCandle("AAPL", ChartRange.Day, epoch = 1_704_110_400, close = 20_000)

        val merged = PricingHistoryMerge.merge(existing = listOf(early), incoming = listOf(late))

        assertEquals(listOf(early, late), merged)
    }

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
        val newEpoch = pricingCandle("AAPL", ChartRange.Month, epoch = 86_500)
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
