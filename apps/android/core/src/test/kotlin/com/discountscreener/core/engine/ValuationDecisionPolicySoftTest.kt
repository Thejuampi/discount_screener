package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.WaccInputProvenance
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one soft/quality rule, at the boundaries where a second copy would first disagree.
 *
 * This predicate was two private implementations — judgment's and Quant Lens's — that happened to
 * agree. The boundary case is where they would drift first: exactly [ValuationDecisionPolicy.WIDE_SCENARIO_BPS]
 * wide must read solid (`>` is the comparison, not `>=`), and an unfanable analysis must read soft
 * (null width counts as wide).
 */
class ValuationDecisionPolicySoftTest {

    /** Clean inputs and a fan exactly at the cut: solid. A `>=` mutation flips this to soft. */
    @Test
    fun exactly_the_wide_cut_is_still_solid() {
        assertEquals(false, ValuationDecisionPolicy.isSoftModel(analysis(bear = 4_000L, bull = 16_000L)))
    }

    @Test
    fun just_past_the_wide_cut_is_soft() {
        assertEquals(true, ValuationDecisionPolicy.isSoftModel(analysis(bear = 4_000L, bull = 16_001L)))
    }

    /** No usable fan means no width to trust, which is the wide case by another door. */
    @Test
    fun an_unfanable_scenario_read_is_soft() {
        assertEquals(true, ValuationDecisionPolicy.isSoftModel(analysis(bear = 16_000L, bull = 4_000L)))
    }

    @Test
    fun provisional_wacc_inputs_are_soft_even_with_a_tight_fan() {
        var analysis = analysis(bear = 9_000L, bull = 11_000L)
            .copy(waccInputs = WaccInputProvenance(waccClamped = true))

        assertEquals(true, ValuationDecisionPolicy.isSoftModel(analysis))
    }

    @Test
    fun an_unreliable_point_estimate_is_soft_even_with_a_tight_fan() {
        assertEquals(
            true,
            ValuationDecisionPolicy.isSoftModel(analysis(bear = 9_000L, bull = 11_000L).copy(pointEstimateUnreliable = true)),
        )
    }

    /** The all-reported provenance is the clean control every case above perturbs. */
    @Test
    fun clean_inputs_with_a_tight_fan_are_not_soft() {
        assertEquals(false, ValuationDecisionPolicy.isSoftModel(analysis(bear = 9_000L, bull = 11_000L)))
    }

    private fun analysis(bear: Long, bull: Long): DcfAnalysis = DcfAnalysis(
        bearIntrinsicValueCents = bear,
        baseIntrinsicValueCents = 10_000L,
        bullIntrinsicValueCents = bull,
        waccBps = 900,
        baseGrowthBps = 500,
        netDebtDollars = 0L,
        waccInputs = WaccInputProvenance(),
        pointEstimateUnreliable = false,
    )
}
