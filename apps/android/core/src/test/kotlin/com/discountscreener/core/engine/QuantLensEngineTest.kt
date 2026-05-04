package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
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
import com.discountscreener.core.model.QuantLensInput
import com.discountscreener.core.model.QuantLensModelVersion
import com.discountscreener.core.model.QuantLensPrimaryStatus
import com.discountscreener.core.model.QuantLensReasonCode
import com.discountscreener.core.model.SimilarSetupsBand
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.TrendReliabilityBand
import kotlin.test.Test
import kotlin.test.assertEquals

class QuantLensEngineTest {
    @Test
    fun scaffold_report_contains_all_five_explicit_sections() {
        val report = QuantLensEngine.analyze(minimalInput())

        assertEquals(QuantLensPrimaryStatus.Sparse, report.evidenceStrength.primaryStatus)
        assertEquals(QuantLensPrimaryStatus.Sparse, report.expectedValueRange.primaryStatus)
        assertEquals(QuantLensPrimaryStatus.Unavailable, report.correlationRisk.primaryStatus)
        assertEquals(QuantLensPrimaryStatus.Insufficient, report.trendReliability.primaryStatus)
        assertEquals(QuantLensPrimaryStatus.Sparse, report.similarSetups.primaryStatus)
    }

    @Test
    fun scaffold_report_keeps_domain_bands_for_every_section() {
        val report = QuantLensEngine.analyze(minimalInput())

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
        val report = QuantLensEngine.analyze(minimalInput())

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
        )

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
        )

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
        )

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
        )

        assertEquals(QuantLensPrimaryStatus.Available, report.trendReliability.primaryStatus)
        assertEquals(TrendReliabilityBand.Flat, report.trendReliability.band)
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
        )

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
        )

        assertEquals(QuantLensPrimaryStatus.Sparse, report.similarSetups.primaryStatus)
        assertEquals(2, report.similarSetups.qualifyingComparableCount)
        assertEquals(emptyList(), report.similarSetups.matches)
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
        externalSignalLowFairValueCents: Long? = null,
        externalSignalFairValueCents: Long? = null,
        externalSignalHighFairValueCents: Long? = null,
    ) = SymbolDetail(
        symbol = "ACME",
        profitable = true,
        marketPriceCents = 10_000,
        intrinsicValueCents = 12_000,
        gapBps = 1_667,
        minimumGapBps = 1_500,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Missing,
        externalSignalFairValueCents = externalSignalFairValueCents,
        externalSignalLowFairValueCents = externalSignalLowFairValueCents,
        externalSignalHighFairValueCents = externalSignalHighFairValueCents,
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
}
