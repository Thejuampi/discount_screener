package com.discountscreener.core.earnings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionMatrixTest {

    @Test
    fun a_move_priced_above_the_tickers_own_history_is_high_risk() {
        assertEquals(EventRisk.High, eventRiskOf(15_000))
    }

    @Test
    fun a_move_priced_under_the_tickers_own_history_is_low_risk() {
        assertEquals(EventRisk.Low, eventRiskOf(7_000))
    }

    @Test
    fun a_move_priced_like_the_tickers_own_history_is_normal_risk() {
        assertEquals(EventRisk.Normal, eventRiskOf(10_000))
    }

    @Test
    fun the_high_threshold_itself_is_still_normal_risk() {
        assertEquals(EventRisk.Normal, eventRiskOf(HIGH_RISK_RATIO_BPS))
    }

    @Test
    fun a_ticker_with_no_settled_history_carries_no_risk_class() {
        assertEquals(EventRisk.Unknown, eventRiskOf(null))
    }

    @Test
    fun an_expensive_ticker_facing_a_high_risk_report_leaves_before_it() {
        assertEquals(DecisionCell.ExpensiveHighRisk, decisionOf(pre(price = 5_000L, ratio = 15_000)).cell)
    }

    @Test
    fun an_expensive_ticker_facing_a_high_risk_report_holds_nothing_through_it() {
        assertEquals(0, decisionOf(pre(price = 5_000L, ratio = 15_000)).positionSizeBps)
    }

    @Test
    fun an_expensive_ticker_facing_a_normal_report_is_cut_for_the_price() {
        assertEquals(EventAction.Reduce, decisionOf(pre(price = 5_000L, ratio = 10_000)).action)
    }

    @Test
    fun a_cheap_ticker_facing_a_high_risk_report_is_hedged_and_not_sold() {
        assertEquals(EventAction.Hedge, decisionOf(pre(price = 3_500L, ratio = 15_000)).action)
    }

    @Test
    fun the_hedge_of_a_cheap_ticker_never_sells_away_its_upside() {
        assertEquals(HedgeKind.PutSpread, decisionOf(pre(price = 3_500L, ratio = 15_000)).hedge)
    }

    @Test
    fun a_cheap_ticker_facing_a_high_risk_report_keeps_half_the_position() {
        assertEquals(5_000, decisionOf(pre(price = 3_500L, ratio = 15_000)).positionSizeBps)
    }

    @Test
    fun a_cheap_ticker_facing_a_normal_report_is_held_whole() {
        assertEquals(EventAction.Hold, decisionOf(pre(price = 3_500L, ratio = 10_000)).action)
    }

    @Test
    fun the_cheap_threshold_itself_still_counts_as_cheap() {
        assertEquals(DecisionCell.CheapNormalRisk, decisionOf(pre(price = 3_600L, ratio = 10_000)).cell)
    }

    @Test
    fun one_cent_above_the_cheap_threshold_is_already_expensive() {
        assertEquals(DecisionCell.ExpensiveNormalRisk, decisionOf(pre(price = 3_601L, ratio = 10_000)).cell)
    }

    @Test
    fun a_low_risk_report_is_treated_as_the_normal_column() {
        assertEquals(DecisionCell.CheapNormalRisk, decisionOf(pre(price = 3_500L, ratio = 5_000)).cell)
    }

    @Test
    fun an_event_with_no_risk_ratio_refuses_to_decide() {
        assertEquals(DecisionCell.Undecided, decisionOf(pre(price = 3_500L, ratio = null)).cell)
    }

    @Test
    fun an_event_with_no_fair_value_refuses_to_decide() {
        assertEquals(DecisionCell.Undecided, decisionOf(pre(price = 3_500L, ratio = 10_000, fair = null)).cell)
    }

    @Test
    fun an_undecided_event_says_which_input_it_is_waiting_for() {
        var decision = decisionOf(pre(price = 3_500L, ratio = null, impliedMoveBps = null))

        assertTrue(decision.justification.contains("option chain"))
    }

    @Test
    fun an_undecided_event_with_a_priced_move_blames_the_missing_history() {
        var decision = decisionOf(pre(price = 3_500L, ratio = null, impliedMoveBps = 700))

        assertTrue(decision.justification.contains("settled reaction"))
    }

    @Test
    fun a_fair_value_of_zero_never_divides_the_price_by_it() {
        assertEquals(DecisionCell.Undecided, decisionOf(pre(price = 3_500L, ratio = 10_000, fair = 0L)).cell)
    }

    @Test
    fun the_price_against_fair_value_reads_back_in_basis_points() {
        assertEquals(8_750, priceToFairBps(pre(price = 3_500L, ratio = 10_000)))
    }

    @Test
    fun the_justification_names_the_multiple_the_market_is_paying() {
        assertTrue(decisionOf(pre(price = 3_500L, ratio = 15_000)).justification.contains("1.5x"))
    }

    @Test
    fun a_hedge_the_position_can_afford_is_the_hedge_that_gets_bought() {
        assertEquals(EventAction.Hedge, decisionOf(cheapRisky(spread = 80)).action)
    }

    @Test
    fun a_hedge_the_position_can_afford_carries_the_price_it_was_quoted_at() {
        assertEquals(80, decisionOf(cheapRisky(spread = 80)).hedgeCostBps)
    }

    @Test
    fun a_hedge_priced_at_the_cap_itself_is_still_bought() {
        assertEquals(EventAction.Hedge, decisionOf(cheapRisky(spread = HEDGE_COST_CAP_BPS)).action)
    }

    @Test
    fun a_hedge_that_costs_more_than_the_cap_becomes_a_smaller_position() {
        assertEquals(EventAction.Reduce, decisionOf(cheapRisky(spread = 150)).action)
    }

    @Test
    fun a_hedge_too_dear_to_buy_is_never_reported_as_bought() {
        assertEquals(HedgeKind.None, decisionOf(cheapRisky(spread = 150)).hedge)
    }

    @Test
    fun a_hedge_too_dear_to_buy_still_leaves_the_ticker_in_its_own_cell() {
        assertEquals(DecisionCell.CheapHighRisk, decisionOf(cheapRisky(spread = 150)).cell)
    }

    @Test
    fun a_hedge_too_dear_to_buy_names_the_price_that_ruled_it_out() {
        assertTrue(
            decisionOf(cheapRisky(spread = 150))
                .justification.contains("1.5% of the position, over the 1.0% cap"),
        )
    }

    @Test
    fun a_hedge_too_dear_to_buy_still_cuts_the_position_in_half() {
        assertEquals(5_000, decisionOf(cheapRisky(spread = 150)).positionSizeBps)
    }

    @Test
    fun a_chain_that_quotes_no_spread_leaves_the_hedge_unpriced() {
        assertEquals(null, decisionOf(cheapRisky(spread = null)).hedgeCostBps)
    }

    @Test
    fun a_chain_that_quotes_no_spread_still_calls_for_the_hedge() {
        assertEquals(EventAction.Hedge, decisionOf(cheapRisky(spread = null)).action)
    }

    @Test
    fun an_affordable_hedge_names_its_price_in_the_justification() {
        assertTrue(decisionOf(cheapRisky(spread = 80)).justification.contains("0.8%"))
    }

    private fun cheapRisky(spread: Int?) = pre(price = 3_500L, ratio = 15_000, spread = spread)

    private fun pre(
        price: Long,
        ratio: Int?,
        fair: Long? = 4_000L,
        impliedMoveBps: Int? = 700,
        spread: Int? = null,
    ) = PreReport(
        symbol = "LVS",
        reportEpochDay = 20_692L,
        timing = ReportTiming.AfterClose,
        priceCents = price,
        dcfFairValueCents = fair,
        impliedMoveBps = impliedMoveBps,
        riskRatioBps = ratio,
        putSpreadCostBps = spread,
    )
}
