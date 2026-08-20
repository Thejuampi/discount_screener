package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * V4 splits the old single Growth term. Pulse is Yahoo quarter EPS YoY.
 * Trend is the median of recent annual revenue YoY. Each carries half the old weight.
 */
class AggressiveV4GrowthTest {

    /**
     *  0% quarter EPS YoY sits at the middle of [-5%, +15%].
     *  ramp −0.5 × 6 / 110 × 100 → −3
     */
    @Test
    fun pulse_at_zero_scores_the_middle_of_the_old_growth_band() {
        assertEquals(-3, pulseScore(earningsGrowthBps = 0))
    }

    /** The old V4 term used weight 12. Pulse alone must not keep that full swing. */
    @Test
    fun pulse_at_the_floor_is_half_the_old_growth_swing() {
        assertEquals(-5, pulseScore(earningsGrowthBps = -1_100))
    }

    /**
     * Three flat revenue years give two 0% transitions. Same band as Pulse.
     * median 0 → −3. One year is not a franchise reading.
     */
    @Test
    fun trend_from_flat_revenue_uses_the_median_of_two_transitions() {
        assertEquals(-3, trendScore(100.0, 100.0, 100.0))
    }

    /** Two annual points are one transition. Trend needs two. */
    @Test
    fun one_revenue_transition_is_not_a_trend() {
        assertNull(trendScore(100.0, 130.0))
    }

    /**
     * MELI-class: quarter EPS −11%, revenue +30% and +30%.
     * Pulse −5 + Trend +5 = 0. The old term would have reported −11.
     */
    @Test
    fun a_revenue_boom_and_an_eps_drop_cancel_instead_of_saturating_negative() {
        var evidence = OpportunityEngine.aggressiveV4FundamentalsScore(
            detail(earningsGrowthBps = -1_100, trailingEpsCents = 500),
            sectorBenchmarks = null,
            timeseries = revenueSeries(100.0, 130.0, 169.0),
        )

        assertEquals(0, evidence.score)
    }

    @Test
    fun a_revenue_boom_and_an_eps_drop_flag_the_disagreement() {
        var evidence = OpportunityEngine.aggressiveV4FundamentalsScore(
            detail(earningsGrowthBps = -1_100, trailingEpsCents = 500),
            sectorBenchmarks = null,
            timeseries = revenueSeries(100.0, 130.0, 169.0),
        )

        assertEquals(listOf("Trend++", "Pulse--", "Pulse≠Trend"), evidence.signals)
    }

