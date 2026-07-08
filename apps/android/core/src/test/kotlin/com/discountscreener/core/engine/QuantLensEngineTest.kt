package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ComputationResult
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.CorrelationRiskBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.EvidenceStrengthBand
import com.discountscreener.core.model.ExpectedValueRangeBand
import com.discountscreener.core.model.ExpectedValueRangeSource
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.QuantLensComparable
import com.discountscreener.core.model.QuantLensCorrelationSeries
import com.discountscreener.core.model.QuantLensHorizon
import com.discountscreener.core.model.QuantLensInput
import com.discountscreener.core.model.QuantLensModelVersion
import com.discountscreener.core.model.QuantLensPrimaryStatus
import com.discountscreener.core.model.QuantLensReasonCode
import com.discountscreener.core.model.QuantLensReport
import com.discountscreener.core.model.SimilarSetupsBand
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.TrendReliabilityBand
import kotlin.test.Test
import kotlin.test.assertEquals

class QuantLensEngineTest {
    @Test
    fun scaffold_report_contains_all_explicit_sections() {
        val report = QuantLensEngine.analyze(minimalInput()).requireSuccess()

        assertEquals(QuantLensPrimaryStatus.Sparse, report.evidenceStrength.primaryStatus)
        assertEquals(QuantLensPrimaryStatus.Sparse, report.expectedValueRange.primaryStatus)
        assertEquals(QuantLensPrimaryStatus.Unavailable, report.correlationRisk.primaryStatus)
        assertEquals(QuantLensPrimaryStatus.Insufficient, report.trendReliability.primaryStatus)
        assertEquals(QuantLensPrimaryStatus.Unavailable, report.horizonContext.primaryStatus)
        assertEquals(QuantLensPrimaryStatus.Sparse, report.similarSetups.primaryStatus)
    }

    @Test
    fun scaffold_report_keeps_domain_bands_for_every_section() {
        val report = QuantLensEngine.analyze(minimalInput()).requireSuccess()

        assertEquals(EvidenceStrengthBand.Sparse, report.evidenceStrength.band)
        assertEquals(ExpectedValueRangeBand.Sparse, report.expectedValueRange.band)
        assertEquals(CorrelationRiskBand.Unavailable, report.correlationRisk.band)
        assertEquals(TrendReliabilityBand.Insufficient, report.trendReliability.band)
        assertEquals(SimilarSetupsBand.Sparse, report.similarSetups.band)
    }

    @Test
    fun scaffold_report_is_deterministic_for_identical_inputs() {
        val input = minimalInput()

        val first = QuantLensEngine.analyze(input)
        val second = QuantLensEngine.analyze(input)

        assertEquals(first, second)
    }

    @Test
    fun scaffold_report_carries_supplied_metadata() {
        val report = QuantLensEngine.analyze(minimalInput()).requireSuccess()

        assertEquals("ACME", report.symbol)
        assertEquals(ChartRange.Month, report.selectedRange)
        assertEquals(1_777_000_000, report.computedAtEpochSeconds)
        assertEquals(QuantLensModelVersion.CURRENT, report.modelVersion)
        assertEquals("test-fingerprint", report.inputFingerprint)
        assertEquals(listOf(QuantLensReasonCode.ScaffoldPending), report.notices)
    }

