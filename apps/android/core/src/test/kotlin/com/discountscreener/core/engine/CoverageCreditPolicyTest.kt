package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageCreditPolicyTest {
    @Test
    fun high_coverage_uses_the_tight_spread() {
        assertEquals(59, CoverageCreditPolicy.spreadBps(12.5))
    }

    @Test
    fun three_times_covered_interest_is_not_investment_grade_tight() {
        assertEquals(178, CoverageCreditPolicy.spreadBps(3.0))
    }

    @Test
    fun uncovered_interest_uses_the_wide_spread() {
        assertEquals(1_157, CoverageCreditPolicy.spreadBps(0.0))
    }

    @Test
    fun thinner_coverage_never_cheapens_the_spread() {
        assertTrue(CoverageCreditPolicy.spreadBps(2.0) > CoverageCreditPolicy.spreadBps(4.0))
    }
}
