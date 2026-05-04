package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.CorrelationRiskBand
import com.discountscreener.core.model.EvidenceStrengthBand
import com.discountscreener.core.model.ExpectedValueRangeBand
import com.discountscreener.core.model.QuantLensCorrelationRisk
import com.discountscreener.core.model.QuantLensEvidenceStrength
import com.discountscreener.core.model.QuantLensExpectedValueRange
import com.discountscreener.core.model.QuantLensHorizon
import com.discountscreener.core.model.QuantLensHorizonBaseline
import com.discountscreener.core.model.QuantLensHorizonContext
import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.model.QuantLensLensRowState
import com.discountscreener.core.model.QuantLensModelVersion
import com.discountscreener.core.model.QuantLensPrimaryStatus
import com.discountscreener.core.model.QuantLensReasonCode
import com.discountscreener.core.model.QuantLensReport
import com.discountscreener.core.model.QuantLensRowLabel
import com.discountscreener.core.model.QuantLensRowSummary
import com.discountscreener.core.model.QuantLensSimilarSetups
import com.discountscreener.core.model.QuantLensTrendReliability
import com.discountscreener.core.model.SimilarSetupsBand
import com.discountscreener.core.model.TrendReliabilityBand
import org.junit.Assert.assertEquals
import org.junit.Test

class QuantLensUiModelsTest {
    @Test
    fun missing_row_summary_maps_to_loading_chip() {
        assertEquals(listOf("Lens loading"), mapRowQuantLensSummary(null).map { it.label })
    }

    @Test
    fun row_summary_chips_follow_priority_order_and_cap() {
        val summary = summary(
            state(QuantLensLensId.SimilarSetups, QuantLensRowLabel.SimilarAvailable),
            state(QuantLensLensId.TrendReliability, QuantLensRowLabel.TrendModerate),
            state(QuantLensLensId.CorrelationRisk, QuantLensRowLabel.CorrHigh),
            state(QuantLensLensId.ExpectedValueRange, QuantLensRowLabel.EvRange, low = 1_000, high = 2_000),
            state(QuantLensLensId.EvidenceStrength, QuantLensRowLabel.EvidenceStrong),
        )

        assertEquals(listOf("Evidence strong", "EV +10%..+20%", "Corr high"), mapRowQuantLensSummary(summary).map { it.label })
    }

    @Test
    fun row_summary_hides_sparse_correlation_even_with_high_label() {
        val summary = summary(
            state(QuantLensLensId.EvidenceStrength, QuantLensRowLabel.EvidenceStrong),
            state(QuantLensLensId.CorrelationRisk, QuantLensRowLabel.CorrHigh, status = QuantLensPrimaryStatus.Sparse),
        )

        assertEquals(listOf("Evidence strong"), mapRowQuantLensSummary(summary).map { it.label })
    }

    @Test
    fun row_summary_uses_structured_label_over_reason_codes() {
        val summary = summary(
            state(
                QuantLensLensId.EvidenceStrength,
                QuantLensRowLabel.EvidenceUnavailable,
                status = QuantLensPrimaryStatus.Unavailable,
                reason = QuantLensReasonCode.CompleteScenarioAnchors,
            ),
        )

        assertEquals("Evidence unavailable", mapRowQuantLensSummary(summary).single().label)
    }

    @Test
    fun detail_quant_lens_maps_horizon_context_section_after_trend() {
        val state = mapQuantLensReport(report())

        assertEquals(
            listOf("Evidence strength", "Expected value range", "Correlation risk", "Trend reliability", "Horizon context", "Similar setups"),
            state?.sections?.map { it.title },
        )
    }

    @Test
    fun horizon_context_available_rows_show_self_contained_visible_text() {
        val section = mapQuantLensReport(report())?.sections?.first { it.lensId == QuantLensLensId.HorizonContext }

        assertEquals(
            HorizonSectionSnapshot(
                primaryLine = "Historical baseline from loaded candles",
                rows = listOf(
                    "5m" to "5m · Median 0.42% · P25-P75 0.18%-0.91% · 72 windows",
                    "1D" to "1D · Median 1.50% · P25-P75 0.80%-2.20% · 21 windows",
                    "3M" to "3M · Median 8.40% · P25-P75 4.20%-12.30% · 58 windows",
                ),
                footerChips = listOf("Historical context", "No forecast"),
            ),
            section?.let { HorizonSectionSnapshot(it.primaryLine, it.rows, it.footerChips) },
        )
    }

    @Test
    fun horizon_context_degraded_rows_use_loaded_candle_copy_without_trading_language() {
        val section = mapQuantLensReport(
            report(
                horizonContext = QuantLensHorizonContext(
                    primaryStatus = QuantLensPrimaryStatus.Insufficient,
                    horizons = listOf(
                        horizon(QuantLensHorizon.FiveMinutes, QuantLensPrimaryStatus.Insufficient, ChartRange.Day, 1, 9),
                        horizon(QuantLensHorizon.OneDay, QuantLensPrimaryStatus.Unavailable, ChartRange.Month, 1, 0),
                        horizon(QuantLensHorizon.ThreeMonths, QuantLensPrimaryStatus.Unavailable, ChartRange.FiveYears, 3, 0),
                    ),
                    reasonCodes = listOf(QuantLensReasonCode.InsufficientHorizonSamples, QuantLensReasonCode.MissingHorizonCandles),
                ),
            ),
        )?.sections?.first { it.lensId == QuantLensLensId.HorizonContext }
        val forbidden = listOf("predict", "target", "probability", "buy", "sell", "hold")
        val text = listOfNotNull(section?.title, section?.primaryLine, section?.rows?.joinToString(), section?.footerChips?.joinToString()).joinToString(" ")

        assertEquals(emptyList<String>(), forbidden.filter { text.contains(it, ignoreCase = true) })
    }

