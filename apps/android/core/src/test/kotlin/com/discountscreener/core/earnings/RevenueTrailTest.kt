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
    fun a_flat_prior_three_has_zero_scale() {
        assertEquals(0L, revenueTrailOf(listOf(100L, 100L, 100L, 50L))!!.scaleCents)
    }

    @Test
    fun the_latest_print_does_not_set_the_scale() {
        assertEquals(20L, revenueTrailOf(listOf(80L, 100L, 120L, 50L))!!.scaleCents)
    }

    @Test
    fun the_centre_is_the_robust_mean_of_the_prior_three() {
        assertEquals(100L, revenueTrailOf(listOf(80L, 100L, 120L, 50L))!!.centreCents)
    }

    @Test
    fun a_foreign_prior_print_refuses_the_trail() {
        assertNull(revenueTrailOf(listOf(80L, 100L, 10_000L, 50L)))
    }
}
