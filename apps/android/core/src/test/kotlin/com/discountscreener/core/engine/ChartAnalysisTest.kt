package com.discountscreener.core.engine

import com.discountscreener.core.model.HistoricalCandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartAnalysisTest {
    @Test
    fun replay_window_uses_prefix_before_replay_offset() {
        val candles = (1..10).map { index -> candle(index, volume = index.toLong()) }

        val window = ChartAnalysis.buildReplayWindow(candles, replayOffset = 3)

        assertEquals(7, window.visibleCandles.size)
        assertEquals(10, window.totalCandles)
        assertEquals(3, window.replayOffset)
        assertEquals(7, window.visibleCount)
        assertEquals(7L, window.visibleCandles.last().epochSeconds)
    }

    @Test
    fun replay_window_clamps_to_single_oldest_candle() {
        val candles = (1..4).map { index -> candle(index, volume = index.toLong()) }

        val window = ChartAnalysis.buildReplayWindow(candles, replayOffset = 99)

        assertEquals(1, window.visibleCandles.size)
        assertEquals(3, window.replayOffset)
        assertEquals(1L, window.visibleCandles.single().epochSeconds)
    }

    @Test
    fun replay_step_back_and_forward_clamp_to_valid_offsets() {
        assertEquals(1, ChartAnalysis.stepReplayBack(replayOffset = 0, totalCandles = 4))
        assertEquals(3, ChartAnalysis.stepReplayBack(replayOffset = 3, totalCandles = 4))
        assertEquals(0, ChartAnalysis.stepReplayBack(replayOffset = 0, totalCandles = 1))
        assertEquals(2, ChartAnalysis.stepReplayForward(replayOffset = 3))
        assertEquals(0, ChartAnalysis.stepReplayForward(replayOffset = 0))
    }

    @Test
    fun volume_profile_preserves_up_and_down_volume_totals() {
        val candles = listOf(
            candle(1, open = 2_000, low = 2_000, high = 4_000, close = 4_000, volume = 600),
            candle(2, open = 4_000, low = 2_000, high = 4_000, close = 2_000, volume = 400),
        )

        val bins = ChartAnalysis.computeVolumeProfile(
            candles = candles,
            minPriceCents = 2_000,
            maxPriceCents = 4_000,
            binCount = 2,
        )

        assertEquals(600L, bins.sumOf { it.upVolume })
        assertEquals(400L, bins.sumOf { it.downVolume })
    }

    @Test
    fun volume_profile_distributes_wide_candle_without_losing_volume() {
        val bins = ChartAnalysis.computeVolumeProfile(
            candles = listOf(candle(1, open = 1_000, low = 1_000, high = 5_000, close = 5_000, volume = 10)),
            minPriceCents = 1_000,
            maxPriceCents = 5_000,
            binCount = 4,
        )

        assertEquals(listOf(2L, 2L, 3L, 3L), bins.map { it.upVolume })
        assertEquals(10L, bins.sumOf { it.totalVolume })
    }

    @Test
    fun volume_profile_returns_zero_bins_for_invalid_price_range() {
        val bins = ChartAnalysis.computeVolumeProfile(
            candles = listOf(candle(1, open = 3_000, low = 3_000, high = 3_000, close = 3_000, volume = 500)),
            minPriceCents = 3_000,
            maxPriceCents = 3_000,
            binCount = 4,
        )

        assertEquals(4, bins.size)
        assertTrue(bins.all { it.totalVolume == 0L })
    }

    private fun candle(
        epoch: Int,
        open: Long = 1_000,
        low: Long = open,
        high: Long = open + 100,
        close: Long = high,
        volume: Long,
    ) = HistoricalCandle(
        epochSeconds = epoch.toLong(),
        openCents = open,
        highCents = high,
        lowCents = low,
        closeCents = close,
        volume = volume,
    )
}
