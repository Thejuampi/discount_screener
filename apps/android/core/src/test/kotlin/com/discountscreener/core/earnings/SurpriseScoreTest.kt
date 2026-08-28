package com.discountscreener.core.earnings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SurpriseScoreTest {

    @Test
    fun a_beat_of_one_full_dispersion_scores_one_whole_unit() {
        assertEquals(10_000, surpriseScoreBps(244L, pre(consensus = 240L, low = 236L, high = 244L)))
    }

    @Test
    fun a_miss_scores_the_same_size_with_the_sign_turned_around() {
        assertEquals(-10_000, surpriseScoreBps(236L, pre(consensus = 240L, low = 236L, high = 244L)))
    }

    @Test
    fun a_report_that_landed_on_the_consensus_scores_nothing() {
        assertEquals(0, surpriseScoreBps(240L, pre(consensus = 240L, low = 236L, high = 244L)))
    }

    @Test
    fun a_wider_panel_makes_the_same_beat_a_smaller_surprise() {
        assertEquals(5_000, surpriseScoreBps(244L, pre(consensus = 240L, low = 232L, high = 248L)))
    }

    @Test
    fun a_panel_that_all_said_the_same_number_divides_by_nothing_and_reports_nothing() {
        assertNull(surpriseScoreBps(244L, pre(consensus = 240L, low = 240L, high = 240L)))
    }

    @Test
    fun a_report_with_no_actual_eps_scores_nothing() {
        assertNull(surpriseScoreBps(null, pre(consensus = 240L, low = 236L, high = 244L)))
    }

    @Test
    fun a_report_nobody_estimated_scores_nothing() {
        assertNull(surpriseScoreBps(244L, pre(consensus = null, low = 236L, high = 244L)))
    }

    @Test
    fun a_consensus_with_no_spread_on_file_scores_nothing() {
        assertNull(surpriseScoreBps(244L, pre(consensus = 240L, low = null, high = null)))
    }

    @Test
    fun revenue_over_the_consensus_reads_back_as_the_share_it_beat_it_by() {
        assertEquals(1_000, revenueSurpriseBps(22_000L, pre(revenue = 20_000L)))
    }

    @Test
    fun revenue_under_the_consensus_reads_back_negative() {
        assertEquals(-1_000, revenueSurpriseBps(18_000L, pre(revenue = 20_000L)))
    }

    @Test
    fun a_revenue_consensus_of_zero_is_never_divided_by() {
        assertNull(revenueSurpriseBps(22_000L, pre(revenue = 0L)))
    }

    @Test
    fun a_quarter_that_reported_no_revenue_scores_nothing() {
        assertNull(revenueSurpriseBps(null, pre(revenue = 20_000L)))
    }

    private fun pre(
        consensus: Long? = null,
        low: Long? = null,
        high: Long? = null,
        revenue: Long? = null,
    ) = PreReport(
        symbol = "AVGO",
        reportEpochDay = 20_692L,
        timing = ReportTiming.AfterClose,
        priceCents = 30_000L,
        consensusEpsCents = consensus,
        consensusEpsLowCents = low,
        consensusEpsHighCents = high,
        consensusRevenueCents = revenue,
    )
}
