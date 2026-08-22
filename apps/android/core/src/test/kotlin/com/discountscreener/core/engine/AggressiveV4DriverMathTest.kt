package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalTimeseries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The annual-series math behind V4's Trend, Pulse, share-count and cycle-peak terms.
 *
 * Each test here is named for the defect it forbids. All of them failed against the engine that
 * paired adjacent *survivors* of a filter as if they were adjacent fiscal years and divided across
 * a negative base as if a ratio still meant growth there.
 */
class AggressiveV4DriverMathTest {

    // ── Sign flips ───────────────────────────────────────────────────────────

    /**
     * A loss maker narrowing its losses and turning profitable: net income +50 / −30 / −25 / −20,
     * Yahoo quarter EPS YoY at +4%. The only true reading of this company is "improving".
     *
     * The old math divided across negative bases. −30 → −25 printed as −16.7% and −25 → −20 as
     * −20%, so the annual sample said "collapsing", called the true +4% foreign to it, and Pulse —
     * the one term carrying the good news — was refused.
     */
    @Test
    fun a_loss_narrowing_turnaround_keeps_its_pulse() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(earningsGrowthBps = 400, trailingEpsCents = 500)),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(
                netIncome = dated(2021, 50.0, 2022, -30.0, 2023, -25.0, 2024, -20.0),
            ),
        ).factors.map { it.key }

        assertTrue("Pulse" in keys, "a turnaround's EPS growth must score: $keys")
    }

    /**
     * The rate itself must never form across a sign flip, whatever survives elsewhere. A base of
     * −10 under a latest of +10 prints −20 000 bps — the worst reading in the band — for a company
     * that just doubled its profit.
     */
    @Test
    fun a_transition_across_zero_does_not_become_a_rate() {
        var rates = positiveLevelTransitions(
            dated(2021, -10.0, 2022, 10.0, 2023, 12.0),
            maxYears = 5,
        )

        assertEquals(listOf(10.0 to 12.0), rates)
    }

    // ── Fiscal-year adjacency ────────────────────────────────────────────────

    /**
     * A missing fiscal year silently doubled an "annual" rate: 110 in 2021 against 169 in 2023 is
     * two years of growth read as one year's +53.6%. With the gap pair refused, only the one clean
     * transition survives, which is below the two-transition floor, so Trend is absent rather than
     * wrong.
     */
    @Test
    fun a_missing_fiscal_year_is_not_silent_annual_growth() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals()),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(
                revenue = dated(2020, 100.0, 2021, 110.0, 2023, 169.0),
            ),
        ).factors.map { it.key }

        assertFalse("Trend" in keys, "a gapped series must not print an annual rate: $keys")
    }

    /** The same refusal for the share-count pair: 970 in 2021 against 900 in 2023 is not a year. */
    @Test
    fun a_gap_in_the_share_series_is_not_one_annual_move() {
        var evidence = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals()),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(
                dilutedAverageShares = dated(2020, 1_000.0, 2021, 970.0, 2023, 900.0),
            ),
        )

        assertNull(evidence.score, "a gapped share pair must refuse, not score a five-year move as annual")
    }

    /** And the cycle-peak regime cannot classify a run whose every transition spans a gap. */
    @Test
    fun a_run_of_gapped_years_reports_no_regime() {
        var reading = cyclePeakReading(
            fundamentals = fundamentals(sectorKey = "energy", industryKey = "oil-gas-integrated"),
            timeseries = FundamentalTimeseries(
                revenue = dated(2021, 300.0, 2023, 360.0, 2025, 430.0),
                netIncome = dated(2021, 24.0, 2023, 30.0, 2025, 45.0),
            ),
            maxYears = 5,
        )

        assertNull(reading.regime, "no adjacent pair means no regime, not a secular-expansion stamp")
    }

    // ── The conflict flag ────────────────────────────────────────────────────

    /**
     * Trend +6% against Pulse +4% flagged "Pulse≠Trend" under the old ramp-sign test, because the
     * band midpoint sits at +5% and the two readings straddled it. Two healthy growers do not
     * disagree. Ten points of growth is the smallest gap this engine calls a conflict.
     */
    @Test
    fun a_small_trend_pulse_gap_is_not_a_conflict() {
        var signals = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(earningsGrowthBps = 400, trailingEpsCents = 500)),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(revenue = dated(2022, 100.0, 2023, 106.0, 2024, 112.36)),
        ).signals

        assertFalse("Pulse≠Trend" in signals, "+6% revenue against +4% EPS is agreement, not conflict: $signals")
    }

    /** The real disagreement the flag exists for keeps firing: +30% revenue against −11% EPS. */
    @Test
    fun a_real_trend_pulse_divergence_still_flags() {
        var signals = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(earningsGrowthBps = -1_100, trailingEpsCents = 500)),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(revenue = dated(2022, 100.0, 2023, 130.0, 2024, 169.0)),
        ).signals

        assertTrue("Pulse≠Trend" in signals)
    }

    /**
     * Two growers on the same side of the band midpoint can still disagree by more than the
     * threshold: Trend +20% against Pulse +9% is 1 100 bps apart with both ramps positive. The
     * old ramp-sign test stayed silent here, so this case is what forbids that revert.
     */
    @Test
    fun a_same_side_divergence_past_the_threshold_flags() {
        var signals = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(earningsGrowthBps = 900, trailingEpsCents = 500)),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(revenue = dated(2022, 100.0, 2023, 120.0, 2024, 144.0)),
        ).signals

        assertTrue("Pulse≠Trend" in signals, "+20% revenue against +9% EPS is 1100 bps apart: $signals")
    }

    /**
     * The boundary is inclusive: exactly V4_GROWTH_CONFLICT_BPS apart is a conflict. Trend sits
     * pinned at +15% and Pulse at the band's +5% midpoint, where its ramp reads zero — which the
     * old product test treated as silence.
     */
    @Test
    fun exactly_the_threshold_is_a_conflict() {
        var signals = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(earningsGrowthBps = 500, trailingEpsCents = 500)),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(revenue = dated(2022, 100.0, 2023, 115.0, 2024, 132.25)),
        ).signals

        assertTrue("Pulse≠Trend" in signals, "1500 − 500 = 1000 bps is not under the threshold: $signals")
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** Alternating year/value pairs, oldest first, ISO-dated like both providers date them. */
    private fun dated(vararg yearValue: Any): List<AnnualReportedValue> =
        yearValue.toList().chunked(2).map { (year, value) ->
            AnnualReportedValue(asOfDate = "$year-12-31", value = value as Double)
        }
}
