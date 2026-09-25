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
    fun a_move_priced_under_the_tickers_own_history_is_normal_risk() {
        assertEquals(EventRisk.Normal, eventRiskOf(7_000))
    }

    @Test
    fun a_move_priced_like_the_tickers_own_history_is_normal_risk() {
        assertEquals(EventRisk.Normal, eventRiskOf(10_000))
    }

    @Test
    fun the_high_threshold_itself_is_still_normal_risk() {
        assertEquals(EventRisk.Normal, eventRiskOf(UNITY_BPS))
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
    fun price_equal_to_fair_is_expensive() {
        assertEquals(DecisionCell.ExpensiveHighRisk, decisionOf(pre(price = 4_000L, ratio = 15_000)).cell)
    }

    @Test
    fun a_cheap_ticker_facing_a_high_risk_report_is_hedged_and_not_sold() {
        assertEquals(EventAction.Hedge, decisionOf(pre(price = 3_500L, ratio = 15_000, spread = 80)).action)
    }

    @Test
    fun the_hedge_of_a_cheap_ticker_never_sells_away_its_upside() {
        assertEquals(HedgeKind.PutSpread, decisionOf(pre(price = 3_500L, ratio = 15_000, spread = 80)).hedge)
    }

    @Test
    fun a_cheap_ticker_facing_a_high_risk_report_keeps_half_the_position() {
        assertEquals(5_000, decisionOf(pre(price = 3_500L, ratio = 15_000, spread = 80)).positionSizeBps)
    }

    @Test
    fun a_cheap_ticker_facing_a_normal_report_is_held_whole() {
        assertEquals(EventAction.Hold, decisionOf(pre(price = 3_500L, ratio = 10_000)).action)
    }

    @Test
    fun one_cent_below_fair_is_cheap() {
        assertEquals(DecisionCell.CheapNormalRisk, decisionOf(pre(price = 3_999L, ratio = 10_000)).cell)
    }

    @Test
    fun a_low_ratio_report_is_treated_as_the_normal_column() {
        assertEquals(DecisionCell.CheapNormalRisk, decisionOf(pre(price = 3_500L, ratio = 5_000)).cell)
    }

    @Test
    fun an_event_with_no_risk_ratio_refuses_to_decide() {
        assertEquals(DecisionCell.Undecided, decisionOf(pre(price = 3_500L, ratio = null)).cell)
    }

    @Test
    fun an_event_with_no_fair_value_refuses_to_decide() {
        assertEquals("dcf_unavailable", decisionOf(pre(price = 3_500L, ratio = 10_000, fair = null)).unavailableReason)
    }

    @Test
    fun a_zero_fair_value_refuses_as_dcf_unavailable() {
        assertEquals("dcf_unavailable", decisionOf(pre(price = 3_500L, ratio = 10_000, fair = 0L)).unavailableReason)
    }

    @Test
    fun an_undecided_event_says_which_input_it_is_waiting_for() {
        var decision = decisionOf(pre(price = 3_500L, ratio = null, impliedMoveBps = null))

        assertEquals("chain_unavailable", decision.unavailableReason)
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

        assertEquals("ar_unavailable", decision.unavailableReason)
    }

    @Test
    fun a_zero_median_ar_refuses_as_ar_unavailable() {
        assertEquals(
            "ar_unavailable",
            decisionOf(pre(price = 3_500L, ratio = null, impliedMoveBps = 700).copy(medianAbsoluteAbnormalReturnBps = 0)).unavailableReason,
        )
    }

    @Test
    fun a_halted_price_never_counts_as_cheap() {
        assertEquals("price_unavailable", decisionOf(pre(price = 0L, ratio = 10_000)).unavailableReason)
    }

    @Test
    fun quiet_that_eats_the_move_does_not_become_high() {
        var decision = decisionOf(
            pre(price = 3_500L, ratio = 15_000, impliedMoveBps = 1_000, eventMove = null, spread = 80),
        )
        assertEquals("quiet_dominates_implied", decision.unavailableReason)
        assertEquals(EventAction.Hold, decision.action)
        assertEquals(HedgeKind.None, decision.hedge)
    }

    @Test
    fun the_price_against_fair_value_reads_back_in_basis_points() {
        assertEquals(8_750, priceToFairBps(pre(price = 3_500L, ratio = 10_000)))
    }

    @Test
    fun the_justification_names_the_multiple_the_market_is_paying() {
        assertTrue(decisionOf(pre(price = 3_500L, ratio = 15_000, spread = 80)).justification.contains("1.50x"))
    }

    @Test
    fun a_hedge_cheaper_than_the_event_is_bought() {
        assertEquals(EventAction.Hedge, decisionOf(cheapRisky(spread = 400)).action)
    }

    @Test
    fun a_hedge_priced_at_the_event_is_not_bought() {
        assertEquals(EventAction.Reduce, decisionOf(cheapRisky(spread = 700, put = 700)).action)
    }

    @Test
    fun a_spread_at_the_event_still_buys_a_cheaper_put() {
        assertEquals(HedgeKind.ProtectivePut, decisionOf(cheapRisky(spread = 700, put = 500)).hedge)
    }

    @Test
    fun a_hedge_over_the_event_cuts_the_position() {
        assertEquals(EventAction.Reduce, decisionOf(cheapRisky(spread = 900, put = 1_000)).action)
    }

    @Test
    fun a_hedge_too_dear_to_buy_is_never_reported_as_bought() {
        assertEquals(HedgeKind.None, decisionOf(cheapRisky(spread = 900, put = 1_000)).hedge)
    }

    @Test
    fun leftover_yaml_caps_do_not_block_a_real_earnings_hedge() {
        assertEquals(EventAction.Hedge, decisionOf(cheapRisky(spread = 150)).action)
    }

    @Test
    fun a_chain_that_quotes_no_hedge_cuts_size() {
        assertEquals(EventAction.Reduce, decisionOf(cheapRisky(spread = null, put = null)).action)
    }

    @Test
    fun a_priced_put_with_no_spread_is_the_hedge() {
        assertEquals(HedgeKind.ProtectivePut, decisionOf(cheapRisky(spread = null, put = 100)).hedge)
    }

    @Test
    fun an_affordable_spread_is_bought_before_the_protective_put() {
        assertEquals(HedgeKind.PutSpread, decisionOf(cheapRisky(spread = 80, put = 50)).hedge)
    }

    @Test
    fun a_chain_quoted_as_wide_as_the_straddle_decides_nothing() {
        assertEquals("option_width_ge_straddle", decisionOf(stale(quote = 10_000)).unavailableReason)
    }

    @Test
    fun a_chain_quoted_wider_than_the_straddle_decides_nothing() {
        assertEquals(DecisionCell.Undecided, decisionOf(stale(quote = 15_402)).cell)
    }

    @Test
    fun a_chain_quoted_just_inside_the_straddle_still_decides() {
        assertEquals(DecisionCell.CheapHighRisk, decisionOf(stale(quote = 9_999)).cell)
    }

    @Test
    fun a_chain_with_no_width_of_its_own_never_counts_as_stale() {
        assertEquals(DecisionCell.CheapHighRisk, decisionOf(stale(quote = null)).cell)
    }

    @Test
    fun a_sue_fit_never_changes_the_cell() {
        var base = pre(price = 3_500L, ratio = 15_000, spread = 80)
        var fitted = base.copy(surpriseFitN = 16, surpriseFitSueSlopeArBps = 300)
        assertEquals(decisionOf(base).cell, decisionOf(fitted).cell)
    }

    @Test
    fun a_hold_below_a_flat_trail_is_cut_in_half() {
        assertEquals(5_000, decisionOf(flatMiss()).positionSizeBps)
    }

    @Test
    fun a_revenue_cut_on_hold_sets_the_trail_flag() {
        assertEquals(true, decisionOf(flatMiss()).trailCut())
    }

    @Test
    fun a_revenue_cut_keeps_the_cheap_normal_cell() {
        assertEquals(DecisionCell.CheapNormalRisk, decisionOf(flatMiss()).cell)
    }

    @Test
    fun a_shortfall_equal_to_one_mad_does_not_cut() {
        assertEquals(
            EventAction.Hold,
            decisionOf(
                pre(price = 3_500L, ratio = 10_000).copy(
                    revenueTrailLatestCents = 80L,
                    revenueTrailCentreCents = 100L,
                    revenueTrailScaleCents = 20L,
                ),
            ).action,
        )
    }

    @Test
    fun an_old_median_key_still_feeds_the_trail() {
        assertEquals(
            true,
            decisionOf(
                pre(price = 3_500L, ratio = 10_000).copy(
                    revenueTrailLatestCents = 80L,
                    revenueTrailMedianCents = 100L,
                    revenueTrailScaleCents = 10L,
                ),
            ).trailCut(),
        )
    }

    @Test
    fun the_new_centre_wins_when_both_keys_exist() {
        assertEquals(
            true,
            decisionOf(
                pre(price = 3_500L, ratio = 10_000).copy(
                    revenueTrailLatestCents = 80L,
                    revenueTrailMedianCents = 50L,
                    revenueTrailCentreCents = 100L,
                    revenueTrailScaleCents = 10L,
                ),
            ).trailCut(),
        )
    }

    @Test
    fun an_expensive_name_never_wears_the_revenue_flag() {
        assertEquals(false, decisionOf(flatMiss(price = 5_000L)).trailCut())
    }

    @Test
    fun a_high_risk_hedge_never_takes_the_trail_cut() {
        assertEquals(
            false,
            decisionOf(flatMiss(price = 3_500L, ratio = 15_000, spread = 80)).trailCut(),
        )
        assertEquals(
            DecisionCell.CheapHighRisk,
            decisionOf(flatMiss(price = 3_500L, ratio = 15_000, spread = 80)).cell,
        )
    }

    @Test
    fun leftover_percent_keys_cannot_make_zero_point_nine_expensive() {
        assertEquals(
            DecisionCell.CheapHighRisk,
            decisionOf(pre(price = 3_800L, ratio = 11_000, spread = 80)).cell,
        )
    }

    @Test
    fun quiet_beats_missing_ar_on_the_card() {
        var decision = decisionOf(pre(price = 3_500L, ratio = null, impliedMoveBps = 1_000, eventMove = null))
        assertEquals("quiet_dominates_implied", decision.unavailableReason)
    }

    @Test
    fun halted_beats_missing_dcf_on_the_card() {
        var decision = decisionOf(pre(price = 0L, ratio = 10_000, fair = null))
        assertEquals("price_unavailable", decision.unavailableReason)
    }

    @Test
    fun chain_beats_quiet_on_the_card() {
        assertEquals(
            "chain_unavailable",
            decisionOf(pre(price = 3_500L, ratio = null, impliedMoveBps = null, eventMove = null))
                .unavailableReason,
        )
    }

    @Test
    fun stale_beats_missing_price_on_the_card() {
        assertEquals(
            "option_width_ge_straddle",
            decisionOf(pre(price = 0L, ratio = 15_000).copy(quoteSpreadBps = 10_000))
                .unavailableReason,
        )
    }

    @Test
    fun dcf_beats_missing_ar_on_the_card() {
        assertEquals(
            "dcf_unavailable",
            decisionOf(pre(price = 3_500L, ratio = null, fair = null)).unavailableReason,
        )
    }

    @Test
    fun an_old_flag_does_not_count_as_an_identity_change() {
        var next = decisionOf(pre(price = 3_800L, ratio = 11_000, spread = 80))

        assertEquals(false, identityChanged(next, next.copy(sectorOverrideApplied = false)))
    }

    private fun flatMiss(
        price: Long = 3_500L,
        ratio: Int? = 10_000,
        spread: Int? = null,
    ) = pre(price = price, ratio = ratio, spread = spread).copy(
        revenueTrailLatestCents = 50L,
        revenueTrailCentreCents = 100L,
        revenueTrailScaleCents = 0L,
    )

    private fun stale(quote: Int?) =
        pre(price = 3_500L, ratio = 15_000, spread = 80).copy(quoteSpreadBps = quote)

    private fun cheapRisky(spread: Int?, put: Int? = null) =
        pre(price = 3_500L, ratio = 15_000, spread = spread, put = put)

    private fun pre(
        price: Long,
        ratio: Int?,
        fair: Long? = 4_000L,
        impliedMoveBps: Int? = 700,
        eventMove: Int? = impliedMoveBps,
        spread: Int? = null,
        put: Int? = null,
    ) = PreReport(
        symbol = "LVS",
        reportEpochDay = 20_692L,
        timing = ReportTiming.AfterClose,
        priceCents = price,
        dcfFairValueCents = fair,
        impliedMoveBps = impliedMoveBps,
        eventImpliedMoveBps = eventMove,
        riskRatioBps = ratio,
        putSpreadCostBps = spread,
        protectivePutCostBps = put,
    )
}
