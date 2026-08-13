package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each scored term must leave a number the Snapshot header can print, not only a `++` suffix.
 *
 * The suffix says direction. The operator asked how the term moved the bucket. That number is
 * `weight × ramp / full weight × 100`, the same quantity the bucket score is made of.
 */
class ScoreFactorContributionTest {

    @Test
    fun fcf_yield_at_the_upper_bound_adds_its_full_weight_as_bucket_points() {
        var evidence = OpportunityEngine.aggressiveV3FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 8,
                    marketCapDollars = 100,
                ),
            ),
        )

        assertEquals(22, evidence.factors.single { it.key == "FCFy" }.bucketPoints)
    }

    @Test
    fun factor_points_in_a_bucket_sum_to_that_bucket_score() {
        var evidence = OpportunityEngine.aggressiveV3FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 8,
                    marketCapDollars = 100,
                ),
            ),
        )

        assertEquals(evidence.score, evidence.factors.sumOf { it.bucketPoints })
    }

    @Test
    fun a_missing_term_is_absent_rather_than_scored_as_zero() {
        var evidence = OpportunityEngine.aggressiveV3FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 8,
                    marketCapDollars = 100,
                ),
            ),
        )

        assertEquals(emptyList(), evidence.factors.filter { it.key == "ROE" })
    }
}
