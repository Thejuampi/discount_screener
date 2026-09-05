package com.discountscreener.core.earnings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RevenueTrailTest {

    @Test
    fun three_prints_are_not_a_trail() {
        assertNull(revenueTrailOf(listOf(100L, 100L, 50L)))
    }

    @Test
    fun four_equal_prints_have_no_scale() {
        assertNull(revenueTrailOf(listOf(100L, 100L, 100L, 100L)))
    }

    @Test
    fun a_last_print_two_sd_below_the_median_is_a_shortfall() {
        assertEquals(20_000, revenueTrailOf(listOf(100L, 100L, 100L, 50L))!!.shortfallZBps)
    }
}
