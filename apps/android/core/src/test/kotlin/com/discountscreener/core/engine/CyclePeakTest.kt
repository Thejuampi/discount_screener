package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalTimeseries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The cycle-peak penalty fires on two conditions at once, and the pair is the whole design.
 *
 * The first group of tests below is the one that matters most: it shows the conjunction can be
 * satisfied by a real oil major's five years, which is the only proof that the rule is not a
 * property nothing can ever have. The rest hold each half of it, and the one-sided shape.
 */
class CyclePeakTest {

    // ── The conjunction is reachable ─────────────────────────────────────────

    /**
     * An integrated oil producer's own numbers, rounded to the billion: revenue 285 / 413 / 344 /
     * 320 / 420 and net income 23 / 55.7 / 36 / 33.7 / 50.
     *
     * Two of the four annual moves are down, which is what [DriverRegime.CyclicalOrTransition]
     * asks for, and the last year's 11.9% margin beats three of the four before it. Three of four
     * is a percentile of 7 500, half the ramp, so the penalty is half of eight.
     */
    @Test
    fun an_oil_major_at_the_top_of_its_own_five_years_pays_the_penalty() {
        assertEquals(4, oilMajor().penaltyPoints)
    }

    @Test
    fun the_same_run_reads_as_a_cycle_and_not_as_growth() {
        assertEquals(DriverRegime.CyclicalOrTransition, oilMajor().regime)
    }

    /** The best margin of the window, beating all four, is the whole penalty. */
    @Test
    fun the_best_margin_of_the_window_costs_the_whole_penalty() {
        assertEquals(8, oilMajor(latestIncome = 60.0).penaltyPoints)
    }

    // ── Each half of the conjunction, alone, buys nothing ────────────────────

    /**
     * The same five years under a software industry key. Nothing about the company changed; the
     * only difference is that the beta policy does not call this industry through-cycle.
     */
    @Test
    fun the_same_peak_costs_nothing_in_an_industry_that_is_not_through_cycle() {
        assertEquals(0, oilMajor(industryKey = "software-infrastructure").penaltyPoints)
    }

    /**
     * A through-cycle industry whose revenue only ever climbed. Revenue 300 / 320 / 340 / 360 /
     * 380 gives four positive moves, so the regime is not cyclical and the peak reading is the
     * artefact of a short window rather than a cycle.
     */
    @Test
    fun a_through_cycle_industry_with_a_steady_run_pays_nothing() {
        assertEquals(0, steadyProducer().penaltyPoints)
    }

    /** And the reason is named, so a reading of zero can be told apart from a reading of nothing. */
    @Test
    fun the_steady_run_still_reports_its_industry_as_through_cycle() {
        assertEquals(true, steadyProducer().throughCycleIndustry)
    }

    // ── One-sided ────────────────────────────────────────────────────────────

    /**
     * The lowest margin of the window pays nothing. Over five points a trough and a decline look
     * the same, and paying a name for the bottom of its range would be the same extrapolation
     * error the penalty exists to catch, pointed the other way.
     */
    @Test
    fun the_worst_margin_of_the_window_is_not_rewarded() {
        assertEquals(0, oilMajor(latestIncome = 20.0).penaltyPoints)
    }

    /** The middle of the window is the knee: at the median exactly, the penalty is still zero. */
    @Test
    fun a_margin_at_the_middle_of_its_window_pays_nothing() {
        assertEquals(0, oilMajor(latestIncome = 36.5).penaltyPoints)
    }

    // ── The window itself ────────────────────────────────────────────────────

    /**
     * Two margins have no middle. Every two-point window would read as a peak or a trough, so a
     * window that short reports nothing instead of reporting a maximum.
     */
    @Test
    fun two_years_of_margin_are_not_a_window() {
        assertNull(shortHistory().marginPercentileBps)
    }

    @Test
    fun a_window_too_short_to_measure_costs_nothing() {
        assertEquals(0, shortHistory().penaltyPoints)
    }

    /**
     * A year with revenue but no net income is dropped from the margin window rather than counted
     * as a zero margin, which would plant a false trough under every later year.
     */
    @Test
    fun a_year_missing_its_net_income_leaves_the_window() {
        assertEquals(6_666, oilMajor(dropIncomeYear = "2023-12-31").marginPercentileBps)
    }

    // ── The industry is matched by key, not by sector ────────────────────────

    /**
     * Semiconductors are a cyclical business and they sit under the `technology` sector, whose
     * entry is `software_technology`. A sector-keyed lookup would answer for software. The key is
     * read first so the answer is the semiconductor entry — which, as the policy stands, is not
     * marked through-cycle either. Both facts are here on purpose.
     */
    @Test
    fun a_semiconductor_is_read_by_its_industry_and_not_by_the_technology_sector() {
        assertEquals(false, semiconductor().throughCycleIndustry)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun oilMajor(
        latestIncome: Double = 50.0,
        industryKey: String = "oil-gas-integrated",
        dropIncomeYear: String? = null,
    ) = cyclePeakReading(
        fundamentals = fundamentals(sectorKey = "energy", industryKey = industryKey),
        timeseries = timeseries(
            revenue = listOf(285.0, 413.0, 344.0, 320.0, 420.0),
            netIncome = listOf(23.0, 55.7, 36.0, 33.7, latestIncome),
            dropIncomeYear = dropIncomeYear,
        ),
        maxYears = 5,
    )

    private fun steadyProducer() = cyclePeakReading(
        fundamentals = fundamentals(sectorKey = "energy", industryKey = "oil-gas-integrated"),
        timeseries = timeseries(
            revenue = listOf(300.0, 320.0, 340.0, 360.0, 380.0),
            netIncome = listOf(24.0, 26.0, 28.0, 30.0, 45.0),
        ),
        maxYears = 5,
    )

    private fun semiconductor() = cyclePeakReading(
        fundamentals = fundamentals(sectorKey = "technology", industryKey = "semiconductors"),
        timeseries = timeseries(
            revenue = listOf(285.0, 413.0, 344.0, 320.0, 420.0),
            netIncome = listOf(23.0, 55.7, 36.0, 33.7, 50.0),
        ),
        maxYears = 5,
    )

    private fun shortHistory() = cyclePeakReading(
        fundamentals = fundamentals(sectorKey = "energy", industryKey = "oil-gas-integrated"),
        timeseries = timeseries(revenue = listOf(300.0, 420.0), netIncome = listOf(24.0, 60.0)),
        maxYears = 5,
    )

    private fun timeseries(
        revenue: List<Double>,
        netIncome: List<Double>,
        dropIncomeYear: String? = null,
    ): FundamentalTimeseries {
        var years = (2026 - revenue.size until 2026).map { "$it-12-31" }
        return FundamentalTimeseries(
            revenue = years.zip(revenue) { year, value -> AnnualReportedValue(year, value) },
            netIncome = years.zip(netIncome) { year, value -> AnnualReportedValue(year, value) }
                .filterNot { it.asOfDate == dropIncomeYear },
        )
    }
}
