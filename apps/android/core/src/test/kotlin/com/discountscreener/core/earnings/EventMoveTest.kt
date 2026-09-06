package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventMoveTest {

    @Test
    fun the_days_between_a_report_and_its_expiry_skip_the_weekend() {
        assertEquals(5, tradingDaysBetween(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 27)))
    }

    @Test
    fun an_expiry_on_the_report_day_itself_counts_no_days() {
        assertEquals(0, tradingDaysBetween(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 20)))
    }

    @Test
    fun an_expiry_before_the_report_counts_no_days() {
        assertEquals(0, tradingDaysBetween(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 18)))
    }

    @Test
    fun the_quiet_day_move_is_the_median_of_the_daily_moves() {
        assertEquals(100, normalDailyMoveBps(series(30, stepBps = 100)))
    }

    @Test
    fun one_earnings_jump_never_drags_the_quiet_day_move_with_it() {
        var withJump = series(30, stepBps = 100) + DailyClose(LocalDate.of(2026, 9, 1), 100_000L)

        assertEquals(100, normalDailyMoveBps(withJump))
    }

    @Test
    fun a_history_too_short_to_read_refuses() {
        assertNull(normalDailyMoveBps(series(10, stepBps = 100)))
    }

    @Test
    fun a_close_of_zero_after_the_move_never_enters_the_quiet_day() {
        var closes = series(21, stepBps = 100).toMutableList()
        closes[closes.lastIndex] = closes.last().copy(closeCents = 0L)

        assertNull(normalDailyMoveBps(closes))
    }

    @Test
    fun a_move_priced_to_the_day_of_the_report_is_all_event() {
        assertEquals(700, eventMoveBps(totalMoveBps = 700, normalDailyBps = 120, tradingDaysToExpiry = 1))
    }

    @Test
    fun a_ticker_with_no_readable_history_keeps_the_whole_priced_move() {
        assertEquals(700, eventMoveBps(totalMoveBps = 700, normalDailyBps = null, tradingDaysToExpiry = 5))
    }

    @Test
    fun the_quiet_days_between_the_report_and_the_expiry_come_out_of_the_move() {
        assertEquals(600, eventMoveBps(totalMoveBps = 700, normalDailyBps = 180, tradingDaysToExpiry = 5))
    }

    @Test
    fun a_longer_expiry_leaves_a_smaller_event_move_than_a_nearer_one() {
        var near = eventMoveBps(totalMoveBps = 700, normalDailyBps = 180, tradingDaysToExpiry = 3)!!

        assertEquals(true, near > eventMoveBps(700, normalDailyBps = 180, tradingDaysToExpiry = 5)!!)
    }

    @Test
    fun a_quiet_drift_wider_than_the_priced_move_never_zeroes_the_event() {
        assertEquals(210, eventMoveBps(totalMoveBps = 700, normalDailyBps = 900, tradingDaysToExpiry = 5))
    }

    @Test
    fun an_event_left_under_the_floor_is_lifted_to_the_floor() {
        assertEquals(210, eventMoveBps(totalMoveBps = 700, normalDailyBps = 340, tradingDaysToExpiry = 5))
    }

    @Test
    fun a_ticker_with_no_option_chain_carries_no_event_move() {
        assertNull(eventMoveBps(totalMoveBps = null, normalDailyBps = 120, tradingDaysToExpiry = 5))
    }

    private fun series(days: Int, stepBps: Int): List<DailyClose> {
        var start = LocalDate.of(2026, 6, 1)
        var price = 10_000.0
        return (0 until days).map { index ->
            if (index > 0) price *= if (index % 2 == 0) 1.0 + stepBps / 10_000.0 else 1.0 - stepBps / 10_000.0
            DailyClose(start.plusDays(index.toLong()), price.toLong())
        }
    }
}
