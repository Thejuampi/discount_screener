package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two chart inputs market-context scoring reads that nothing else did: 52-week position and
 * Bollinger %B. Both mirror `apps/windows/src-tauri/src/engine.rs` (`pos_52w_pct`, `bb_percent_b`),
 * so these tests pin the shape of the mirror rather than a calibration.
 */
class ChartSummaryMarketContextInputsTest {
    @Test
    fun position_in_52_week_range_is_100_when_the_latest_close_is_the_high() {
        var summary = summaryOf(risingCandles(count = 60))

        assertEquals(100.0, summary.pos52wPct)
    }

    @Test
    fun position_in_52_week_range_is_zero_when_the_latest_close_is_the_low() {
        var summary = summaryOf(risingCandles(count = 60).reversed())

        assertEquals(0.0, summary.pos52wPct)
    }

    @Test
    fun position_in_52_week_range_is_absent_when_the_range_is_a_single_price() {
        var summary = summaryOf((1..30).map { index -> flatCandle(index, priceCents = 10_000) })

        assertNull(summary.pos52wPct)
    }

    @Test
    fun the_52_week_window_stops_at_252_bars_so_older_extremes_drop_out() {
        var candles = buildList {
            add(candle(epoch = 1, low = 10_000, high = 99_999, close = 10_000))
            addAll((2..300).map { index -> candle(epoch = index, low = 10_000, high = 10_100, close = 10_000) })
        }

        var summary = summaryOf(candles)

        assertEquals(10_100L, summary.high52wCents)
    }

    @Test
    fun bollinger_percent_b_runs_past_one_when_price_closes_above_the_upper_band() {
        var candles = (1..19).map { index -> flatCandle(index, priceCents = 10_000) } +
            flatCandle(epoch = 20, priceCents = 12_000)

        var percentB = summaryOf(candles).bbPercentB

        assertTrue(percentB != null && percentB > 1.0, "expected %B above the band, got $percentB")
    }

    @Test
    fun bollinger_percent_b_is_absent_below_a_full_20_bar_window() {
        var summary = summaryOf((1..19).map { index -> flatCandle(index, priceCents = 10_000) })

        assertNull(summary.bbPercentB)
    }

    private fun summaryOf(candles: List<HistoricalCandle>) = ChartAnalysis.buildSummary(
        range = ChartRange.Year,
        candles = candles,
        capturedAtEpochSeconds = 1_700_000_000L,
    )

    /** A monotonically rising series, so the latest close sits exactly on the window high. */
    private fun risingCandles(count: Int) = (1..count).map { index ->
        flatCandle(epoch = index, priceCents = 10_000L + (index * 10L))
    }

    private fun flatCandle(epoch: Int, priceCents: Long) =
        candle(epoch = epoch, low = priceCents, high = priceCents, close = priceCents)

    private fun candle(epoch: Int, low: Long, high: Long, close: Long) = HistoricalCandle(
        epochSeconds = epoch.toLong(),
        openCents = low,
        highCents = high,
        lowCents = low,
        closeCents = close,
        volume = 1_000L,
    )
}
