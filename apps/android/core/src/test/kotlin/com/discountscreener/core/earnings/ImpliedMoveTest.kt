package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImpliedMoveTest {

    @Test
    fun the_straddle_over_the_forward_is_the_move_the_market_pays_for() {
        var move = impliedMove(listOf(atTheMoney(strike = 100.0, call = 3.0, put = 2.0)), forward = 100.0)

        assertEquals(0.05, move?.fraction)
    }

    @Test
    fun the_logged_strike_is_the_one_the_price_came_from() {
        var rows = listOf(
            atTheMoney(strike = 99.0, call = 3.0, put = 2.0),
            atTheMoney(strike = 100.5, call = 3.0, put = 2.0),
        )

        var move = impliedMove(rows, forward = 100.0)

        assertEquals(100.5, move?.strike)
    }

    @Test
    fun a_tie_between_two_strikes_takes_the_lower_one_every_time() {
        var rows = listOf(
            atTheMoney(strike = 101.0, call = 3.0, put = 2.0),
            atTheMoney(strike = 99.0, call = 3.0, put = 2.0),
        )

        var move = impliedMove(rows, forward = 100.0)

        assertEquals(99.0, move?.strike)
    }

    @Test
    fun a_strike_grid_too_coarse_to_reach_the_forward_refuses() {
        var rows = listOf(atTheMoney(strike = 105.0, call = 3.0, put = 2.0))

        assertNull(impliedMove(rows, forward = 100.0))
    }

    @Test
    fun an_at_the_money_option_with_no_bid_refuses() {
        var rows = listOf(
            ChainRow(
                strike = 100.0,
                call = OptionQuote(bid = 0.0, ask = 3.0),
                put = OptionQuote(bid = 1.9, ask = 2.1),
            ),
        )

        assertNull(impliedMove(rows, forward = 100.0))
    }

    @Test
    fun a_crossed_quote_refuses() {
        var rows = listOf(
            ChainRow(
                strike = 100.0,
                call = OptionQuote(bid = 3.2, ask = 2.8),
                put = OptionQuote(bid = 1.9, ask = 2.1),
            ),
        )

        assertNull(impliedMove(rows, forward = 100.0))
    }

    @Test
    fun an_empty_chain_refuses() {
        assertNull(impliedMove(emptyList(), forward = 100.0))
    }

    @Test
    fun a_forward_of_zero_refuses() {
        var rows = listOf(atTheMoney(strike = 100.0, call = 3.0, put = 2.0))

        assertNull(impliedMove(rows, forward = 0.0))
    }

    @Test
    fun the_expiry_that_covers_the_report_is_the_first_one_after_it() {
        var expiries = listOf(
            LocalDate.of(2026, 8, 21),
            LocalDate.of(2026, 8, 28),
            LocalDate.of(2026, 9, 18),
        )

        assertEquals(
            LocalDate.of(2026, 8, 28),
            expiryAfterReport(expiries, reportDate = LocalDate.of(2026, 8, 26)),
        )
    }

    @Test
    fun an_expiry_on_the_report_day_does_not_cover_an_after_hours_report() {
        var expiries = listOf(LocalDate.of(2026, 8, 26))

        assertNull(expiryAfterReport(expiries, reportDate = LocalDate.of(2026, 8, 26)))
    }

    @Test
    fun an_expiry_on_the_report_day_covers_a_before_open_report() {
        var expiries = listOf(LocalDate.of(2026, 8, 26))

        assertEquals(
            LocalDate.of(2026, 8, 26),
            expiryAfterReport(
                expiries,
                reportDate = LocalDate.of(2026, 8, 26),
                timing = ReportTiming.BeforeOpen,
            ),
        )
    }

    private fun atTheMoney(strike: Double, call: Double, put: Double) = ChainRow(
        strike = strike,
        call = OptionQuote(bid = call - 0.1, ask = call + 0.1),
        put = OptionQuote(bid = put - 0.1, ask = put + 0.1),
    )
}
