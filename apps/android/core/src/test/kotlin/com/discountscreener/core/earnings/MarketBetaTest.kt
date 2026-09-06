package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarketBetaTest {

    @Test
    fun a_ticker_that_rides_the_index_one_for_one_measures_a_beta_of_one() {
        assertEquals(1.0, beta(multiple = 1.0)!!, 0.0001)
    }

    @Test
    fun a_ticker_that_moves_half_again_as_hard_measures_it() {
        assertEquals(1.5, beta(multiple = 1.5)!!, 0.0001)
    }

    @Test
    fun a_ticker_that_moves_against_the_index_measures_a_negative_beta() {
        assertEquals(-0.8, beta(multiple = -0.8)!!, 0.0001)
    }

    @Test
    fun a_history_too_short_to_fit_a_slope_measures_nothing() {
        assertNull(beta(multiple = 1.0, days = MIN_BETA_SAMPLE - 1))
    }

    @Test
    fun the_shortest_history_that_still_fits_a_slope_measures_it() {
        assertEquals(1.0, beta(multiple = 1.0, days = MIN_BETA_SAMPLE)!!, 0.0001)
    }

    @Test
    fun an_index_that_never_moved_divides_by_nothing_and_measures_nothing() {
        var flat = (0..200).map { DailyClose(START.plusDays(it.toLong()), BASE_CENTS.roundToLong()) }

        assertNull(marketBetaExcludingEvents(walk(1.0), flat, emptyList()))
    }

    @Test
    fun a_close_of_zero_never_becomes_a_return_of_its_own() {
        var broken = walk(1.0).map { if (it.date == START.plusDays(5)) it.copy(closeCents = 0L) else it }

        assertEquals(1.0, marketBetaExcludingEvents(broken, index(), emptyList())!!, 0.0001)
    }

    @Test
    fun the_report_days_are_left_out_of_the_slope() {
        var event = START.plusDays(100)
        var jumped = walk(1.0).map { if (it.date >= event) it.copy(closeCents = it.closeCents * 2) else it }

        assertEquals(1.0, marketBetaExcludingEvents(jumped, index(), listOf(event))!!, 0.0001)
    }

    @Test
    fun an_empty_history_measures_nothing() {
        assertNull(marketBetaExcludingEvents(emptyList(), index(), emptyList()))
    }

    @Test
    fun the_abnormal_return_takes_out_the_share_of_the_index_the_beta_names() {
        assertEquals(160, abnormalReturnBps(stockReturnBps = 500, marketReturnBps = 200, beta = 1.7))
    }

    @Test
    fun a_ticker_with_no_beta_on_file_is_treated_as_riding_the_index_one_for_one() {
        assertEquals(300, abnormalReturnBps(stockReturnBps = 500, marketReturnBps = 200, beta = null))
    }

    private fun beta(multiple: Double, days: Int = 200): Double? =
        marketBetaExcludingEvents(walk(multiple, days), index(days), emptyList())

    private fun index(days: Int = 200): List<DailyClose> =
        (0..days).map { DailyClose(START.plusDays(it.toLong()), (BASE_CENTS * factor(it)).roundToLong()) }

    private fun walk(multiple: Double, days: Int = 200): List<DailyClose> =
        (0..days).map { index ->
            var level = BASE_CENTS
            for (step in 1..index) {
                level *= 1.0 + multiple * dayReturn(step)
            }
            DailyClose(START.plusDays(index.toLong()), level.roundToLong())
        }

    private fun factor(index: Int): Double {
        var level = 1.0
        for (step in 1..index) {
            level *= 1.0 + dayReturn(step)
        }
        return level
    }

    private fun dayReturn(step: Int): Double = when (step % 4) {
        0 -> 0.010
        1 -> -0.006
        2 -> 0.004
        else -> -0.002
    }

    private fun Double.roundToLong(): Long = Math.round(this)

    private companion object {
        const val BASE_CENTS = 100_000_000.0
        val START: LocalDate = LocalDate.of(2024, 1, 1)
    }
}
