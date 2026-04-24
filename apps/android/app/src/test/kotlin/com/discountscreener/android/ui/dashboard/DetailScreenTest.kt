package com.discountscreener.android.ui.dashboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRevision
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun replay_keyboard_controls_match_desktop_and_phone_volume_keys() {
        assertEquals(DashboardAction.StepReplayBack, replayActionForKey(Key.DirectionLeft))
        assertEquals(DashboardAction.StepReplayBack, replayActionForKey(Key.VolumeDown))
        assertEquals(DashboardAction.StepReplayForward, replayActionForKey(Key.DirectionRight))
        assertEquals(DashboardAction.StepReplayForward, replayActionForKey(Key.VolumeUp))
        assertNull(replayActionForKey(Key.DirectionUp))
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
    fun volume_profile_model_uses_visible_replay_candles() {
        val model = buildVolumeProfileModel(
            candles = listOf(
                sampleCandle(
                    epochSeconds = dateEpoch(2024, 1, 1),
                    openCents = 2_000L,
                    lowCents = 2_000L,
                    highCents = 4_000L,
                    closeCents = 4_000L,
                    volume = 600L,
                ),
                sampleCandle(
                    epochSeconds = dateEpoch(2024, 1, 2),
                    openCents = 4_000L,
                    lowCents = 2_000L,
                    highCents = 4_000L,
                    closeCents = 2_000L,
                    volume = 400L,
                ),
            ),
            minPriceCents = 2_000L,
            maxPriceCents = 4_000L,
            binCount = 2,
        )!!

        assertEquals(2, model.bins.size)
        assertEquals(600L, model.bins.sumOf { it.upVolume })
        assertEquals(400L, model.bins.sumOf { it.downVolume })
        assertEquals(500L, model.maxBinVolume)
    }

    @Test
    fun replay_summary_marks_historical_and_live_windows() {
        assertEquals(
            "Showing 7 / 10 candles  |  Replay -3 from live  |  Volume max 1.00K",
            replayStatusText(visibleCount = 7, totalCount = 10, replayOffset = 3, maxVolume = 1_000L),
        )
        assertEquals(
            "Showing 10 / 10 candles  |  Live  |  Volume max 1.00K",
            replayStatusText(visibleCount = 10, totalCount = 10, replayOffset = 0, maxVolume = 1_000L),
        )
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
    fun valuation_trend_summary_detects_steady_upgrades() {
        val summary = valuationTrendSummary(
            listOf(
                revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = 10_000L),
                revisionAt(dateEpoch(2024, 2, 1), weightedFairValueCents = 11_000L),
                revisionAt(dateEpoch(2024, 3, 1), weightedFairValueCents = 12_500L),
            ),
        )

        assertEquals("Analysts steadily raising targets", summary.title)
        assertTrue(summary.detail.contains("latest \$125.00"))
    }

    @Test
    fun valuation_trend_summary_falls_back_to_median_target_when_weighted_is_missing() {
        val summary = valuationTrendSummary(
            listOf(
                revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = null, externalFairValueCents = 10_000L),
                revisionAt(dateEpoch(2024, 2, 1), weightedFairValueCents = null, externalFairValueCents = 11_000L),
                revisionAt(dateEpoch(2024, 3, 1), weightedFairValueCents = null, externalFairValueCents = 12_500L),
            ),
        )

        assertEquals("Analysts steadily raising targets", summary.title)
        assertTrue(summary.detail.contains("latest \$125.00"))
    }

    @Test
    fun significant_revision_changes_ignore_small_moves() {
        val changes = significantRevisionChanges(
            listOf(
                revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = 10_000L),
                revisionAt(dateEpoch(2024, 2, 1), weightedFairValueCents = 10_200L),
                revisionAt(dateEpoch(2024, 3, 1), weightedFairValueCents = 11_000L),
            ),
        )

        assertEquals(1, changes.size)
        assertEquals("2024-03-01", revisionDateLabel(changes.single().revision))
    }

    @Test
    fun latest_meaningful_revision_change_returns_latest_significant_step() {
        val latest = latestMeaningfulRevisionChange(
            listOf(
                revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = 10_000L),
                revisionAt(dateEpoch(2024, 2, 1), weightedFairValueCents = 10_700L),
                revisionAt(dateEpoch(2024, 3, 1), weightedFairValueCents = 10_900L),
                revisionAt(dateEpoch(2024, 4, 1), weightedFairValueCents = 12_500L),
            ),
        )

        assertEquals("2024-04-01", revisionDateLabel(latest!!.current))
        assertEquals("2024-03-01", revisionDateLabel(latest.previous))
    }

    @Test
    fun history_status_message_explains_empty_and_single_snapshot_states() {
        val emptyHistoryMessage = historyStatusMessage(
            detail = detailWithValuationAnchors(),
            history = emptyList(),
            valuationHistory = emptyList(),
        )
        assertEquals("No saved analyst-target history yet", emptyHistoryMessage?.title)

        val singleSnapshotMessage = historyStatusMessage(
            detail = detailWithValuationAnchors(),
            history = listOf(revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = 10_000L)),
            valuationHistory = listOf(revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = 10_000L)),
        )
        assertEquals("Only one analyst-target snapshot", singleSnapshotMessage?.title)

        assertNull(
            historyStatusMessage(
                detail = detailWithValuationAnchors(),
                history = listOf(
                    revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = 10_000L),
                    revisionAt(dateEpoch(2024, 2, 1), weightedFairValueCents = 11_000L),
                ),
                valuationHistory = listOf(
                    revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = 10_000L),
                    revisionAt(dateEpoch(2024, 2, 1), weightedFairValueCents = 11_000L),
                ),
            ),
        )
    }

    @Test
    fun history_status_message_does_not_report_missing_when_median_targets_exist() {
        val revisions = listOf(
            revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = null, externalFairValueCents = 10_000L),
            revisionAt(dateEpoch(2024, 2, 1), weightedFairValueCents = null, externalFairValueCents = 11_000L),
        )

        assertNull(
            historyStatusMessage(
                detail = detailWithValuationAnchors().copy(
                    weightedExternalSignalFairValueCents = null,
                    weightedAnalystCount = null,
                    externalSignalFairValueCents = 11_000L,
                    analystOpinionCount = 18,
                ),
                history = revisions,
                valuationHistory = revisions,
            ),
        )
    }

    @Test
    fun analyst_target_episodes_collapse_repeated_targets_into_flat_spans() {
        val episodes = analystTargetEpisodes(
            listOf(
                revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = 10_000L),
                revisionAt(dateEpoch(2024, 1, 8), weightedFairValueCents = 10_000L),
                revisionAt(dateEpoch(2024, 1, 15), weightedFairValueCents = 11_500L),
                revisionAt(dateEpoch(2024, 1, 22), weightedFairValueCents = 11_500L),
            ),
        )

        assertEquals(2, episodes.size)
        assertEquals(2, episodes.first().observationCount)
        assertEquals(2, episodes.last().observationCount)
        assertEquals(11_500L, episodes.last().targetFairValueCents)
    }

    @Test
    fun analyst_target_history_overview_reports_flat_state_with_price_context() {
        val history = listOf(
            revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = 10_000L, marketPriceCents = 8_500L),
            revisionAt(dateEpoch(2024, 2, 1), weightedFairValueCents = 10_000L, marketPriceCents = 9_500L),
            revisionAt(dateEpoch(2024, 3, 1), weightedFairValueCents = 10_000L, marketPriceCents = 11_000L),
        )

        val overview = analystTargetHistoryOverview(history, analystTargetEpisodes(history))

        assertEquals(AnalystTargetHistoryState.Flat, overview?.state)
        assertTrue(overview!!.detail.contains("Price moved"))
        assertEquals(0, overview.changeCount)
    }

    @Test
    fun analyst_target_history_overview_reports_change_count_and_source() {
        val history = listOf(
            revisionAt(dateEpoch(2024, 1, 1), weightedFairValueCents = null, externalFairValueCents = 10_000L),
            revisionAt(dateEpoch(2024, 2, 1), weightedFairValueCents = null, externalFairValueCents = 11_000L),
            revisionAt(dateEpoch(2024, 3, 1), weightedFairValueCents = null, externalFairValueCents = 12_000L),
        )

        val overview = analystTargetHistoryOverview(history, analystTargetEpisodes(history))

        assertEquals(AnalystTargetHistoryState.Changed, overview?.state)
        assertEquals("Median", overview?.sourceLabel)
        assertEquals(2, overview?.changeCount)
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

    private fun revisionAt(
        evaluatedAtEpochSeconds: Long,
        weightedFairValueCents: Long?,
        externalFairValueCents: Long = weightedFairValueCents ?: 0L,
        marketPriceCents: Long = 17_270L,
    ) = SymbolRevision(
        symbol = "NVDA",
        evaluatedAtEpochSeconds = evaluatedAtEpochSeconds,
        detail = detailWithValuationAnchors().copy(
            marketPriceCents = marketPriceCents,
            weightedExternalSignalFairValueCents = weightedFairValueCents,
            externalSignalFairValueCents = externalFairValueCents,
            externalSignalLowFairValueCents = externalFairValueCents - 1_000L,
            externalSignalHighFairValueCents = externalFairValueCents + 1_000L,
        ),
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
        openCents: Long = closeCents - 100L,
        highCents: Long = closeCents + 100L,
        lowCents: Long = closeCents - 200L,
        volume: Long = 1_000L,
    ) = HistoricalCandle(
        epochSeconds = epochSeconds,
        openCents = openCents,
        highCents = highCents,
        lowCents = lowCents,
        closeCents = closeCents,
        volume = volume,
    )

    private fun dateEpoch(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()
}
