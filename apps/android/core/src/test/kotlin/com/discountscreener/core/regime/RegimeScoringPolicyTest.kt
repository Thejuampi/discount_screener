package com.discountscreener.core.regime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The policy is where the market reading turns into something that moves scores, so the property
 * that matters most is the *refusal*: below the confidence floor it must hand back nothing rather
 * than a weak steer. A refusal is visible to the user as "unavailable"; a weak policy would move
 * every score in the list while claiming to be a reading.
 *
 * Both sides of the floor are tested. A policy that always refused would pass the refusal test
 * alone, so the test at the boundary is what stops that from being the accidental behaviour.
 */
class RegimeScoringPolicyTest {
    @Test
    fun a_reading_below_the_confidence_floor_yields_no_policy() {
        assertNull(RegimeScoringPolicy.fromRegime(regime(confidenceBps = 3499)))
    }

    @Test
    fun a_reading_at_the_confidence_floor_yields_a_policy() {
        assertNotNull(RegimeScoringPolicy.fromRegime(regime(confidenceBps = 3500)))
    }

    @Test
    fun an_unknown_reading_yields_no_policy_however_confident() {
        assertNull(
            RegimeScoringPolicy.fromRegime(
                regime(confidenceBps = 9500).copy(environmentBand = "Unknown", primaryRegime = "Unknown"),
            ),
        )
    }

    @Test
    fun strength_tracks_confidence() {
        assertEquals(0.5, policy(regime(confidenceBps = 5000)).strength)
    }

    /**
     * `strength` clamps at 0.35 below, and the confidence floor is 3500 — the same point, so no
     * policy that is ever issued can hit the clamp. That is what keeps strength a faithful reading
     * of confidence rather than a floor most weak readings would flatten onto.
     *
     * The expectation is derived from [RegimeScoringPolicy.MIN_CONFIDENCE_BPS] rather than written
     * as 0.35, so lowering the floor fails here instead of silently making the clamp reachable — a
     * literal 0.35 would keep passing, since the clamp itself would supply the answer.
     */
    @Test
    fun no_issued_policy_can_reach_the_strength_clamp() {
        assertEquals(
            RegimeScoringPolicy.MIN_CONFIDENCE_BPS / 10_000.0,
            policy(regime(confidenceBps = RegimeScoringPolicy.MIN_CONFIDENCE_BPS)).strength,
        )
    }

    @Test
    fun preferring_quality_raises_the_quality_weight() {
        assertTrue(
            policy(regime().copy(preferQuality = true)).wQuality >
                policy(regime().copy(preferQuality = false)).wQuality,
        )
    }

    /** A rising tape carried by few names: the policy must stop paying for growth exposure. */
    @Test
    fun a_narrow_rally_halves_the_growth_weight() {
        assertEquals(
            deployAtBreadth(60.0).wGrowth / 2.0,
            deployAtBreadth(35.0).wGrowth,
        )
    }

    @Test
    fun weak_credit_raises_the_beta_haircut() {
        assertTrue(
            policy(regime().copy(creditScore = -40)).betaHaircutMult >
                policy(regime().copy(creditScore = 0)).betaHaircutMult,
        )
    }

    @Test
    fun extreme_fear_raises_the_appetite_for_oversold_names() {
        assertTrue(
            policy(regime().copy(cnnFearGreed = 15)).wOversoldQuality >
                policy(regime().copy(cnnFearGreed = 50)).wOversoldQuality,
        )
    }

    @Test
    fun extreme_greed_raises_the_penalty_on_extended_names() {
        assertTrue(
            policy(regime().copy(cnnFearGreed = 85)).wAntiExtension >
                policy(regime().copy(cnnFearGreed = 50)).wAntiExtension,
        )
    }

    private fun regime(confidenceBps: Int = 8000) = MarketRegime(
        primaryRegime = "Bull",
        environmentBand = "RiskOn",
        actionStance = "Accumulate",
        globalConfidenceBps = confidenceBps,
    )

    private fun deployAtBreadth(breadth: Double) = policy(
        regime().copy(actionStance = "Deploy", breadthAboveMa200Pct = breadth),
    )

    private fun policy(regime: MarketRegime): RegimeScoringPolicy =
        requireNotNull(RegimeScoringPolicy.fromRegime(regime)) { "fixture must clear the confidence floor" }
}
