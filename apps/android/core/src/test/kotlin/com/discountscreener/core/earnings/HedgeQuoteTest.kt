package com.discountscreener.core.earnings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HedgeQuoteTest {

    @Test
    fun the_long_leg_is_the_at_the_money_put_of_the_priced_move() {
        assertEquals(100.0, quote()?.longStrike)
    }

    @Test
    fun the_short_leg_sits_five_percent_below_the_long_leg() {
        assertEquals(95.0, quote()?.shortStrike)
    }

    @Test
    fun the_protective_put_costs_what_the_at_the_money_put_costs() {
        assertEquals(400, quote()?.protectivePutCostBps)
    }

    @Test
    fun the_spread_costs_the_long_leg_less_the_short_leg() {
        assertEquals(250, quote()?.putSpreadCostBps)
    }

    @Test
    fun a_ladder_with_no_strike_below_the_money_prices_no_spread() {
        var only = listOf(row(100.0, put = 4.0))

        assertNull(hedgeQuoteOf(only, move(100.0), forward = 100.0)?.putSpreadCostBps)
    }

    @Test
    fun a_ladder_with_no_strike_below_the_money_still_prices_the_put() {
        var only = listOf(row(100.0, put = 4.0))

        assertEquals(400, hedgeQuoteOf(only, move(100.0), forward = 100.0)?.protectivePutCostBps)
    }

    @Test
    fun a_short_leg_worth_more_than_the_long_leg_never_becomes_a_credit_spread() {
        var crossed = listOf(row(100.0, put = 1.0), row(95.0, put = 3.0))

        assertNull(hedgeQuoteOf(crossed, move(100.0), forward = 100.0)?.putSpreadCostBps)
    }

    @Test
    fun a_short_leg_that_prices_no_spread_names_no_strike() {
        var crossed = listOf(row(100.0, put = 1.0), row(95.0, put = 3.0))

        assertNull(hedgeQuoteOf(crossed, move(100.0), forward = 100.0)?.shortStrike)
    }

    @Test
    fun a_ladder_that_never_quotes_the_at_the_money_strike_refuses() {
        assertNull(hedgeQuoteOf(listOf(row(95.0, put = 1.5)), move(100.0), forward = 100.0))
    }

    @Test
    fun an_at_the_money_put_with_no_bid_refuses() {
        var noBid = listOf(ChainRow(100.0, OptionQuote(1.0, 1.2), OptionQuote(0.0, 4.2)))

        assertNull(hedgeQuoteOf(noBid, move(100.0), forward = 100.0))
    }

    @Test
    fun a_price_of_zero_refuses() {
        assertNull(hedgeQuoteOf(ladder, move(100.0), forward = 0.0))
    }

    @Test
    fun the_short_leg_is_the_strike_nearest_the_five_percent_target() {
        var wide = listOf(row(100.0, put = 4.0), row(97.0, put = 2.6), row(94.0, put = 1.4))

        assertEquals(94.0, hedgeQuoteOf(wide, move(100.0), forward = 100.0)?.shortStrike)
    }

    @Test
    fun the_cost_is_read_against_the_price_and_not_against_the_strike() {
        assertEquals(500, hedgeQuoteOf(ladder, move(100.0), forward = 80.0)?.protectivePutCostBps)
    }

    private fun quote(): HedgeQuote? = hedgeQuoteOf(ladder, move(100.0), forward = 100.0)

    private fun move(strike: Double) = ImpliedMove(fraction = 0.08, strike = strike, straddlePrice = 8.0)

    private fun row(strike: Double, put: Double) =
        ChainRow(strike, OptionQuote(1.0, 1.2), OptionQuote(put - 0.1, put + 0.1))

    private val ladder = listOf(row(105.0, put = 7.0), row(100.0, put = 4.0), row(95.0, put = 1.5))
}
