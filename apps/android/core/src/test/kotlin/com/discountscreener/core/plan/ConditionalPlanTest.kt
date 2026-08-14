package com.discountscreener.core.plan

import com.discountscreener.core.model.OpportunityScoringModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionalPlanTest {
    @Test
    fun missing_technical_score_cannot_stay_act() {
        var plan = buildConditionalPlan(actRow(technicalScore = null))
        assertEquals(PlanStance.WaitZone, plan.stance)
    }

    @Test
    fun weak_composite_cannot_act_even_with_a_shown_zone() {
        var plan = buildConditionalPlan(actRow(composite = 22, technicalScore = 20))
        assertEquals(PlanStance.WaitZone, plan.stance)
    }

    @Test
    fun clean_act_in_zone_stays_act() {
        var plan = buildConditionalPlan(actRow(composite = 40, technicalScore = 20))
        assertEquals(PlanStance.ActNow, plan.stance)
    }

    @Test
    fun strong_bearish_tape_becomes_avoid() {
        var plan = buildConditionalPlan(actRow(composite = 40, technicalScore = -50))
        assertEquals(PlanStance.Avoid, plan.stance)
    }

    @Test
    fun death_cross_without_strong_tape_blocks_act() {
        var plan = buildConditionalPlan(
            actRow(composite = 40, technicalScore = 12).copy(technicalSignals = listOf("50/200-")),
        )
        assertEquals(PlanStance.WaitZone, plan.stance)
    }

    @Test
    fun hold_trim_regime_demotes_act() {
        var plan = buildConditionalPlan(actRow(composite = 32, technicalScore = 20).copy(regimeStance = "HoldTrim"))
        assertEquals(PlanStance.WaitZone, plan.stance)
    }

    @Test
    fun high_score_in_zone_keeps_act_under_hold_trim() {
        var plan = buildConditionalPlan(actRow(composite = 45, technicalScore = 20).copy(regimeStance = "HoldTrim"))
        assertEquals(PlanStance.ActNow, plan.stance)
    }

    @Test
    fun setup_from_composite_uses_the_v3_cut() {
        assertEquals(SetupLabel.Buy, setupFromComposite(30))
    }

    @Test
    fun act_gate_requires_zone_and_score() {
        var gate = ActionableGateInput(
            stance = PlanStance.ActNow,
            technicalScore = 20,
            technicalSignals = emptyList(),
            compositeScore = 40,
            zoneShown = false,
            zoneConfidence = ZoneConfidence.High,
            cautionCount = 0,
            scoringModel = OpportunityScoringModel.AggressiveV4,
        )
        assertFalse(passesActionableGates(gate))
    }

    @Test
    fun atr_only_p20_is_blank_on_the_card() {
        var plan = buildConditionalPlan(
            actRow(composite = 40, technicalScore = 20).copy(
                path = shownPath().copy(timingMethod = TimingMethod.AtrDistance, pTouch20d = 61),
            ),
        )
        assertEquals(null, plan.pTouch20d)
    }
}

private fun shownPath(): CompactPricePath = CompactPricePath(
    zoneLowCents = 147_082,
    zoneHighCents = 152_186,
    zoneConfidence = ZoneConfidence.High,
    pTouch20d = 76,
    expectedSessions = 2,
    invalidationCents = 169_825,
    riskCodes = emptyList(),
    supportCodes = listOf(PathMotiveCode.InZone, PathMotiveCode.BelowValue),
    timingMethod = TimingMethod.Hybrid,
)

private fun actRow(composite: Int = 40, technicalScore: Int?): PlanRowInput = PlanRowInput(
    symbol = "SNDK",
    decision = PlanDecision.Act,
    compositeScore = composite,
    technicalScore = technicalScore,
    marketPriceCents = 152_811,
    streetFairValueCents = 169_825,
    path = shownPath(),
    scoringModel = OpportunityScoringModel.AggressiveV4,
    confidenceHigh = true,
)
