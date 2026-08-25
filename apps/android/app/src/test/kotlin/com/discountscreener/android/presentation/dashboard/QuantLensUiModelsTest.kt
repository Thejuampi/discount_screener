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
            listOf("Valuation decision", "Signal quality", "Market overlap", "Price trend", "Typical moves", "Similar patterns"),
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
        evidenceStrength: QuantLensEvidenceStrength = QuantLensEvidenceStrength(
            primaryStatus = QuantLensPrimaryStatus.Sparse,
            band = EvidenceStrengthBand.Sparse,
            reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
        ),
        correlationRisk: QuantLensCorrelationRisk = QuantLensCorrelationRisk(
            primaryStatus = QuantLensPrimaryStatus.Unavailable,
            band = CorrelationRiskBand.Unavailable,
            reasonCodes = listOf(QuantLensReasonCode.InsufficientLocalHistory),
        ),
        trendReliability: QuantLensTrendReliability = QuantLensTrendReliability(
            primaryStatus = QuantLensPrimaryStatus.Insufficient,
            band = TrendReliabilityBand.Insufficient,
            reasonCodes = listOf(QuantLensReasonCode.InsufficientTrendSamples),
        ),
    ) = QuantLensReport(
        symbol = "ACME",
        selectedRange = ChartRange.Month,
        computedAtEpochSeconds = 1_777_000_000,
        modelVersion = QuantLensModelVersion.CURRENT,
        inputFingerprint = "fingerprint",
        primaryStatus = QuantLensPrimaryStatus.Available,
        evidenceStrength = evidenceStrength,
        expectedValueRange = expectedValueRange,
        correlationRisk = correlationRisk,
        trendReliability = trendReliability,
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
    fun ev_section_aligned_still_shows_both_dcf_and_analyst_rows() {
        val ev = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Available,
            band = ExpectedValueRangeBand.ScenarioWeighted,
            source = ExpectedValueRangeSource.Dcf,
            weightedFairValueCents = 11_500L,
            weightedUpsideBps = 1500,
            lowFairValueCents = 10_800L,
            highFairValueCents = 12_400L,
            modelLowFairValueCents = 10_800L,
            modelBaseFairValueCents = 11_500L,
            modelHighFairValueCents = 12_400L,
            analystLowFairValueCents = 10_500L,
            analystBaseFairValueCents = 11_200L,
            analystHighFairValueCents = 12_000L,
            disagreementBps = 264,
            reasonCodes = listOf(QuantLensReasonCode.CompleteScenarioAnchors),
        )
        val section = mapQuantLensReport(report(expectedValueRange = ev), 10_000L)!!
            .sections
            .first { it.lensId == QuantLensLensId.ExpectedValueRange }
        assertEquals(
            listOf("DCF base", "Analyst base", "DCF range", "Analyst range"),
            section.rows.map { it.first },
        )
    }

    @Test
    fun ev_section_disputed_shows_both_anchors_without_presenting_a_range() {
        val ev = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Disputed,
            band = ExpectedValueRangeBand.Disputed,
            modelLowFairValueCents = 1_152L,
            modelBaseFairValueCents = 1_708L,
            modelHighFairValueCents = 1_913L,
            analystLowFairValueCents = 20_700L,
            analystBaseFairValueCents = 31_500L,
            analystHighFairValueCents = 37_000L,
            disagreementBps = 15_000,
            reasonCodes = listOf(QuantLensReasonCode.ModelAnalystDisagreement),
        )
        val section = mapQuantLensReport(report(expectedValueRange = ev), 23_977L)!!
            .sections
            .first { it.lensId == QuantLensLensId.ExpectedValueRange }

        assertEquals("Disputed", section.chip.label)
        assertTrue(section.primaryLine.contains("no single estimate"))
        assertTrue(section.rows.any { it.first == "DCF base" })
        assertTrue(section.rows.any { it.first == "Analyst base" })
        assertEquals(null, section.evRailModel)
        assertTrue(section.footerChips.contains("Sources disagree"))
    }

    /**
     * The disputed band is where the card used to name a DCF number "Identity model".
     *
     * The policy returns this band before it picks a source, so `source` is null here. Naming the
     * family off that field printed the unnamed wording under a footer chip that said "DCF and
     * analyst". The rows and the primary line are asserted together because the defect was that
     * the two halves of one card disagreed.
     */
    @Test
    fun the_disputed_card_names_the_model_family_dcf_in_the_rows_and_in_the_line() {
        val ev = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Disputed,
            band = ExpectedValueRangeBand.Disputed,
            modelLowFairValueCents = 1_152L,
            modelBaseFairValueCents = 1_708L,
            modelHighFairValueCents = 1_913L,
            analystLowFairValueCents = 20_700L,
            analystBaseFairValueCents = 31_500L,
            analystHighFairValueCents = 37_000L,
            disagreementBps = 15_000,
            reasonCodes = listOf(QuantLensReasonCode.ModelAnalystDisagreement),
        )
        val section = mapQuantLensReport(report(expectedValueRange = ev), 23_977L)!!
            .sections
            .first { it.lensId == QuantLensLensId.ExpectedValueRange }
        assertEquals(
            "rows=DCF base line=DCF model",
            "rows=${section.rows.first().first} line=${if ("DCF model" in section.primaryLine) "DCF model" else section.primaryLine}",
        )
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

    // ── Which way a signal reads ─────────────────────────────────────────────

    /**
     * The defect the qualifier exists to fix, stated as a pair.
     *
     * Chip direction used to come from [QuantLensPrimaryStatus] — which answers "did the lens
     * compute?", not "is this good?". A clear downtrend and a clear uptrend both report `Available`,
     * so both would have been painted favourable. Same band, opposite movement, opposite reading:
     * either test alone would still pass on a constant, so both are here.
     */
    @Test
    fun a_clear_downtrend_reads_strongly_adverse() {
        assertEquals(
            QuantLensQualifier.StrongNegative,
            qualifierOf(report(trendReliability = trend(TrendReliabilityBand.Reliable, -1_200)), QuantLensLensId.TrendReliability),
        )
    }

    @Test
    fun a_clear_uptrend_reads_strongly_favourable() {
        assertEquals(
            QuantLensQualifier.StrongPositive,
            qualifierOf(report(trendReliability = trend(TrendReliabilityBand.Reliable, 1_200)), QuantLensLensId.TrendReliability),
        )
    }

    /** Movement the trend itself does not support is not a direction, however large it is. */
    @Test
    fun a_choppy_trend_reads_neutral_however_far_price_moved() {
        assertEquals(
            QuantLensQualifier.Neutral,
            qualifierOf(report(trendReliability = trend(TrendReliabilityBand.Noisy, 1_200)), QuantLensLensId.TrendReliability),
        )
    }

    /** The band says how emphatic; the support-versus-conflict split says which way. */
    @Test
    fun evidence_that_conflicts_more_than_it_supports_reads_adverse() {
        assertEquals(
            QuantLensQualifier.StrongNegative,
            qualifierOf(report(evidenceStrength = evidence(EvidenceStrengthBand.Strong, support = 1, conflict = 4)), QuantLensLensId.EvidenceStrength),
        )
    }

    @Test
    fun the_same_split_read_the_other_way_round_is_favourable() {
        assertEquals(
            QuantLensQualifier.StrongPositive,
            qualifierOf(report(evidenceStrength = evidence(EvidenceStrengthBand.Strong, support = 4, conflict = 1)), QuantLensLensId.EvidenceStrength),
        )
    }

    /**
     * Totality over the band, rather than one hand-picked case: a new band, or a band quietly
     * re-pointed, has to show up here.
     */
    @Test
    fun moving_with_the_market_is_the_only_overlap_reading_that_is_strongly_adverse() {
        assertEquals(
            listOf(CorrelationRiskBand.High),
            CorrelationRiskBand.values().toList().filter {
                qualifierOf(report(correlationRisk = correlation(it)), QuantLensLensId.CorrelationRisk) ==
                    QuantLensQualifier.StrongNegative
            },
        )
    }

    /**
     * Every "no reading" band across three lenses, in one list. Asserted together because the
     * failure mode is one of them defaulting to favourable while the others behave.
     */
    @Test
    fun a_lens_that_could_not_read_carries_no_direction() {
        val unmeasured = listOf(
            qualifierOf(report(evidenceStrength = evidence(EvidenceStrengthBand.Sparse)), QuantLensLensId.EvidenceStrength),
            qualifierOf(report(evidenceStrength = evidence(EvidenceStrengthBand.Unavailable)), QuantLensLensId.EvidenceStrength),
            qualifierOf(report(correlationRisk = correlation(CorrelationRiskBand.Sparse)), QuantLensLensId.CorrelationRisk),
            qualifierOf(report(correlationRisk = correlation(CorrelationRiskBand.Unavailable)), QuantLensLensId.CorrelationRisk),
            qualifierOf(report(trendReliability = trend(TrendReliabilityBand.Insufficient, 1_200)), QuantLensLensId.TrendReliability),
            qualifierOf(report(trendReliability = trend(TrendReliabilityBand.Unavailable, 1_200)), QuantLensLensId.TrendReliability),
        )

        assertEquals(emptyList<QuantLensQualifier>(), unmeasured.filter { it != QuantLensQualifier.Unknown })
    }

    /** An estimate whose pessimistic end is a loss is not an upside, whatever its best guess says. */
    @Test
    fun an_expected_value_range_that_straddles_zero_reads_neutral() {
        assertEquals(
            QuantLensQualifier.Neutral,
            qualifierOf(report(expectedValueRange = weightedEv(1_200, low = 9_600L, high = 12_400L)), QuantLensLensId.ExpectedValueRange, price = 10_000L),
        )
    }

    @Test
    fun a_material_upside_is_emphatic_and_a_slim_one_is_not() {
        assertEquals(
            listOf(QuantLensQualifier.StrongPositive, QuantLensQualifier.Positive),
            listOf(2_500, 500).map {
                qualifierOf(report(expectedValueRange = weightedEv(it, low = 10_100L, high = 12_400L)), QuantLensLensId.ExpectedValueRange, price = 10_000L)
            },
        )
    }

    /** The scale is symmetric: the same magnitudes, the other way round, read equally emphatic. */
    @Test
    fun a_material_downside_is_emphatic_and_a_slim_one_is_not() {
        assertEquals(
            listOf(QuantLensQualifier.StrongNegative, QuantLensQualifier.Negative),
            listOf(-2_500, -500).map {
                qualifierOf(report(expectedValueRange = weightedEv(it, low = 7_600L, high = 9_900L)), QuantLensLensId.ExpectedValueRange, price = 10_000L)
            },
        )
    }

    /** No expectation and an expectation of exactly nothing are both "no direction", not upside. */
    @Test
    fun an_expectation_that_measured_nothing_either_way_reads_neutral() {
        assertEquals(
            listOf(QuantLensQualifier.Neutral, QuantLensQualifier.Neutral),
            listOf(0, null).map {
                qualifierOf(report(expectedValueRange = weightedEv(it, low = 10_100L, high = 12_400L)), QuantLensLensId.ExpectedValueRange, price = 10_000L)
            },
        )
    }

    /**
     * The weaker band keeps the direction and drops the emphasis — one plus, not two. Both signs
     * are here because a band that only ever softened one side would still read as a bias.
     */
    @Test
    fun a_provisional_reading_keeps_its_direction_and_loses_its_emphasis() {
        assertEquals(
            listOf(QuantLensQualifier.Positive, QuantLensQualifier.Negative),
            listOf(4 to 1, 1 to 4).map { (support, conflict) ->
                qualifierOf(
                    report(evidenceStrength = evidence(EvidenceStrengthBand.Provisional, support, conflict)),
                    QuantLensLensId.EvidenceStrength,
                )
            },
        )
    }

    @Test
    fun a_moderate_trend_keeps_its_direction_and_loses_its_emphasis() {
        assertEquals(
            listOf(QuantLensQualifier.Positive, QuantLensQualifier.Negative),
            listOf(1_200, -1_200).map {
                qualifierOf(report(trendReliability = trend(TrendReliabilityBand.Moderate, it)), QuantLensLensId.TrendReliability)
            },
        )
    }

    /** A trend the lens rates clearly but never measured a movement for is not good news. */
    @Test
    fun a_trend_with_no_measured_movement_reads_neutral_however_reliable_the_band() {
        assertEquals(
            listOf(QuantLensQualifier.Neutral, QuantLensQualifier.Neutral),
            listOf(TrendReliabilityBand.Reliable, TrendReliabilityBand.Moderate).map {
                qualifierOf(report(trendReliability = trend(it, movementBps = null)), QuantLensLensId.TrendReliability)
            },
        )
    }

    /**
     * A lens that read clearly and found no direction is not the same as one that could not read:
     * these must be [QuantLensQualifier.Neutral], never [QuantLensQualifier.Unknown].
     */
    @Test
    fun a_lens_that_read_and_found_no_direction_is_neutral_rather_than_unread() {
        val measured = listOf(
            qualifierOf(report(evidenceStrength = evidence(EvidenceStrengthBand.Mixed, support = 2, conflict = 2)), QuantLensLensId.EvidenceStrength),
            qualifierOf(report(trendReliability = trend(TrendReliabilityBand.Flat, 0)), QuantLensLensId.TrendReliability),
            qualifierOf(report(evidenceStrength = evidence(EvidenceStrengthBand.Strong, support = 2, conflict = 2)), QuantLensLensId.EvidenceStrength),
        )

        assertEquals(emptyList<QuantLensQualifier>(), measured.filter { it != QuantLensQualifier.Neutral })
    }

    // ── Which way a row chip reads ───────────────────────────────────────────

    /**
     * The compact row state keeps a label but not the numbers under it — no signed movement, no
     * support/conflict split. "Clear trend" is therefore not enough to call it good news.
     */
    @Test
    fun a_row_label_that_carries_no_direction_reads_neutral_not_favourable() {
        val summary = summary(state(QuantLensLensId.TrendReliability, QuantLensRowLabel.TrendReliable))

        assertEquals(QuantLensQualifier.Neutral, mapRowQuantLensSummary(summary).single().qualifier)
    }

    @Test
    fun a_row_range_judged_from_its_pessimistic_end_is_not_emphatic() {
        val summary = summary(state(QuantLensLensId.ExpectedValueRange, QuantLensRowLabel.EvRange, low = 500, high = 9_000))

        assertEquals(QuantLensQualifier.Positive, mapRowQuantLensSummary(summary).single().qualifier)
    }

    /**
     * The row states that do carry a direction, all of them, in one list.
     *
     * [QuantLensRowLabel.CorrLow] is absent because it cannot reach a chip: the eligibility filter
     * in [mapRowQuantLensSummary] promotes only elevated and high overlap, so a row never shows
     * "moves independently". Its arm stays in the mapping to keep the `when` exhaustive.
     */
    @Test
    fun the_row_labels_that_carry_a_direction_of_their_own_read_it() {
        val directed = listOf(
            QuantLensLensId.CorrelationRisk to QuantLensRowLabel.CorrElevated,
            QuantLensLensId.CorrelationRisk to QuantLensRowLabel.CorrHigh,
            QuantLensLensId.ExpectedValueRange to QuantLensRowLabel.EvTension,
            QuantLensLensId.ExpectedValueRange to QuantLensRowLabel.EvDisputed,
        )

        assertEquals(
            listOf(
                QuantLensQualifier.Negative,
                QuantLensQualifier.StrongNegative,
                QuantLensQualifier.Negative,
                QuantLensQualifier.Negative,
            ),
            directed.map { (lensId, label) ->
                mapRowQuantLensSummary(summary(state(lensId, label, status = QuantLensPrimaryStatus.Available))).single().qualifier
            },
        )
    }

    /** The mirror of the rule above: nearer zero means the optimistic end when both are losses. */
    @Test
    fun a_row_range_wholly_below_zero_is_judged_from_its_optimistic_end() {
        val summary = summary(state(QuantLensLensId.ExpectedValueRange, QuantLensRowLabel.EvRange, low = -9_000, high = -500))

        assertEquals(QuantLensQualifier.Negative, mapRowQuantLensSummary(summary).single().qualifier)
    }

    /** A range label with no numbers under it cannot be read either way. */
    @Test
    fun a_row_range_with_a_missing_bound_reads_neutral() {
        val summary = summary(state(QuantLensLensId.ExpectedValueRange, QuantLensRowLabel.EvRange, low = 500, high = null))

        assertEquals(QuantLensQualifier.Neutral, mapRowQuantLensSummary(summary).single().qualifier)
    }

    @Test
    fun a_row_chip_for_a_lens_that_never_read_carries_no_direction() {
        val summary = summary(
            state(QuantLensLensId.EvidenceStrength, QuantLensRowLabel.EvidenceUnavailable, status = QuantLensPrimaryStatus.Unavailable),
        )

        assertEquals(QuantLensQualifier.Unknown, mapRowQuantLensSummary(summary).single().qualifier)
    }

    private fun qualifierOf(report: QuantLensReport, lensId: QuantLensLensId, price: Long? = null) =
        mapQuantLensReport(report, price)!!.sections.first { it.lensId == lensId }.chip.qualifier

    private fun trend(band: TrendReliabilityBand, movementBps: Int?) = QuantLensTrendReliability(
        primaryStatus = QuantLensPrimaryStatus.Available,
        band = band,
        movementBps = movementBps,
        reasonCodes = listOf(QuantLensReasonCode.HistoricalBaselineAvailable),
    )

    private fun evidence(band: EvidenceStrengthBand, support: Int = 0, conflict: Int = 0) = QuantLensEvidenceStrength(
        primaryStatus = QuantLensPrimaryStatus.Available,
        band = band,
        supportCount = support,
        conflictCount = conflict,
        reasonCodes = listOf(QuantLensReasonCode.HistoricalBaselineAvailable),
    )

    private fun correlation(band: CorrelationRiskBand) = QuantLensCorrelationRisk(
        primaryStatus = QuantLensPrimaryStatus.Available,
        band = band,
        reasonCodes = listOf(QuantLensReasonCode.HistoricalBaselineAvailable),
    )

    private fun weightedEv(weightedUpsideBps: Int?, low: Long, high: Long) = QuantLensExpectedValueRange(
        primaryStatus = QuantLensPrimaryStatus.Available,
        band = ExpectedValueRangeBand.ScenarioWeighted,
        weightedUpsideBps = weightedUpsideBps,
        lowFairValueCents = low,
        highFairValueCents = high,
        freshnessQualifier = QuantLensFreshnessQualifier.Fresh,
        reasonCodes = listOf(QuantLensReasonCode.HistoricalBaselineAvailable),
    )
}
