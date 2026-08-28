package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.earnings.DecisionCell
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.EventRisk
import com.discountscreener.core.earnings.PostReport
import com.discountscreener.core.earnings.PreReport
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.earnings.decisionOf
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarningsGatePresentationTest {

    @Test
    fun a_report_still_ahead_lands_in_the_upcoming_list() {
        assertEquals(listOf("LVS"), present(listOf(record(day = 3))).upcoming.map { it.symbol })
    }

    @Test
    fun a_report_already_past_lands_in_the_settled_list() {
        assertEquals(listOf("LVS"), present(listOf(record(day = -3))).settled.map { it.symbol })
    }

    @Test
    fun a_report_happening_today_is_still_upcoming() {
        assertEquals(1, present(listOf(record(day = 0))).upcoming.size)
    }

    @Test
    fun the_nearest_report_is_shown_first() {
        var ui = present(listOf(record(day = 8, symbol = "WYNN"), record(day = 2)))

        assertEquals(listOf("LVS", "WYNN"), ui.upcoming.map { it.symbol })
    }

    @Test
    fun the_most_recent_report_is_shown_first_among_the_settled() {
        var ui = present(listOf(record(day = -8, symbol = "WYNN"), record(day = -2)))

        assertEquals(listOf("LVS", "WYNN"), ui.settled.map { it.symbol })
    }

    @Test
    fun the_priced_move_reads_back_as_a_percent() {
        assertEquals("7.01%", present(listOf(record(day = 3))).upcoming.single().impliedMove)
    }

    @Test
    fun the_risk_ratio_reads_back_as_a_multiple() {
        assertEquals("1.75x", present(listOf(record(day = 3))).upcoming.single().riskRatio)
    }

    @Test
    fun a_high_risk_cheap_ticker_is_named_by_its_cell() {
        assertEquals(
            "Cheap, high event risk",
            present(listOf(record(day = 3))).upcoming.single().headline,
        )
    }

    @Test
    fun a_high_risk_report_is_marked_as_high_risk() {
        assertEquals(EventRisk.High, present(listOf(record(day = 3))).upcoming.single().risk)
    }

    @Test
    fun the_position_size_reads_back_as_a_percent_of_the_full_one() {
        assertEquals("50%", present(listOf(record(day = 3))).upcoming.single().positionSize)
    }

    @Test
    fun an_event_with_no_history_behind_it_says_so_instead_of_showing_a_number() {
        var ui = present(listOf(record(day = 3, ratio = null)))

        assertEquals("—", ui.upcoming.single().riskRatio)
    }

    @Test
    fun an_event_with_no_history_behind_it_is_named_as_waiting() {
        var ui = present(listOf(record(day = 3, ratio = null)))

        assertEquals("Waiting on data", ui.upcoming.single().headline)
    }

    @Test
    fun a_report_whose_hour_yahoo_never_confirmed_says_so() {
        var ui = present(listOf(record(day = 3, timing = ReportTiming.Unknown)))

        assertEquals("Hour unconfirmed", ui.upcoming.single().timing)
    }

    @Test
    fun a_settled_report_shows_the_move_the_index_did_not_explain() {
        var ui = present(listOf(record(day = -3, abnormalReturnBps = 412)))

        assertEquals("Abnormal move +4.12%", ui.settled.single().reaction)
    }

    @Test
    fun a_settled_report_that_fell_shows_the_sign_of_the_fall() {
        var ui = present(listOf(record(day = -3, abnormalReturnBps = -412)))

        assertEquals("Abnormal move -4.12%", ui.settled.single().reaction)
    }

    @Test
    fun a_settled_report_names_the_share_of_the_index_it_was_charged() {
        var settled = PostReport(abnormalReturnBps = 412, marketBetaBps = 17_000)

        assertEquals("Abnormal move +4.12%, beta 1.70x", present(listOf(record(day = -3, post = settled))).settled.single().reaction)
    }

    @Test
    fun a_settled_report_with_no_beta_on_file_still_shows_its_move() {
        var ui = present(listOf(record(day = -3, abnormalReturnBps = 412)))

        assertEquals("Abnormal move +4.12%", ui.settled.single().reaction)
    }

    @Test
    fun a_settled_report_shows_how_far_the_eps_beat_the_analyst_spread() {
        var settled = PostReport(abnormalReturnBps = 412, surpriseScoreBps = 10_435)

        assertEquals("EPS +1.04 of the analyst spread", present(listOf(record(day = -3, post = settled))).settled.single().surprise)
    }

    @Test
    fun a_settled_report_shows_the_revenue_against_what_was_expected() {
        var settled = PostReport(abnormalReturnBps = 412, revenueSurpriseBps = 1_000)

        assertEquals("revenue +10.00%", present(listOf(record(day = -3, post = settled))).settled.single().surprise)
    }

    @Test
    fun a_settled_report_shows_both_surprises_together_when_it_has_both() {
        var settled = PostReport(abnormalReturnBps = 412, surpriseScoreBps = -5_000, revenueSurpriseBps = -250)

        assertEquals("EPS -0.50 of the analyst spread, revenue -2.50%", present(listOf(record(day = -3, post = settled))).settled.single().surprise)
    }

    @Test
    fun a_settled_report_that_carries_no_actuals_shows_no_surprise() {
        assertNull(present(listOf(record(day = -3, abnormalReturnBps = 412))).settled.single().surprise)
    }

    @Test
    fun a_report_not_settled_yet_shows_no_surprise() {
        assertNull(present(listOf(record(day = 3))).upcoming.single().surprise)
    }

    @Test
    fun a_report_not_settled_yet_shows_no_reaction() {
        assertNull(present(listOf(record(day = 3))).upcoming.single().reaction)
    }

    @Test
    fun the_hedge_of_a_cheap_high_risk_event_is_named_in_words() {
        assertEquals("Put spread", present(listOf(record(day = 3))).upcoming.single().hedge)
    }

    @Test
    fun the_damaged_lines_of_the_log_are_carried_to_the_screen() {
        assertEquals(2, present(listOf(record(day = 3)), damaged = 2).damagedLines)
    }

    @Test
    fun an_empty_log_reads_back_as_empty() {
        assertTrue(present(emptyList()).isEmpty)
    }

    @Test
    fun the_cell_of_a_decided_event_reaches_the_row() {
        assertEquals(DecisionCell.CheapHighRisk, present(listOf(record(day = 3))).upcoming.single().cell)
    }

    @Test
    fun the_price_of_the_spread_reaches_the_card() {
        assertTrue(row(spread = 165).hedgeCost.startsWith("1.65% of the position"))
    }

    @Test
    fun the_card_names_the_two_strikes_the_spread_is_built_from() {
        assertTrue(row(spread = 165).hedgeCost.contains("(44.00 / 42.00 puts)"))
    }

    @Test
    fun an_event_with_only_a_put_quoted_shows_that_price_instead() {
        assertEquals("3.25% of the position for a protective put", row(spread = null).hedgeCost)
    }

    @Test
    fun an_event_with_no_chain_shows_no_hedge_price() {
        assertEquals("—", row(spread = null, put = null).hedgeCost)
    }

    @Test
    fun the_card_separates_the_event_move_from_the_move_priced_to_the_expiry() {
        assertEquals("6.77% after 1.80% a day of quiet drift", moveRow().eventMove)
    }

    @Test
    fun the_card_still_shows_the_whole_move_priced_to_the_expiry() {
        assertEquals("7.01%", moveRow().impliedMove)
    }

    @Test
    fun a_ticker_with_no_readable_history_shows_the_event_move_alone() {
        assertEquals("7.01%", present(listOf(record(day = 3, event = 701))).upcoming.single().eventMove)
    }

    @Test
    fun a_ticker_with_no_chain_shows_no_event_move() {
        assertEquals("—", present(listOf(record(day = 3))).upcoming.single().eventMove)
    }

    private fun moveRow() =
        present(listOf(record(day = 3, event = 677, quiet = 180))).upcoming.single()

    private fun row(spread: Int?, put: Int? = 325) =
        present(listOf(record(day = 3, spread = spread, put = put))).upcoming.single()

    private fun present(events: List<EarningsEventRecord>, damaged: Int = 0) =
        presentEarningsGate(events = events, damagedLines = damaged, today = TODAY)

    private fun record(
        day: Long,
        symbol: String = "LVS",
        ratio: Int? = 17_525,
        timing: ReportTiming = ReportTiming.AfterClose,
        abnormalReturnBps: Int? = null,
        post: PostReport? = null,
        spread: Int? = null,
        put: Int? = null,
        event: Int? = null,
        quiet: Int? = null,
    ): EarningsEventRecord {
        var pre = PreReport(
            symbol = symbol,
            reportEpochDay = TODAY.plusDays(day).toEpochDay(),
            timing = timing,
            priceCents = 4_424L,
            dcfFairValueCents = 42_633L,
            impliedMoveBps = 701,
            eventImpliedMoveBps = event,
            normalDailyMoveBps = quiet,
            medianAbsoluteAbnormalReturnBps = 400,
            riskRatioBps = ratio,
            protectivePutCostBps = put,
            putSpreadCostBps = spread,
            hedgeLongStrikeCents = 4_400L,
            hedgeShortStrikeCents = 4_200L,
        )
        return EarningsEventRecord(
            pre = pre,
            decision = decisionOf(pre),
            post = post ?: abnormalReturnBps?.let { PostReport(abnormalReturnBps = it) },
        )
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 23)
    }
}
