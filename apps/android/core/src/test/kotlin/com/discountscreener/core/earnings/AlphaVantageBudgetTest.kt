package com.discountscreener.core.earnings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlphaVantageBudgetTest {

    @Test
    fun the_twenty_fifth_call_of_the_day_is_still_admitted() {
        var budget = AlphaVantageBudget(dayEpoch = DAY, lastCallEpoch = NOW - 60, callsToday = 24)
        assertEquals(25, admitAlphaVantageCall(budget, NOW)?.callsToday)
    }

    @Test
    fun the_twenty_sixth_call_of_the_day_is_refused() {
        var budget = AlphaVantageBudget(dayEpoch = DAY, lastCallEpoch = NOW - 60, callsToday = 25)
        assertNull(admitAlphaVantageCall(budget, NOW))
    }

    @Test
    fun a_call_inside_the_minute_gap_is_refused() {
        var budget = AlphaVantageBudget(dayEpoch = DAY, lastCallEpoch = NOW - 5, callsToday = 1)
        assertNull(admitAlphaVantageCall(budget, NOW))
    }

    @Test
    fun a_new_day_resets_the_count() {
        var budget = AlphaVantageBudget(dayEpoch = DAY - 1, lastCallEpoch = NOW - 86_400, callsToday = 25)
        assertEquals(1, admitAlphaVantageCall(budget, NOW)?.callsToday)
    }

    @Test
    fun a_pair_inside_the_day_limit_is_admitted() {
        var budget = AlphaVantageBudget(dayEpoch = DAY, lastCallEpoch = NOW - 60, callsToday = 23)
        assertEquals(25, admitAlphaVantageCall(budget, NOW, count = 2)?.callsToday)
    }

    @Test
    fun a_pair_that_would_pass_the_day_limit_is_refused() {
        var budget = AlphaVantageBudget(dayEpoch = DAY, lastCallEpoch = NOW - 60, callsToday = 24)
        assertNull(admitAlphaVantageCall(budget, NOW, count = 2))
    }

    private companion object {
        const val NOW = 1_787_770_800L
        val DAY = java.time.Instant.ofEpochSecond(NOW).atZone(EXCHANGE_ZONE).toLocalDate().toEpochDay()
    }
}
