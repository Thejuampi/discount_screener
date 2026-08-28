package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventSettlementTest {

    @Test
    fun a_report_after_the_close_is_priced_from_that_days_close_to_the_next() {
        assertEquals(500, settlementOf(pre(ReportTiming.AfterClose), stock, market)?.stockReturnBps)
    }

    @Test
    fun a_report_before_the_open_is_priced_from_the_previous_close_to_that_days() {
        assertEquals(-238, settlementOf(pre(ReportTiming.BeforeOpen), stock, market)?.stockReturnBps)
    }

    @Test
    fun a_report_of_unknown_hour_spans_the_whole_day_it_lands_on() {
        assertEquals(250, settlementOf(pre(ReportTiming.Unknown), stock, market)?.stockReturnBps)
    }

    @Test
    fun the_index_move_of_the_same_window_is_measured_too() {
        assertEquals(200, settlementOf(pre(ReportTiming.AfterClose), stock, market)?.marketReturnBps)
    }

    @Test
    fun the_abnormal_move_is_what_the_index_did_not_explain() {
        assertEquals(300, settlementOf(pre(ReportTiming.AfterClose), stock, market)?.abnormalReturnBps)
    }

    @Test
    fun a_missing_index_series_never_costs_the_stock_move() {
        assertEquals(500, settlementOf(pre(ReportTiming.AfterClose), stock, emptyList())?.stockReturnBps)
    }

    @Test
    fun a_missing_index_series_leaves_the_abnormal_move_unknown() {
        assertNull(settlementOf(pre(ReportTiming.AfterClose), stock, emptyList())?.abnormalReturnBps)
    }

    @Test
    fun a_window_with_no_close_after_the_report_refuses() {
        assertNull(settlementOf(pre(ReportTiming.AfterClose), stock.dropLast(1), market))
    }

    @Test
    fun a_window_with_no_close_before_the_report_refuses() {
        assertNull(settlementOf(pre(ReportTiming.BeforeOpen), stock.drop(2), market))
    }

    @Test
    fun a_series_that_arrives_out_of_order_is_priced_the_same() {
        assertEquals(500, settlementOf(pre(ReportTiming.AfterClose), stock.reversed(), market)?.stockReturnBps)
    }

    @Test
    fun a_close_of_zero_refuses_instead_of_dividing_by_it() {
        var broken = stock.map { if (it.date == REPORT) it.copy(closeCents = 0L) else it }

        assertNull(settlementOf(pre(ReportTiming.AfterClose), broken, market))
    }

    @Test
    fun an_epoch_second_reads_back_as_the_exchange_day_it_belongs_to() {
        assertEquals(LocalDate.of(2026, 8, 20), dailyCloseOf(1_787_232_600L, 4_200L).date)
    }

    @Test
    fun the_quarter_the_company_reported_lands_in_the_settled_record() {
        assertEquals(244L, settled()?.epsActualCents)
    }

    @Test
    fun the_revenue_of_that_quarter_lands_with_it() {
        assertEquals(2_218_700_000_000L, settled()?.revenueActualCents)
    }

    @Test
    fun the_beat_is_scored_against_how_far_apart_the_analysts_were() {
        assertEquals(10_000, settled()?.surpriseScoreBps)
    }

    @Test
    fun the_revenue_beat_reads_back_as_the_share_it_beat_the_consensus_by() {
        assertEquals(1_000, settled()?.revenueSurpriseBps)
    }

    @Test
    fun a_settlement_with_no_quarter_on_file_still_prices_the_reaction() {
        assertEquals(500, settlementOf(estimated(), stock, market)?.stockReturnBps)
    }

    @Test
    fun a_settlement_with_no_quarter_on_file_scores_no_surprise() {
        assertNull(settlementOf(estimated(), stock, market)?.surpriseScoreBps)
    }

    private fun settled() = settlementOf(estimated(), stock, market, listOf(reportedQuarter))

    private fun estimated() = pre(ReportTiming.AfterClose).copy(
        consensusEpsCents = 240L,
        consensusEpsLowCents = 236L,
        consensusEpsHighCents = 244L,
        consensusRevenueCents = 2_016_999_999_999L,
    )

    private val reportedQuarter = ReportedQuarter(
        quarterEndDate = REPORT.minusDays(20),
        epsActual = 2.44,
        epsEstimate = 2.40,
        revenueActual = 22_187_000_000.0,
    )

    private fun pre(timing: ReportTiming) = PreReport(
        symbol = "LVS",
        reportEpochDay = REPORT.toEpochDay(),
        timing = timing,
        priceCents = 4_000L,
    )

    private val stock = listOf(
        DailyClose(REPORT.minusDays(1), 4_200L),
        DailyClose(REPORT, 4_100L),
        DailyClose(REPORT.plusDays(1), 4_305L),
    )

    private val market = listOf(
        DailyClose(REPORT.minusDays(1), 10_000L),
        DailyClose(REPORT, 10_000L),
        DailyClose(REPORT.plusDays(1), 10_200L),
    )

    private companion object {
        val REPORT: LocalDate = LocalDate.of(2026, 8, 20)
    }
}
