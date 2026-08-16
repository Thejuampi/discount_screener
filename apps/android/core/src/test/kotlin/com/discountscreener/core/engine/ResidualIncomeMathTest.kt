package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class ResidualIncomeMathTest {
    @Test
    fun franchise_spread_caps_the_long_run() {
        assertEquals(1_391, ResidualIncomeMath.longRunRoeBps(1_625, 891))
    }

    @Test
    fun franchise_below_the_cap_keeps_observed_roe() {
        assertEquals(1_200, ResidualIncomeMath.longRunRoeBps(1_200, 891))
    }
}
