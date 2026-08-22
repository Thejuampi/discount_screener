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

    @Test
    fun equity_cents_match_one_claim_on_the_book() {
        var perShare = ResidualIncomeMath.valuePerShareCents(
            book0 = 100.0,
            shares = 1.0,
            roe0Bps = 1_200,
            costOfEquityBps = 900,
            retention = 0.5,
            fadeYears = 5,
            longRunRoeBps = 1_200,
            stableGrowthBps = 300,
        )
        var equity = ResidualIncomeMath.valueEquityCents(
            book0 = 100.0,
            roe0Bps = 1_200,
            costOfEquityBps = 900,
            retention = 0.5,
            fadeYears = 5,
            longRunRoeBps = 1_200,
            stableGrowthBps = 300,
        )
        assertEquals(perShare, equity)
    }
}
