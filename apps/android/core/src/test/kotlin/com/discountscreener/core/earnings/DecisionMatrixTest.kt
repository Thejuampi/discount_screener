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
    fun a_chain_that_answered_but_quoted_nothing_says_it_is_not_quoted_yet() {
        var pre = pre(price = 3_500L, ratio = null, impliedMoveBps = null).copy(expiryEpochDay = 20_700L)

        assertTrue(decisionOf(pre).justification.contains("not quoted yet"))
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
    fun a_halted_price_never_counts_as_cheap() {
        assertEquals(null, priceToFairBps(pre(price = 0L, ratio = 10_000)))
    }

    @Test
    fun the_justification_names_the_multiple_the_market_is_paying() {
        assertTrue(decisionOf(pre(price = 3_500L, ratio = 15_000)).justification.contains("1.50x"))
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
    fun a_dear_spread_falls_back_to_an_affordable_protective_put() {
        assertEquals(HedgeKind.ProtectivePut, decisionOf(cheapRisky(spread = 150, put = 120)).hedge)
    }

    @Test
    fun a_protective_put_at_its_cap_is_still_bought() {
        assertEquals(
            EventAction.Hedge,
            decisionOf(cheapRisky(spread = 150, put = PROTECTIVE_PUT_COST_CAP_BPS)).action,
        )
    }

    @Test
    fun a_protective_put_over_its_cap_cuts_the_position() {
        assertEquals(EventAction.Reduce, decisionOf(cheapRisky(spread = 150, put = 200)).action)
    }

    @Test
    fun an_affordable_spread_is_bought_before_the_protective_put() {
        assertEquals(HedgeKind.PutSpread, decisionOf(cheapRisky(spread = 80, put = 50)).hedge)
    }

    @Test
    fun a_priced_put_with_no_spread_is_the_hedge() {
        assertEquals(HedgeKind.ProtectivePut, decisionOf(cheapRisky(spread = null, put = 100)).hedge)
    }

    @Test
    fun an_affordable_hedge_names_its_price_in_the_justification() {
        assertTrue(decisionOf(cheapRisky(spread = 80)).justification.contains("0.8%"))
    }

    @Test
    fun a_chain_quoted_wider_than_its_own_mid_decides_nothing() {
        assertEquals(DecisionCell.Undecided, decisionOf(stale(quote = 6_000)).cell)
    }

    @Test
    fun a_chain_quoted_wider_than_its_own_mid_says_why_it_decided_nothing() {
        assertTrue(decisionOf(stale(quote = 6_000)).justification.contains("60.0% wide"))
    }

    @Test
    fun a_chain_quoted_at_the_width_limit_still_decides() {
        assertEquals(DecisionCell.CheapHighRisk, decisionOf(stale(quote = MAX_QUOTE_SPREAD_BPS)).cell)
    }

    @Test
    fun a_chain_with_no_width_of_its_own_never_counts_as_stale() {
        assertEquals(DecisionCell.CheapHighRisk, decisionOf(stale(quote = null)).cell)
    }

    private fun stale(quote: Int?) =
        pre(price = 3_500L, ratio = 15_000).copy(quoteSpreadBps = quote)

    private fun cheapRisky(spread: Int?, put: Int? = null) =
        pre(price = 3_500L, ratio = 15_000, spread = spread, put = put)

    private fun pre(
        price: Long,
        ratio: Int?,
        fair: Long? = 4_000L,
        impliedMoveBps: Int? = 700,
        spread: Int? = null,
        put: Int? = null,
    ) = PreReport(
        symbol = "LVS",
        reportEpochDay = 20_692L,
        timing = ReportTiming.AfterClose,
        priceCents = price,
        dcfFairValueCents = fair,
        impliedMoveBps = impliedMoveBps,
        riskRatioBps = ratio,
        putSpreadCostBps = spread,
        protectivePutCostBps = put,
    )
}
