package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.ProjectedChartStatus
import com.discountscreener.core.model.ProjectedTechnicalSignalBias
import com.discountscreener.core.model.ProjectedTechnicalSignalKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartAnalysisTest {
    @Test
    fun projected_chart_analysis_exposes_series_latest_values_and_price_domain() {
        var candles = (1..30).map { index ->
            candle(index, open = 10_000, low = 10_000, high = 10_000, close = 10_000, volume = 1_000)
        }

        var chart = ChartAnalysis.buildProjectedChartData(
            range = ChartRange.Month,
            candles = candles,
            capturedAtEpochSeconds = 123L,
            replayOffset = 0,
            volumeProfileBinCount = 4,
        )

        assertEquals(
            ChartAnalysisSeriesExpectation(
                status = ProjectedChartStatus.Available,
                visibleCount = 30,
                latestCloseCents = 10_000L,
                ema20Count = 30,
                ema50Count = 30,
                ema200Count = 30,
                latestEma20Cents = 10_000L,
                latestEma50Cents = 10_000L,
                latestEma200Cents = 10_000L,
                macdCount = 30,
                signalCount = 30,
                histogramCount = 30,
                latestMacdCents = 0L,
                latestSignalCents = 0L,
                latestHistogramCents = 0L,
                priceMinValue = 9_500f,
                priceMaxValue = 10_500f,
                volumeMax = 1_000L,
                volumeProfileBinCount = 4,
            ),
            ChartAnalysisSeriesExpectation(
                status = chart.analysis.status,
                visibleCount = chart.analysis.replayWindow.visibleCount,
                latestCloseCents = chart.analysis.price?.latestCloseCents,
                ema20Count = chart.analysis.price?.ema20.orEmpty().size,
                ema50Count = chart.analysis.price?.ema50.orEmpty().size,
                ema200Count = chart.analysis.price?.ema200.orEmpty().size,
                latestEma20Cents = chart.analysis.price?.latestEma20Cents,
                latestEma50Cents = chart.analysis.price?.latestEma50Cents,
                latestEma200Cents = chart.analysis.price?.latestEma200Cents,
                macdCount = chart.analysis.macd?.macdLine.orEmpty().size,
                signalCount = chart.analysis.macd?.signalLine.orEmpty().size,
                histogramCount = chart.analysis.macd?.histogram.orEmpty().size,
                latestMacdCents = chart.analysis.macd?.latestMacdCents,
                latestSignalCents = chart.analysis.macd?.latestSignalCents,
                latestHistogramCents = chart.analysis.macd?.latestHistogramCents,
                priceMinValue = chart.analysis.price?.domain?.minValue,
                priceMaxValue = chart.analysis.price?.domain?.maxValue,
                volumeMax = chart.analysis.volume?.maxVolume,
                volumeProfileBinCount = chart.analysis.volumeProfile?.bins.orEmpty().size,
            ),
        )
    }

    @Test
    fun projected_chart_analysis_exposes_technical_signal_kind_and_bias() {
        var candles = (1..30).map { index ->
            candle(
                epoch = index,
                open = 10_000L + (index * 100L),
                low = 9_900L + (index * 100L),
                high = 10_200L + (index * 100L),
                close = 10_100L + (index * 100L),
                volume = 1_000L,
            )
        }

        var chart = ChartAnalysis.buildProjectedChartData(
            range = ChartRange.Month,
            candles = candles,
            capturedAtEpochSeconds = 123L,
            replayOffset = 0,
            volumeProfileBinCount = 4,
        )

        val signals = chart.analysis.technicalSignals.map { signal ->
            ProjectedTechnicalSignalExpectation(signal.kind, signal.bias)
        }
        assertTrue(signals.contains(ProjectedTechnicalSignalExpectation(ProjectedTechnicalSignalKind.Ema20Ema50, ProjectedTechnicalSignalBias.Bull)))
        assertTrue(signals.contains(ProjectedTechnicalSignalExpectation(ProjectedTechnicalSignalKind.Ema50Ema200, ProjectedTechnicalSignalBias.Bull)))
        assertTrue(signals.contains(ProjectedTechnicalSignalExpectation(ProjectedTechnicalSignalKind.MacdSignal, ProjectedTechnicalSignalBias.Bull)))
        assertTrue(signals.contains(ProjectedTechnicalSignalExpectation(ProjectedTechnicalSignalKind.RsiMomentum, ProjectedTechnicalSignalBias.Bull)))
    }

    @Test
    fun projected_chart_analysis_exposes_dual_rsi_series_and_derivatives() {
        val candles = (1..40).map { index ->
            candle(
                epoch = index,
                open = 10_000L + (index * 120L),
                low = 9_900L + (index * 120L),
                high = 10_300L + (index * 120L),
                close = 10_200L + (index * 120L),
                volume = 1_000L + (index * 50L),
            )
        }

        val chart = ChartAnalysis.buildProjectedChartData(
            range = ChartRange.Month,
            candles = candles,
            capturedAtEpochSeconds = 123L,
            replayOffset = 0,
            volumeProfileBinCount = 4,
        )

        val rsi = chart.analysis.rsi
        assertEquals(candles.size, rsi?.wilderRsi.orEmpty().size)
        assertEquals(candles.size, rsi?.signalRsi.orEmpty().size)
        assertEquals(candles.size, rsi?.slope.orEmpty().size)
        assertEquals(candles.size, rsi?.acceleration.orEmpty().size)
        assertTrue((rsi?.latestWilderRsi ?: 0.0) > 50.0)
        assertTrue((rsi?.latestSignalRsi ?: 0.0) > 50.0)
    }

    @Test
    fun projected_chart_analysis_keeps_flat_rsi_neutral() {
        val candles = (1..40).map { index ->
            candle(index, open = 10_000L, low = 10_000L, high = 10_000L, close = 10_000L, volume = 1_000L)
        }

        val chart = ChartAnalysis.buildProjectedChartData(
            range = ChartRange.Month,
            candles = candles,
            capturedAtEpochSeconds = 123L,
            replayOffset = 0,
            volumeProfileBinCount = 4,
        )

        val rsi = chart.analysis.rsi
        assertEquals(50.0, rsi?.latestWilderRsi ?: 0.0, 0.01)
        assertEquals(50.0, rsi?.latestSignalRsi ?: 0.0, 0.01)
        assertEquals(0.0, rsi?.latestSlope ?: 0.0, 0.01)
    }

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

    private data class ChartAnalysisSeriesExpectation(
        val status: ProjectedChartStatus,
        val visibleCount: Int,
        val latestCloseCents: Long?,
        val ema20Count: Int,
        val ema50Count: Int,
        val ema200Count: Int,
        val latestEma20Cents: Long?,
        val latestEma50Cents: Long?,
        val latestEma200Cents: Long?,
        val macdCount: Int,
        val signalCount: Int,
        val histogramCount: Int,
        val latestMacdCents: Long?,
        val latestSignalCents: Long?,
        val latestHistogramCents: Long?,
        val priceMinValue: Float?,
        val priceMaxValue: Float?,
        val volumeMax: Long?,
        val volumeProfileBinCount: Int,
    )

    private data class ProjectedTechnicalSignalExpectation(
        val kind: ProjectedTechnicalSignalKind,
        val bias: ProjectedTechnicalSignalBias,
    )
}
