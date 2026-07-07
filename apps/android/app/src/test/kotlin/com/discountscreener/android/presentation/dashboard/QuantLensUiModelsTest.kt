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
import com.discountscreener.core.model.ExpectedValueRangeSource
import com.discountscreener.core.model.QuantLensFreshnessQualifier
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
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

        assertEquals(listOf("Strong signals", "+10%..+20% upside", "Moves together"), mapRowQuantLensSummary(summary).map { it.label })
    }

    @Test
    fun row_summary_hides_sparse_correlation_even_with_high_label() {
        val summary = summary(
            state(QuantLensLensId.EvidenceStrength, QuantLensRowLabel.EvidenceStrong),
            state(QuantLensLensId.CorrelationRisk, QuantLensRowLabel.CorrHigh, status = QuantLensPrimaryStatus.Sparse),
        )

        assertEquals(listOf("Strong signals"), mapRowQuantLensSummary(summary).map { it.label })
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

        assertEquals("No signals", mapRowQuantLensSummary(summary).single().label)
    }

    @Test
    fun detail_quant_lens_maps_horizon_context_section_after_trend() {
        val state = mapQuantLensReport(report())

        assertEquals(
            listOf("Signal quality", "Price estimate", "Market overlap", "Price trend", "Typical moves", "Similar patterns"),
            state?.sections?.map { it.title },
        )
    }

    @Test
    fun horizon_context_available_rows_show_self_contained_visible_text() {
        val section = mapQuantLensReport(report())?.sections?.first { it.lensId == QuantLensLensId.HorizonContext }

        assertEquals(
            HorizonSectionSnapshot(
                primaryLine = "How much this stock usually moves",
                rows = listOf(
                    "5m" to "±0.42% typical · 0.18%–0.91% usual range",
                    "1D" to "±1.50% typical · 0.80%–2.20% usual range",
                    "3M" to "±8.40% typical · 4.20%–12.30% usual range",
                ),
                footerChips = listOf("Based on price history", "Not a forecast"),
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

        assertEquals(listOf("Strong signals", "+10%..+20% upside"), mapRowQuantLensSummary(summary).map { it.label })
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
        expectedValueRange: QuantLensExpectedValueRange = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Sparse,
            band = ExpectedValueRangeBand.Sparse,
            reasonCodes = listOf(QuantLensReasonCode.MissingScenarioAnchors),
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
        expectedValueRange = expectedValueRange,
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

    @Test
    fun ev_section_scenario_weighted_positive_primary_line_and_rail() {
        val price = 10_000L // $100.00
        val low = 10_800L   // $108 -> +800 bps
        val weighted = 11_500L // $115 -> +1500 bps
        val high = 12_400L  // $124 -> +2400 bps
        val ev = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Available,
            band = ExpectedValueRangeBand.ScenarioWeighted,
            source = ExpectedValueRangeSource.Dcf,
            weightedFairValueCents = weighted,
            weightedUpsideBps = 1500,
            lowFairValueCents = low,
            highFairValueCents = high,
            spreadBps = 1600,
            freshnessQualifier = QuantLensFreshnessQualifier.Fresh,
            reasonCodes = listOf(QuantLensReasonCode.HistoricalBaselineAvailable),
        )
        var r = report(expectedValueRange = ev)
        val section = mapQuantLensReport(r, price)!!.sections.first { it.lensId == QuantLensLensId.ExpectedValueRange }
        assertNotNull(section.evRailModel)
        assertEquals(false, section.evRailModel!!.crossesZero)
        assertEquals(800, section.evRailModel!!.lowUpsideBps)
        assertEquals(2400, section.evRailModel!!.highUpsideBps)
    }

    @Test
    fun ev_section_scenario_weighted_crosses_zero_chip_and_flag() {
        val price = 10_000L
        val low = 9_600L  // -400 bps
        val weighted = 11_200L // +1200 bps
        val high = 12_400L // +2400 bps
        val ev = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Available,
            band = ExpectedValueRangeBand.ScenarioWeighted,
            weightedFairValueCents = weighted,
            weightedUpsideBps = 1200,
            lowFairValueCents = low,
            highFairValueCents = high,
            freshnessQualifier = QuantLensFreshnessQualifier.Fresh,
            reasonCodes = listOf(QuantLensReasonCode.HistoricalBaselineAvailable),
        )
        var r = report(expectedValueRange = ev)
        val section = mapQuantLensReport(r, price)!!.sections.first { it.lensId == QuantLensLensId.ExpectedValueRange }
        assertEquals("Mixed up/down", section.chip.label)
        assertNotNull(section.evRailModel)
        assertTrue(section.evRailModel!!.crossesZero)
    }

    @Test
    fun ev_section_reference_only_no_rail_dollar_rows() {
        val ev = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Sparse,
            band = ExpectedValueRangeBand.ReferenceOnly,
            lowFairValueCents = 9_500L,
            highFairValueCents = 11_000L,
            reasonCodes = listOf(QuantLensReasonCode.MissingScenarioAnchors),
        )
        var r = report(expectedValueRange = ev)
        val section = mapQuantLensReport(r, 10_000L)!!.sections.first { it.lensId == QuantLensLensId.ExpectedValueRange }
        assertNull(section.evRailModel)
        assertTrue(section.rows.isNotEmpty())
        assertTrue(section.rows.all { (_, v) -> v.contains("$") })
    }

    @Test
    fun ev_section_unavailable_empty_rows_no_rail() {
        val ev = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Unavailable,
            band = ExpectedValueRangeBand.Unavailable,
            reasonCodes = listOf(QuantLensReasonCode.MissingScenarioAnchors),
        )
        var r = report(expectedValueRange = ev)
        val section = mapQuantLensReport(r, 10_000L)!!.sections.first { it.lensId == QuantLensLensId.ExpectedValueRange }
        assertEquals("No price estimate available", section.primaryLine)
        assertTrue(section.rows.isEmpty())
        assertNull(section.evRailModel)
    }

    @Test
    fun ev_section_stale_footer_chip_and_rail_stale_flag() {
        val price = 10_000L
        val low = 10_800L
        val weighted = 11_500L
        val high = 12_400L
        val ev = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Available,
            band = ExpectedValueRangeBand.ScenarioWeighted,
            source = ExpectedValueRangeSource.Dcf,
            weightedFairValueCents = weighted,
            weightedUpsideBps = 1500,
            lowFairValueCents = low,
            highFairValueCents = high,
            spreadBps = 1600,
            freshnessQualifier = QuantLensFreshnessQualifier.Stale,
            reasonCodes = listOf(QuantLensReasonCode.HistoricalBaselineAvailable),
        )
        var r = report(expectedValueRange = ev)
        val section = mapQuantLensReport(r, price)!!.sections.first { it.lensId == QuantLensLensId.ExpectedValueRange }
        assertTrue(section.footerChips.any { it.contains("saved") })
        assertNotNull(section.evRailModel)
        assertTrue(section.evRailModel!!.isStale)
    }
}