    @Test
    fun expected_value_prefers_complete_dcf_three_anchor_range() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                dcfAnalysis = DcfAnalysis(
                    bearIntrinsicValueCents = 8_000,
                    baseIntrinsicValueCents = 12_000,
                    bullIntrinsicValueCents = 16_000,
                    waccBps = 900,
                    baseGrowthBps = 300,
                    netDebtDollars = 0,
                    source = DcfSource.SecEdgar,
                    sourceFingerprint = "dcf",
                ),
            ),
        ).requireSuccess()

        assertEquals(QuantLensPrimaryStatus.Available, report.expectedValueRange.primaryStatus)
        assertEquals(ExpectedValueRangeBand.ScenarioWeighted, report.expectedValueRange.band)
        assertEquals(ExpectedValueRangeSource.Dcf, report.expectedValueRange.source)
        assertEquals(12_000, report.expectedValueRange.weightedFairValueCents)
        assertEquals(2_000, report.expectedValueRange.weightedUpsideBps)
        assertEquals(8_000, report.expectedValueRange.lowFairValueCents)
        assertEquals(16_000, report.expectedValueRange.highFairValueCents)
    }

    @Test
    fun expected_value_keeps_one_or_two_anchors_sparse_reference_only() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                detail = detail(
                    externalSignalLowFairValueCents = 9_000,
                    externalSignalFairValueCents = 12_000,
                ),
            ),
        ).requireSuccess()

        assertEquals(QuantLensPrimaryStatus.Sparse, report.expectedValueRange.primaryStatus)
        assertEquals(ExpectedValueRangeBand.ReferenceOnly, report.expectedValueRange.band)
        assertEquals(null, report.expectedValueRange.weightedFairValueCents)
        assertEquals(null, report.expectedValueRange.weightedUpsideBps)
    }

    @Test
    fun correlation_risk_uses_local_close_to_close_returns() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                selectedCandlesByRange = mapOf(ChartRange.Month to correlatedCandles(offset = 0)),
                correlationSeries = listOf(
                    QuantLensCorrelationSeries("BETA", ChartRange.Month, correlatedCandles(offset = 100)),
                    QuantLensCorrelationSeries("GAMMA", ChartRange.Month, correlatedCandles(offset = 200)),
                ),
            ),
        ).requireSuccess()

        assertEquals(QuantLensPrimaryStatus.Available, report.correlationRisk.primaryStatus)
        assertEquals(CorrelationRiskBand.High, report.correlationRisk.band)
        assertEquals(listOf("BETA", "GAMMA"), report.correlationRisk.topPairs.map { it.symbol })
    }

    @Test
    fun trend_reliability_marks_small_fitted_movement_flat() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                selectedCandlesByRange = mapOf(
                    ChartRange.Month to (1..24).map { index ->
                        candle(index, close = 10_000L + index)
                    },
                ),
            ),
        ).requireSuccess()

        assertEquals(QuantLensPrimaryStatus.Available, report.trendReliability.primaryStatus)
        assertEquals(TrendReliabilityBand.Flat, report.trendReliability.band)
    }

    @Test
    fun trend_reliability_is_stable_when_duplicate_epoch_order_changes() {
        var baseCandles = (1..24).map { index -> candle(index, close = 10_000L + index * 75L) }
            .filterNot { it.epochSeconds == 12L }
        var lowDuplicate = candle(12, close = 5_000L)
        var normalDuplicate = candle(12, close = 10_900L)
        var highDuplicate = candle(12, close = 20_000L)

        assertEquals(
            QuantLensEngine.analyze(
                minimalInput(
                    selectedCandlesByRange = mapOf(
                        ChartRange.Month to baseCandles + listOf(lowDuplicate, normalDuplicate, highDuplicate),
                    ),
                ),
            ).requireSuccess().trendReliability,
            QuantLensEngine.analyze(
                minimalInput(
                    selectedCandlesByRange = mapOf(
                        ChartRange.Month to baseCandles + listOf(highDuplicate, normalDuplicate, lowDuplicate),
                    ),
                ),
            ).requireSuccess().trendReliability,
        )
    }

    @Test
    fun correlation_risk_is_stable_when_duplicate_epoch_order_changes() {
        var baseCandles = correlatedCandles(offset = 0).filterNot { it.epochSeconds == 12L }
        var lowDuplicate = candle(12, close = 5_000L)
        var normalDuplicate = candle(12, close = 10_900L)
        var highDuplicate = candle(12, close = 20_000L)

        assertEquals(
            QuantLensEngine.analyze(
                minimalInput(
                    selectedCandlesByRange = mapOf(
                        ChartRange.Month to baseCandles + listOf(lowDuplicate, normalDuplicate, highDuplicate),
                    ),
                    correlationSeries = listOf(
                        QuantLensCorrelationSeries("BETA", ChartRange.Month, correlatedCandles(offset = 100)),
                        QuantLensCorrelationSeries("GAMMA", ChartRange.Month, correlatedCandles(offset = 200)),
                    ),
                ),
            ).requireSuccess().correlationRisk,
            QuantLensEngine.analyze(
                minimalInput(
                    selectedCandlesByRange = mapOf(
                        ChartRange.Month to baseCandles + listOf(highDuplicate, normalDuplicate, lowDuplicate),
                    ),
                    correlationSeries = listOf(
                        QuantLensCorrelationSeries("BETA", ChartRange.Month, correlatedCandles(offset = 100)),
                        QuantLensCorrelationSeries("GAMMA", ChartRange.Month, correlatedCandles(offset = 200)),
                    ),
                ),
            ).requireSuccess().correlationRisk,
        )
    }

    @Test
    fun horizon_context_computes_three_baselines_from_loaded_chart_ranges() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                selectedCandlesByRange = mapOf(
                    ChartRange.Day to candleSeries(10_000, 10_100, 10_200, 10_404, 10_300, 10_609, 10_400, 10_816, 10_300, 11_021, 10_000),
                    ChartRange.Month to candleSeries(20_000, 20_200, 20_400, 20_808, 20_600, 21_218, 20_800, 21_632, 20_600, 22_042, 20_000),
                    ChartRange.FiveYears to (0..12).map { index -> candle(index + 1, close = 10_000L + index * 100L) },
                ),
            ),
        ).requireSuccess()

        assertEquals(
            listOf(
                HorizonSummary(QuantLensHorizon.FiveMinutes, ChartRange.Day, 1, 10, 200, 100, 477),
                HorizonSummary(QuantLensHorizon.OneDay, ChartRange.Month, 1, 10, 200, 100, 477),
                HorizonSummary(QuantLensHorizon.ThreeMonths, ChartRange.FiveYears, 3, 10, 286, 280, 294),
            ),
            report.horizonContext.horizons.map(::horizonSummary),
        )
    }

    @Test
    fun horizon_context_primary_status_is_available_when_all_horizons_are_available() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                selectedCandlesByRange = mapOf(
                    ChartRange.Day to flatCandles(count = 11),
                    ChartRange.Month to flatCandles(count = 11),
                    ChartRange.FiveYears to flatCandles(count = 13),
                ),
            ),
        ).requireSuccess()

        assertEquals(QuantLensPrimaryStatus.Available, report.horizonContext.primaryStatus)
    }

    @Test
    fun horizon_context_primary_status_is_partial_when_some_horizons_are_available() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                selectedCandlesByRange = mapOf(
                    ChartRange.Day to flatCandles(count = 11),
                    ChartRange.Month to flatCandles(count = 10),
                ),
            ),
        ).requireSuccess()

        assertEquals(QuantLensPrimaryStatus.Partial, report.horizonContext.primaryStatus)
    }

    @Test
    fun horizon_context_marks_missing_short_and_invalid_sources_without_percentiles() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                selectedCandlesByRange = mapOf(
                    ChartRange.Month to flatCandles(count = 10),
                    ChartRange.FiveYears to listOf(candle(1, close = 0), candle(2, close = -1)),
                ),
            ),
        ).requireSuccess()

        assertEquals(
            listOf(
                HorizonDegradedSummary(QuantLensHorizon.FiveMinutes, QuantLensPrimaryStatus.Unavailable, 0, QuantLensReasonCode.MissingHorizonCandles, null),
                HorizonDegradedSummary(QuantLensHorizon.OneDay, QuantLensPrimaryStatus.Insufficient, 9, QuantLensReasonCode.InsufficientHorizonSamples, null),
                HorizonDegradedSummary(QuantLensHorizon.ThreeMonths, QuantLensPrimaryStatus.Unavailable, 0, QuantLensReasonCode.InvalidHorizonClose, null),
            ),
            report.horizonContext.horizons.map {
                HorizonDegradedSummary(
                    horizon = it.horizon,
                    primaryStatus = it.primaryStatus,
                    sampleCount = it.sampleCount,
                    firstReason = it.reasonCodes.first(),
                    medianAbsoluteMoveBps = it.medianAbsoluteMoveBps,
                )
            },
        )
    }

    @Test
    fun horizon_context_primary_status_is_unavailable_when_all_horizons_are_unavailable() {
        val report = QuantLensEngine.analyze(minimalInput(selectedCandlesByRange = emptyMap())).requireSuccess()

        assertEquals(QuantLensPrimaryStatus.Unavailable, report.horizonContext.primaryStatus)
    }

    @Test
    fun horizon_context_ignores_invalid_close_cents_before_windowing() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                selectedCandlesByRange = mapOf(
                    ChartRange.Day to listOf(candle(0, close = 0), candle(1, close = -1)) + flatCandles(count = 11),
                ),
            ),
        ).requireSuccess()

        assertEquals(10, report.horizonContext.horizons.first { it.horizon == QuantLensHorizon.FiveMinutes }.sampleCount)
    }

    @Test
    fun horizon_context_canonicalizes_duplicate_epochs_before_sample_counting() {
        var report = QuantLensEngine.analyze(
            minimalInput(
                selectedCandlesByRange = mapOf(
                    ChartRange.Day to flatCandles(count = 10) + candle(5, close = 10_500),
                ),
            ),
        ).requireSuccess()

        assertEquals(
            HorizonDegradedSummary(
                horizon = QuantLensHorizon.FiveMinutes,
                primaryStatus = QuantLensPrimaryStatus.Insufficient,
                sampleCount = 9,
                firstReason = QuantLensReasonCode.InsufficientHorizonSamples,
                medianAbsoluteMoveBps = null,
            ),
            report.horizonContext.horizons.first { it.horizon == QuantLensHorizon.FiveMinutes }.let {
                HorizonDegradedSummary(
                    horizon = it.horizon,
                    primaryStatus = it.primaryStatus,
                    sampleCount = it.sampleCount,
                    firstReason = it.reasonCodes.first(),
                    medianAbsoluteMoveBps = it.medianAbsoluteMoveBps,
                )
            },
        )
    }

    @Test
    fun horizon_context_is_stable_when_duplicate_epoch_order_changes() {
        var baseCandles = flatCandles(count = 11).filterNot { it.epochSeconds == 5L }
        var lowDuplicate = candle(5, close = 5_000)
        var normalDuplicate = candle(5, close = 10_000)
        var highDuplicate = candle(5, close = 20_000)
        var lowFirst = baseCandles + listOf(lowDuplicate, normalDuplicate, highDuplicate)
        var highFirst = baseCandles + listOf(highDuplicate, normalDuplicate, lowDuplicate)

        assertEquals(
            QuantLensEngine.analyze(
                minimalInput(selectedCandlesByRange = mapOf(ChartRange.Day to lowFirst)),
            ).requireSuccess().horizonContext,
            QuantLensEngine.analyze(
                minimalInput(selectedCandlesByRange = mapOf(ChartRange.Day to highFirst)),
            ).requireSuccess().horizonContext,
        )
    }

    @Test
    fun similar_setups_returns_top_three_current_comparables() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                comparableUniverse = listOf(
                    comparable("ACME", upside = 2_000, evidence = 7_000, score = 70, trend = 6_000, spread = 2_000),
                    comparable("BETA", upside = 2_100, evidence = 7_100, score = 69, trend = 6_100, spread = 2_100),
                    comparable("GAMMA", upside = 2_200, evidence = 7_200, score = 68, trend = 6_200, spread = 2_200),
                    comparable("DELTA", upside = 4_000, evidence = 8_000, score = 90, trend = 8_000, spread = 4_000),
                    comparable("EPSI", upside = -1_000, evidence = 3_000, score = 10, trend = 2_000, spread = 8_000),
                ),
            ),
        ).requireSuccess()

        assertEquals(QuantLensPrimaryStatus.Available, report.similarSetups.primaryStatus)
        assertEquals(SimilarSetupsBand.Available, report.similarSetups.band)
        assertEquals(listOf("BETA", "GAMMA", "DELTA"), report.similarSetups.matches.map { it.symbol })
    }

    @Test
    fun similar_setups_exactly_two_qualifying_comparables_stays_sparse() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                comparableUniverse = listOf(
                    comparable("ACME", upside = 2_000, evidence = 7_000, score = 70),
                    comparable("BETA", upside = 2_100, evidence = 7_100, score = 69),
                    comparable("GAMMA", upside = 2_200, evidence = 7_200, score = 68),
                ),
            ),
        ).requireSuccess()

        assertEquals(QuantLensPrimaryStatus.Sparse, report.similarSetups.primaryStatus)
        assertEquals(2, report.similarSetups.qualifyingComparableCount)
        assertEquals(emptyList(), report.similarSetups.matches)
    }

    @Test
    fun expected_value_normalizes_reversed_analyst_range_without_throwing() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                detail = detail(
                    externalSignalLowFairValueCents = 16_000,
                    externalSignalFairValueCents = 14_000,
                    externalSignalHighFairValueCents = 9_000,
                    weightedExternalSignalFairValueCents = 8_000,
                ),
            ),
        ).requireSuccess()

        assertEquals(ExpectedValueRangeBand.ScenarioWeighted, report.expectedValueRange.band)
        assertEquals(8_000, report.expectedValueRange.lowFairValueCents)
        assertEquals(10_500, report.expectedValueRange.weightedFairValueCents)
        assertEquals(16_000, report.expectedValueRange.highFairValueCents)
    }

    @Test
    fun expected_value_normalizes_out_of_order_dcf_anchors_without_throwing() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                dcfAnalysis = DcfAnalysis(
                    bearIntrinsicValueCents = 17_000,
                    baseIntrinsicValueCents = 9_000,
                    bullIntrinsicValueCents = 13_000,
                    waccBps = 900,
                    baseGrowthBps = 300,
                    netDebtDollars = 0,
                    source = DcfSource.SecEdgar,
                ),
            ),
        ).requireSuccess()

        assertEquals(9_000, report.expectedValueRange.lowFairValueCents)
        assertEquals(13_000, report.expectedValueRange.weightedFairValueCents)
        assertEquals(17_000, report.expectedValueRange.highFairValueCents)
    }

    @Test
    fun expected_value_clamps_extreme_weighted_upside_without_throwing() {
        val report = QuantLensEngine.analyze(
            minimalInput(
                detail = detail(
                    marketPriceCents = 10_000,
                    intrinsicValueCents = 250_000,
                    gapBps = 240_000,
                    externalSignalLowFairValueCents = 248_500,
                    externalSignalFairValueCents = 250_000,
                    externalSignalHighFairValueCents = 251_500,
                    weightedExternalSignalFairValueCents = 250_000,
                ),
            ),
        ).requireSuccess()

        assertEquals(ExpectedValueRangeBand.ScenarioWeighted, report.expectedValueRange.band)
        assertEquals(100_000, report.expectedValueRange.weightedUpsideBps)
    }

    private fun minimalInput(
        detail: SymbolDetail = detail(),
        dcfAnalysis: DcfAnalysis? = null,
        selectedCandlesByRange: Map<ChartRange, List<HistoricalCandle>> = emptyMap(),
        correlationSeries: List<QuantLensCorrelationSeries> = emptyList(),
        comparableUniverse: List<QuantLensComparable> = emptyList(),
    ) = QuantLensInput(
        detail = detail,
        dcfAnalysis = dcfAnalysis,
        selectedCandlesByRange = selectedCandlesByRange,
        correlationSeries = correlationSeries,
        comparableUniverse = comparableUniverse,
        selectedRange = ChartRange.Month,
        inputFingerprint = "test-fingerprint",
        scoringModel = OpportunityScoringModel.AggressiveV2,
        scoringVersion = 2,
        nowEpochSeconds = 1_777_000_000,
    )

    private fun detail(
        marketPriceCents: Long = 10_000,
        intrinsicValueCents: Long = 12_000,
        gapBps: Int = 1_667,
        externalSignalLowFairValueCents: Long? = null,
        externalSignalFairValueCents: Long? = null,
        externalSignalHighFairValueCents: Long? = null,
        weightedExternalSignalFairValueCents: Long? = null,
    ) = SymbolDetail(
        symbol = "ACME",
        profitable = true,
        marketPriceCents = marketPriceCents,
        intrinsicValueCents = intrinsicValueCents,
        gapBps = gapBps,
        minimumGapBps = 1_500,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Missing,
        externalSignalFairValueCents = externalSignalFairValueCents,
        externalSignalLowFairValueCents = externalSignalLowFairValueCents,
        externalSignalHighFairValueCents = externalSignalHighFairValueCents,
        weightedExternalSignalFairValueCents = weightedExternalSignalFairValueCents,
        externalSignalMaxAgeSeconds = 86_400,
        confidence = ConfidenceBand.Provisional,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
    )

    private fun correlatedCandles(offset: Long): List<HistoricalCandle> =
        (1..32).map { index ->
            val step = if (index % 2 == 0) index * 120 else index * 80
            candle(index, close = 10_000 + offset + step)
        }

    private fun candle(index: Int, close: Long) = HistoricalCandle(
        epochSeconds = index.toLong(),
        openCents = close,
        highCents = close + 10,
        lowCents = close - 10,
        closeCents = close,
        volume = 1_000,
    )

    private fun candleSeries(vararg closes: Long): List<HistoricalCandle> = closes.mapIndexed { index, close ->
        candle(index + 1, close)
    }

    private fun flatCandles(count: Int): List<HistoricalCandle> = (1..count).map { index ->
        candle(index, close = 10_000)
    }

    private fun horizonSummary(
        baseline: com.discountscreener.core.model.QuantLensHorizonBaseline,
    ) = HorizonSummary(
        horizon = baseline.horizon,
        sourceRange = baseline.sourceRange,
        lagCandles = baseline.lagCandles,
        sampleCount = baseline.sampleCount,
        medianAbsoluteMoveBps = baseline.medianAbsoluteMoveBps,
        p25AbsoluteMoveBps = baseline.p25AbsoluteMoveBps,
        p75AbsoluteMoveBps = baseline.p75AbsoluteMoveBps,
    )

    private data class HorizonSummary(
        val horizon: QuantLensHorizon,
        val sourceRange: ChartRange,
        val lagCandles: Int,
        val sampleCount: Int,
        val medianAbsoluteMoveBps: Int?,
        val p25AbsoluteMoveBps: Int?,
        val p75AbsoluteMoveBps: Int?,
    )

    private data class HorizonDegradedSummary(
        val horizon: QuantLensHorizon,
        val primaryStatus: QuantLensPrimaryStatus,
        val sampleCount: Int,
        val firstReason: QuantLensReasonCode,
        val medianAbsoluteMoveBps: Int?,
    )

    private fun comparable(
        symbol: String,
        upside: Int,
        evidence: Int,
        score: Int,
        trend: Int? = null,
        spread: Int? = null,
    ) = QuantLensComparable(
        symbol = symbol,
        valuationUpsideBps = upside,
        evidenceStrengthBps = evidence,
        opportunityScore = score,
        trendReliabilityBps = trend,
        evSpreadBps = spread,
    )

    private fun ComputationResult<QuantLensReport>.requireSuccess(): QuantLensReport = when (this) {
        is ComputationResult.Success -> value
        is ComputationResult.Error -> throw AssertionError("Expected Quant Lens success, got $failure")
    }
}
