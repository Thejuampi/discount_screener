package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chart inputs market-context scoring reads that nothing else did: 52-week position, Bollinger
 * %B, and Wilder's ADX with its two directional indicators. All mirror
 * `apps/windows/src-tauri/src/engine.rs` (`pos_52w_pct`, `bb_percent_b`, `compute_adx`), so these
 * tests pin the shape of the mirror rather than a calibration.
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

    @Test
    fun adx_separates_a_persistent_trend_from_chop() {
        var trending = summaryOf(risingCandles(count = 60)).adx!!

        assertTrue(trending > summaryOf(choppyCandles(count = 60)).adx!!, "trend $trending must beat chop")
    }

    /**
     * ADX measures trend *strength* and is blind to direction — the sign lives in the two
     * directional indicators. A rising and a falling series that move by the same amount each bar
     * must therefore read identically here, and differ only in [ChartRangeSummary.plusDi] versus
     * [ChartRangeSummary.minusDi]. An implementation that leaked direction into ADX would pass a
     * "trend beats chop" test and fail this one.
     */
    @Test
    fun adx_reads_the_same_strength_whichever_way_the_trend_runs() {
        var rising = summaryOf(risingCandles(count = 60)).adx!!

        assertEquals(rising, summaryOf(risingCandles(count = 60).reversed()).adx!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun plus_di_leads_minus_di_in_an_uptrend() {
        var summary = summaryOf(risingCandles(count = 60))

        assertTrue(summary.plusDi!! > summary.minusDi!!)
    }

    @Test
    fun minus_di_leads_plus_di_in_a_downtrend() {
        var summary = summaryOf(risingCandles(count = 60).reversed())

        assertTrue(summary.minusDi!! > summary.plusDi!!)
    }

    /**
     * Wilder needs `2 × 14 + 1` bars before the first ADX reading exists. The pair of tests is what
     * makes the guard meaningful: one bar either side of the boundary, so an implementation that
     * simply never produced an ADX could not pass both.
     */
    @Test
    fun adx_is_absent_one_bar_below_the_wilder_window() {
        var summary = summaryOf(risingCandles(count = 28))

        assertNull(summary.adx)
    }

    @Test
    fun adx_appears_as_soon_as_the_wilder_window_is_full() {
        var summary = summaryOf(risingCandles(count = 29))

        assertNotNull(summary.adx)
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

    /** Alternating up and down bars of equal size: real range, no net direction. */
    private fun choppyCandles(count: Int) = (1..count).map { index ->
        flatCandle(epoch = index, priceCents = 10_000L + ((index % 2) * 100L))
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
