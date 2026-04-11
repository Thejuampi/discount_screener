package com.discountscreener.android.ui.dashboard

import androidx.compose.ui.graphics.Color
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailScreenTest {
    @Test
    fun chart_colors_use_exact_accessible_rgb_values() {
        assertEquals(Color(0xFF00FF00), BullishChartColor)
        assertEquals(Color(0xFFFF0000), BearishChartColor)
    }

    @Test
    fun chart_center_x_uses_slot_centers() {
        assertEquals(10f, chartCenterX(index = 0, pointCount = 5, width = 100f), 0.01f)
        assertEquals(90f, chartCenterX(index = 4, pointCount = 5, width = 100f), 0.01f)
    }

    @Test
    fun chart_range_label_uses_compact_tokens() {
        assertEquals("1D", chartRangeLabel(ChartRange.Day))
        assertEquals("7D", chartRangeLabel(ChartRange.Week))
        assertEquals("1M", chartRangeLabel(ChartRange.Month))
        assertEquals("1Y", chartRangeLabel(ChartRange.Year))
        assertEquals("5Y", chartRangeLabel(ChartRange.FiveYears))
        assertEquals("10Y", chartRangeLabel(ChartRange.TenYears))
    }

    @Test
    fun ema_values_match_desktop_reference_series() {
        val emaSeries = emaValues(listOf(10_000.0, 20_000.0, 30_000.0), 2)

        assertEquals(3, emaSeries.size)
        assertEquals(10_000.0, emaSeries[0], 0.001)
        assertEquals(16_666.6667, emaSeries[1], 0.01)
        assertEquals(25_555.5556, emaSeries[2], 0.01)
    }

    @Test
    fun price_chart_model_exposes_all_ema_reference_values() {
        val model = buildPriceChartModel(sampleCandles())!!

        assertTrue(model.axisLabels.top.startsWith("$"))
        assertTrue(model.axisLabels.middle.startsWith("$"))
        assertTrue(model.axisLabels.bottom.startsWith("$"))
        assertTrue((model.latestEma20Cents ?: 0L) > 0L)
        assertTrue((model.latestEma50Cents ?: 0L) > 0L)
        assertTrue((model.latestEma200Cents ?: 0L) > 0L)
        assertTrue(model.trendSignals.any { it.title == "Bull E20/E50" })
        assertTrue(model.trendSignals.any { it.title == "Bull E50/E200" })
    }

    @Test
    fun trend_signal_marks_fresh_bull_cross_with_explanation() {
        val signal = buildTrendSignal(
            fastSeries = listOf(10.0, 12.0),
            slowSeries = listOf(11.0, 11.0),
            label = "E20/E50",
            bullishMeaning = "bull",
            bearishMeaning = "bear",
            bullishCrossMeaning = "bull cross",
            bearishCrossMeaning = "bear cross",
        )!!

        assertEquals("Bull cross E20/E50", signal.title)
        assertEquals("bull cross", signal.meaning)
        assertEquals(TrendSignalBias.Bull, signal.bias)
        assertTrue(signal.freshCross)
    }

    @Test
    fun macd_chart_model_exposes_bearish_momentum_signal() {
        val model = buildMacdChartModel(
            (1..30).map { day ->
                sampleCandle(
                    epochSeconds = dateEpoch(2024, 1, day.coerceAtMost(28)),
                    closeCents = (30_000L - (day * 400L)),
                )
            },
        )!!

        assertEquals(TrendSignalBias.Bear, model.trendSignal?.bias)
        assertTrue(model.trendSignal?.title?.startsWith("Bear") == true)
    }

    @Test
    fun date_axis_ticks_are_evenly_spaced_for_selected_range() {
        val ticks = buildDateAxisTicks(
            candles = listOf(
                sampleCandle(epochSeconds = dateEpoch(2024, 1, 1)),
                sampleCandle(epochSeconds = dateEpoch(2024, 4, 1)),
                sampleCandle(epochSeconds = dateEpoch(2024, 7, 1)),
                sampleCandle(epochSeconds = dateEpoch(2024, 10, 1)),
            ),
            range = ChartRange.Year,
        )

        assertEquals(4, ticks.size)
        assertEquals(listOf(0, 1, 2, 3), ticks.map(ChartDateTick::index))
        assertEquals(listOf("Jan 1", "Apr 1", "Jul 1", "Oct 1"), ticks.map(ChartDateTick::label))
    }

    @Test
    fun chart_axis_label_texts_collect_all_visible_pane_labels() {
        val labels = chartAxisLabelTexts(
            ChartAxisLabels(top = "$929", middle = "$452", bottom = "-$26.3"),
            ChartAxisLabels(top = "45.3M", middle = "22.7M", bottom = "0"),
            ChartAxisLabels(top = "$139", middle = "$43.0", bottom = "-$52.9"),
        )

        assertTrue(labels.contains("45.3M"))
        assertTrue(labels.contains("-$52.9"))
        assertEquals(6, labels.maxOf(String::length))
    }

    @Test
    fun valuation_anchor_stats_summarize_available_anchor_values() {
        val stats = valuationAnchorStats(
            valuationAnchors(detailWithValuationAnchors()).map(ValuationAnchor::valueCents),
        )!!

        assertEquals(26_225L, stats.average)
        assertEquals(26_923L, stats.median)
        assertEquals(26_500L, stats.p25)
        assertEquals(27_200L, stats.p75)
        assertEquals(18_500L, stats.min)
        assertEquals(32_000L, stats.max)
        assertEquals(31_040L, stats.p95)
        assertEquals(31_808L, stats.p99)
    }

    @Test
    fun valuation_domain_expands_beyond_forecast_band_for_outside_markers() {
        val stats = ValuationStats(
            average = 18_229L,
            median = 18_115L,
            p25 = 16_000L,
            p75 = 20_172L,
            min = 10_000L,
            max = 26_000L,
            p95 = 24_834L,
            p99 = 25_767L,
        )
        val domain = valuationDomain(
            stats = stats,
            markers = listOf(
                VisualAnchor("Price", 8_300L, valuationReferenceColor("Price")),
                VisualAnchor("P99", 25_767L, valuationReferenceColor("P99")),
            ),
        )

        assertTrue(domain.minValue < 8_300L)
        assertTrue(domain.maxValue > 26_000L)
    }

    @Test
    fun valuation_domain_handles_flat_values() {
        val stats = ValuationStats(
            average = 10_000L,
            median = 10_000L,
            p25 = 10_000L,
            p75 = 10_000L,
            min = 10_000L,
            max = 10_000L,
            p95 = 10_000L,
            p99 = 10_000L,
        )
        val domain = valuationDomain(stats = stats, markers = emptyList())

        assertTrue(domain.span > 0L)
        assertEquals(50f, domain.project(10_000L, 100f), 0.01f)
    }

    @Test
    fun valuation_marker_layout_stacks_close_markers_into_separate_lanes() {
        val domain = ValuationDomain(minValue = 10_000L, maxValue = 10_100L)
        val layouts = layoutValuationMarkers(
            markers = listOf(
                VisualAnchor("A", 10_010L, Color.Red),
                VisualAnchor("B", 10_011L, Color.Green),
                VisualAnchor("C", 10_012L, Color.Blue),
            ),
            domain = domain,
            width = 100f,
            minSpacing = 10f,
        )

        assertEquals(listOf(0, 1, 2), layouts.map(ValuationMarkerLayout::lane))
    }

    @Test
    fun valuation_markers_use_distinct_colors_per_reference_point() {
        val stats = valuationAnchorStats(
            valuationAnchors(detailWithValuationAnchors()).map(ValuationAnchor::valueCents),
        )!!
        val markers = valuationBaseMarkers(stats) + valuationDetailMarkers(detailWithValuationAnchors(), stats)

        assertEquals(markers.size, markers.map(VisualAnchor::color).toSet().size)
    }

    @Test
    fun macd_chart_scale_keeps_zero_at_bottom_for_positive_only_series() {
        val scale = macdChartScale(
            macdLine = listOf(12.0, 10.0, 8.0),
            signalLine = listOf(11.0, 9.0, 7.0),
            histogram = listOf(1.0, 1.0, 1.0),
        )

        assertEquals(100f, scale.project(0.0, 100f), 0.01f)
        assertEquals(0f, scale.project(12.0, 100f), 0.01f)
    }

    @Test
    fun macd_chart_scale_moves_zero_line_with_asymmetric_range() {
        val scale = macdChartScale(
            macdLine = listOf(4.0, 3.0, -2.0),
            signalLine = listOf(3.5, 2.5, -1.0),
            histogram = listOf(0.5, 0.0, -1.0),
        )

        val zeroY = scale.project(0.0, 120f)

        assertEquals(80f, zeroY, 0.01f)
        assertTrue(zeroY > 60f)
    }

    private fun detailWithValuationAnchors() = SymbolDetail(
        symbol = "NVDA",
        profitable = true,
        marketPriceCents = 17_270,
        intrinsicValueCents = 26_923,
        gapBps = 5_446,
        minimumGapBps = 1_500,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalFairValueCents = 26_500,
        externalSignalLowFairValueCents = 18_500,
        externalSignalHighFairValueCents = 32_000,
        weightedExternalSignalFairValueCents = 27_200,
        weightedAnalystCount = 9,
        externalSignalGapBps = 5_446,
        externalSignalAgeSeconds = 0,
        externalSignalMaxAgeSeconds = 86_400,
        analystOpinionCount = 42,
        recommendationMeanHundredths = 185,
        strongBuyCount = 20,
        buyCount = 10,
        holdCount = 8,
        sellCount = 3,
        strongSellCount = 1,
        fundamentals = null,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
    )

    private fun sampleCandles() = listOf(
        sampleCandle(epochSeconds = dateEpoch(2024, 1, 1), closeCents = 10_000L),
        sampleCandle(epochSeconds = dateEpoch(2024, 1, 8), closeCents = 10_500L),
        sampleCandle(epochSeconds = dateEpoch(2024, 1, 15), closeCents = 11_000L),
        sampleCandle(epochSeconds = dateEpoch(2024, 1, 22), closeCents = 11_500L),
    )

    private fun sampleCandle(
        epochSeconds: Long,
        closeCents: Long = 10_000L,
    ) = HistoricalCandle(
        epochSeconds = epochSeconds,
        openCents = closeCents - 100L,
        highCents = closeCents + 100L,
        lowCents = closeCents - 200L,
        closeCents = closeCents,
        volume = 1_000L,
    )

    private fun dateEpoch(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()
}