    /**
     * The flag has to be a factor and not only a signal. The score tab reads `factors` whenever
     * that list is non-empty, and it always is here, so a flag that reached only `signals` would be
     * invisible on every row that scored anything at all.
     */
    @Test
    fun the_disagreement_is_a_factor_and_not_only_a_signal() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            detail(earningsGrowthBps = -1_100, trailingEpsCents = 500),
            sectorBenchmarks = null,
            timeseries = revenueSeries(100.0, 130.0, 169.0),
        ).factors.map { it.key }

        assertEquals(true, "Pulse≠Trend" in keys)
    }

    @Test
    fun pulse_carries_the_yahoo_rate_it_scored() {
        var factor = OpportunityEngine.aggressiveV4FundamentalsScore(
            detail(earningsGrowthBps = -1_100, trailingEpsCents = 500),
            sectorBenchmarks = null,
        ).factors.single()

        assertEquals(-1_100, factor.inputBps)
    }

    @Test
    fun trend_carries_the_median_revenue_rate_it_scored() {
        var factor = OpportunityEngine.aggressiveV4FundamentalsScore(
            detail(),
            sectorBenchmarks = null,
            timeseries = revenueSeries(100.0, 100.0, 100.0),
        ).factors.single()

        assertEquals(0, factor.inputBps)
    }

    @Test
    fun a_near_zero_eps_base_refuses_pulse() {
        assertNull(pulseScore(earningsGrowthBps = -1_100, trailingEpsCents = 9))
    }

    @Test
    fun ten_cents_of_eps_is_enough_of_a_base_to_score_pulse() {
        assertEquals(-5, pulseScore(earningsGrowthBps = -1_100, trailingEpsCents = 10))
    }

    @Test
    fun missing_trailing_eps_refuses_pulse() {
        assertNull(pulseScore(earningsGrowthBps = -1_100, trailingEpsCents = null))
    }

    /**
     * Four years of ~5% net-income growth. Yahoo at −50% is another population.
     * Pulse must drop rather than take the full negative ramp.
     */
    @Test
    fun a_yahoo_rate_foreign_to_annual_net_income_is_refused() {
        var evidence = OpportunityEngine.aggressiveV4FundamentalsScore(
            detail(earningsGrowthBps = -5_000, trailingEpsCents = 500),
            sectorBenchmarks = null,
            timeseries = netIncomeSeries(100.0, 105.0, 110.0, 115.0),
        )

        assertNull(evidence.score)
    }

    @Test
    fun v4_does_not_emit_the_old_growth_key() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            detail(earningsGrowthBps = 0, trailingEpsCents = 500),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertEquals(listOf("Pulse"), keys)
    }

    @Test
    fun v3_keeps_the_single_growth_term() {
        var keys = OpportunityEngine.aggressiveV3FundamentalsScore(
            baseDetail(fundamentals = fundamentals(earningsGrowthBps = 0)),
        ).factors.map { it.key }

        assertEquals(listOf("Growth"), keys)
    }

    @Test
    fun the_list_reads_revenue_from_the_context() {
        var engine = ReportingEngine()
        engine.ingestSnapshot(
            MarketSnapshot(symbol = "SUT", profitable = true, marketPriceCents = 8_000, intrinsicValueCents = 10_000),
        )
        engine.ingestFundamentals(FundamentalSnapshot(symbol = "SUT", sectorName = "Technology"))

        var context = OpportunityContext(
            scoringModel = OpportunityScoringModel.AggressiveV4,
            timeseriesBySymbol = mapOf("SUT" to revenueSeries(100.0, 100.0, 100.0)),
        )

        assertEquals(-3, OpportunityEngine.buildRows(engine, context).single().fundamentalsScore)
    }

    /**
     * Trend is read across the last filed year, so a write-down inside that year is part of the
     * rate it reports. The mark costs no points and says the reading stands on a dirty year.
     */
    @Test
    fun a_write_down_in_the_last_filed_year_marks_the_growth_reading() {
        assertEquals(listOf("Trend-", "Charges"), chargeSignals(charge = 30.0))
    }

    @Test
    fun a_year_whose_charge_is_small_leaves_the_growth_reading_unmarked() {
        assertEquals(listOf("Trend-"), chargeSignals(charge = 5.0))
    }

    private fun chargeSignals(charge: Double) = OpportunityEngine.aggressiveV4FundamentalsScore(
        detail(),
        sectorBenchmarks = null,
        timeseries = revenueSeries(100.0, 100.0, 100.0).copy(
            operatingIncome = annual(80.0, 85.0, 90.0),
            nonRecurringCharges = listOf(AnnualReportedValue("2022-12-31", charge)),
        ),
    ).signals

    private fun pulseScore(earningsGrowthBps: Int, trailingEpsCents: Long? = 500) =
        OpportunityEngine.aggressiveV4FundamentalsScore(
            detail(earningsGrowthBps = earningsGrowthBps, trailingEpsCents = trailingEpsCents),
            sectorBenchmarks = null,
        ).score

    private fun trendScore(vararg revenue: Double) = OpportunityEngine
        .aggressiveV4FundamentalsScore(detail(), sectorBenchmarks = null, timeseries = revenueSeries(*revenue))
        .score

    private fun revenueSeries(vararg revenue: Double) = FundamentalTimeseries(
        revenue = annual(*revenue),
    )

    private fun netIncomeSeries(vararg netIncome: Double) = FundamentalTimeseries(
        netIncome = annual(*netIncome),
    )

    private fun annual(vararg values: Double) = values.mapIndexed { index, value ->
        AnnualReportedValue(asOfDate = "202${index}-12-31", value = value)
    }

    private fun detail(
        earningsGrowthBps: Int? = null,
        trailingEpsCents: Long? = null,
    ) = SymbolDetail(
        symbol = "SUT",
        profitable = true,
        marketPriceCents = 19_950,
        intrinsicValueCents = 25_000,
        gapBps = 2_000,
        minimumGapBps = 1_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalMaxAgeSeconds = 86_400,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
        fundamentals = FundamentalSnapshot(
            symbol = "SUT",
            earningsGrowthBps = earningsGrowthBps,
            trailingEpsCents = trailingEpsCents,
        ),
    )
}