    @Test
    fun row_summary_does_not_promote_horizon_context_chip() {
        val summary = summary(
            horizonState(),
            state(QuantLensLensId.EvidenceStrength, QuantLensRowLabel.EvidenceStrong),
            state(QuantLensLensId.ExpectedValueRange, QuantLensRowLabel.EvRange, low = 1_000, high = 2_000),
        )

        assertEquals(listOf("Evidence strong", "EV +10%..+20%"), mapRowQuantLensSummary(summary).map { it.label })
    }

    private fun summary(vararg states: QuantLensLensRowState) = QuantLensRowSummary(
        symbol = "ACME",
        fingerprint = "fingerprint",
        lensStates = states.toList(),
    )

    private fun state(
        lensId: QuantLensLensId,
        label: QuantLensRowLabel,
        status: QuantLensPrimaryStatus = QuantLensPrimaryStatus.Available,
        reason: QuantLensReasonCode = QuantLensReasonCode.ScaffoldPending,
        low: Int? = null,
        high: Int? = null,
    ) = QuantLensLensRowState(
        lensId = lensId,
        primaryStatus = status,
        band = label.name,
        label = label,
        reasonCodes = listOf(reason),
        evLowUpsideBps = low,
        evHighUpsideBps = high,
    )

    private fun horizonState() = QuantLensLensRowState(
        lensId = QuantLensLensId.HorizonContext,
        primaryStatus = QuantLensPrimaryStatus.Available,
        band = "HistoricalBaselineAvailable",
        label = null,
        reasonCodes = listOf(QuantLensReasonCode.HistoricalBaselineAvailable),
    )

    private fun report(
        horizonContext: QuantLensHorizonContext = QuantLensHorizonContext(
            primaryStatus = QuantLensPrimaryStatus.Available,
            horizons = listOf(
                horizon(QuantLensHorizon.FiveMinutes, QuantLensPrimaryStatus.Available, ChartRange.Day, 1, 72, 42, 18, 91),
                horizon(QuantLensHorizon.OneDay, QuantLensPrimaryStatus.Available, ChartRange.Month, 1, 21, 150, 80, 220),
                horizon(QuantLensHorizon.ThreeMonths, QuantLensPrimaryStatus.Available, ChartRange.FiveYears, 3, 58, 840, 420, 1_230),
            ),
            reasonCodes = listOf(QuantLensReasonCode.HistoricalBaselineAvailable),
        ),
    ) = QuantLensReport(
        symbol = "ACME",
        selectedRange = ChartRange.Month,
        computedAtEpochSeconds = 1_777_000_000,
        modelVersion = QuantLensModelVersion.CURRENT,
        inputFingerprint = "fingerprint",
        primaryStatus = QuantLensPrimaryStatus.Available,
        evidenceStrength = QuantLensEvidenceStrength(
            primaryStatus = QuantLensPrimaryStatus.Sparse,
            band = EvidenceStrengthBand.Sparse,
            reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
        ),
        expectedValueRange = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Sparse,
            band = ExpectedValueRangeBand.Sparse,
            reasonCodes = listOf(QuantLensReasonCode.MissingScenarioAnchors),
        ),
        correlationRisk = QuantLensCorrelationRisk(
            primaryStatus = QuantLensPrimaryStatus.Unavailable,
            band = CorrelationRiskBand.Unavailable,
            reasonCodes = listOf(QuantLensReasonCode.InsufficientLocalHistory),
        ),
        trendReliability = QuantLensTrendReliability(
            primaryStatus = QuantLensPrimaryStatus.Insufficient,
            band = TrendReliabilityBand.Insufficient,
            reasonCodes = listOf(QuantLensReasonCode.InsufficientTrendSamples),
        ),
        horizonContext = horizonContext,
        similarSetups = QuantLensSimilarSetups(
            primaryStatus = QuantLensPrimaryStatus.Sparse,
            band = SimilarSetupsBand.Sparse,
            reasonCodes = listOf(QuantLensReasonCode.InsufficientComparables),
        ),
    )

    private fun horizon(
        horizon: QuantLensHorizon,
        status: QuantLensPrimaryStatus,
        sourceRange: ChartRange,
        lagCandles: Int,
        sampleCount: Int,
        medianAbsoluteMoveBps: Int? = null,
        p25AbsoluteMoveBps: Int? = null,
        p75AbsoluteMoveBps: Int? = null,
    ) = QuantLensHorizonBaseline(
        horizon = horizon,
        primaryStatus = status,
        sourceRange = sourceRange,
        lagCandles = lagCandles,
        sampleCount = sampleCount,
        medianAbsoluteMoveBps = medianAbsoluteMoveBps,
        p25AbsoluteMoveBps = p25AbsoluteMoveBps,
        p75AbsoluteMoveBps = p75AbsoluteMoveBps,
        reasonCodes = listOf(
            when (status) {
                QuantLensPrimaryStatus.Available -> QuantLensReasonCode.HistoricalBaselineAvailable
                QuantLensPrimaryStatus.Insufficient -> QuantLensReasonCode.InsufficientHorizonSamples
                else -> QuantLensReasonCode.MissingHorizonCandles
            },
        ),
    )

    private data class HorizonSectionSnapshot(
        val primaryLine: String,
        val rows: List<Pair<String, String>>,
        val footerChips: List<String>,
    )
}