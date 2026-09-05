package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreReportBuilderTest {

    @Test
    fun a_report_before_the_opening_bell_moves_the_same_days_price() {
        assertEquals(ReportTiming.BeforeOpen, reportTimingOf(1_787_749_800L).second)
    }

    @Test
    fun a_report_after_the_closing_bell_moves_the_next_days_price() {
        assertEquals(ReportTiming.AfterClose, reportTimingOf(1_787_785_200L).second)
    }

    @Test
    fun a_placeholder_time_inside_the_session_names_no_reaction_window() {
        assertEquals(ReportTiming.Unknown, reportTimingOf(1_787_764_200L).second)
    }

    @Test
    fun the_report_day_is_read_in_the_exchanges_own_time() {
        assertEquals(LocalDate.of(2026, 8, 26), reportTimingOf(1_787_785_200L).first)
    }

    @Test
    fun the_implied_move_of_the_chain_lands_in_the_block() {
        assertEquals(701, full().impliedMoveBps)
    }

    @Test
    fun the_strike_the_move_came_from_lands_in_the_block() {
        assertEquals(4_400L, full().strikeCents)
    }

    @Test
    fun the_expiry_the_move_came_from_lands_in_the_block() {
        assertEquals(LocalDate.of(2026, 8, 28).toEpochDay(), full().expiryEpochDay)
    }

    @Test
    fun the_yardstick_is_the_median_size_of_past_reactions_either_way() {
        assertEquals(465, full().medianAbsoluteAbnormalReturnBps)
    }

    @Test
    fun the_risk_ratio_says_how_much_richer_this_report_is_priced_than_history() {
        assertEquals(15_075, full().riskRatioBps)
    }

    @Test
    fun the_fair_value_travels_with_the_day_it_was_computed() {
        assertEquals(LocalDate.of(2026, 8, 24).toEpochDay(), full().dcfComputedOnEpochDay)
    }

    @Test
    fun the_consensus_of_the_reporting_quarter_lands_in_cents() {
        assertEquals(62L, full().consensusEpsCents)
    }

    @Test
    fun a_ticker_with_no_history_yet_still_produces_a_block_worth_writing() {
        assertEquals(701, bare(pastAbnormalReturnsBps = emptyList()).impliedMoveBps)
    }

    @Test
    fun a_ticker_with_no_history_yet_has_no_risk_ratio() {
        assertNull(bare(pastAbnormalReturnsBps = emptyList()).riskRatioBps)
    }

    @Test
    fun a_history_of_no_reaction_at_all_refuses_to_become_a_huge_ratio() {
        assertNull(bare(pastAbnormalReturnsBps = listOf(0, 0, 0)).riskRatioBps)
    }

    @Test
    fun an_event_with_no_option_chain_is_still_recorded() {
        var block = preReportOf(
            symbol = "LVS",
            reportDate = LocalDate.of(2026, 8, 26),
            timing = ReportTiming.AfterClose,
            priceCents = 4_424L,
        )

        assertEquals(LocalDate.of(2026, 8, 26).toEpochDay(), block.reportEpochDay)
    }

    @Test
    fun an_event_with_no_option_chain_carries_no_strike_it_cannot_prove() {
        var block = preReportOf(
            symbol = "LVS",
            reportDate = LocalDate.of(2026, 8, 26),
            timing = ReportTiming.AfterClose,
            priceCents = 4_424L,
        )

        assertNull(block.strikeCents)
    }

    @Test
    fun the_event_carries_the_price_of_the_put_spread_that_would_hedge_it() {
        assertEquals(165, full().putSpreadCostBps)
    }

    @Test
    fun the_event_carries_the_price_of_the_put_that_would_cover_it_outright() {
        assertEquals(325, full().protectivePutCostBps)
    }

    @Test
    fun the_event_names_the_strike_the_hedge_is_bought_at() {
        assertEquals(4_400L, full().hedgeLongStrikeCents)
    }

    @Test
    fun the_event_names_the_strike_the_hedge_is_sold_at() {
        assertEquals(4_200L, full().hedgeShortStrikeCents)
    }

    @Test
    fun an_event_with_no_option_chain_carries_no_hedge_price() {
        var block = preReportOf(
            symbol = "LVS",
            reportDate = LocalDate.of(2026, 8, 26),
            timing = ReportTiming.AfterClose,
            priceCents = 4_424L,
        )

        assertNull(block.putSpreadCostBps)
    }

    @Test
    fun the_quiet_days_left_before_the_expiry_come_out_of_the_priced_move() {
        assertEquals(677, withHistory().eventImpliedMoveBps)
    }

    @Test
    fun the_ratio_is_read_against_the_event_move_and_not_against_the_whole_expiry() {
        assertEquals(14_559, withHistory().riskRatioBps)
    }

    @Test
    fun the_move_priced_to_the_expiry_still_reads_back_whole() {
        assertEquals(701, withHistory().impliedMoveBps)
    }

    @Test
    fun the_event_carries_the_quiet_day_move_it_was_measured_against() {
        assertEquals(180, withHistory().normalDailyMoveBps)
    }

    @Test
    fun a_ticker_with_no_readable_history_keeps_the_whole_priced_move() {
        assertEquals(701, full().eventImpliedMoveBps)
    }

    @Test
    fun the_forward_is_the_chain_underlying_when_the_spot_has_moved() {
        var block = preReportOf(
            symbol = "LVS",
            reportDate = LocalDate.of(2026, 8, 26),
            timing = ReportTiming.AfterClose,
            priceCents = 10_000L,
            chain = chain,
        )

        assertEquals(701, block.impliedMoveBps)
    }

    private fun withHistory(): PreReport = preReportOf(
        symbol = "LVS",
        reportDate = LocalDate.of(2026, 8, 26),
        timing = ReportTiming.AfterClose,
        priceCents = 4_424L,
        dcf = DcfAsOf(fairValueCents = 42_633L, computedOn = LocalDate.of(2026, 8, 24)),
        chain = chain,
        pastAbnormalReturnsBps = listOf(-520, -300, 410, 640),
        normalDailyMoveBps = 180,
    )

    private fun full(): PreReport = preReportOf(
        symbol = "LVS",
        reportDate = LocalDate.of(2026, 8, 26),
        timing = ReportTiming.AfterClose,
        priceCents = 4_424L,
        dcf = DcfAsOf(fairValueCents = 42_633L, computedOn = LocalDate.of(2026, 8, 24)),
        chain = chain,
        consensus = consensusOf(
            lenient.parseToJsonElement(fixture("yahoo/earningsTrend/LVS.json")).jsonObject,
        ),
        pastAbnormalReturnsBps = listOf(-520, -300, 410, 640),
    )

    private fun bare(pastAbnormalReturnsBps: List<Int>): PreReport = preReportOf(
        symbol = "LVS",
        reportDate = LocalDate.of(2026, 8, 26),
        timing = ReportTiming.AfterClose,
        priceCents = 4_424L,
        chain = chain,
        pastAbnormalReturnsBps = pastAbnormalReturnsBps,
    )

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    private val chain: OptionChainSnapshot =
        parseOptionChain(fixture("yahoo/options/LVS-2026-08-28.json"))!!

    private fun fixture(path: String): String =
        javaClass.classLoader!!.getResource(path)!!.readText()
}
