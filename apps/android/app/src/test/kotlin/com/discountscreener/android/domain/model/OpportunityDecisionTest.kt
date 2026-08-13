package com.discountscreener.android.domain.model

import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import org.junit.Assert.assertEquals
import org.junit.Test

class OpportunityDecisionTest {

    @Test
    fun a_live_high_score_row_is_act() {
        assertEquals(RowDecisionState.Act, explain(score = 42).state)
    }

    @Test
    fun act_names_the_score_cut_and_the_confidence_gate() {
        assertEquals(
            "Act because score 42 meets 30 and confidence is High.",
            explain(score = 42).why,
        )
    }

    @Test
    fun low_confidence_is_avoid() {
        assertEquals(
            "Avoid because confidence is Low.",
            explain(score = 42, confidence = ConfidenceBand.Low).why,
        )
    }

    @Test
    fun non_positive_upside_is_avoid() {
        assertEquals(
            "Avoid because upside is not positive.",
            explain(score = 42, upsideBps = 0).why,
        )
    }

    @Test
    fun a_score_below_avoid_is_avoid() {
        assertEquals(
            "Avoid because score -5 is below 0.",
            explain(score = -5).why,
        )
    }

    @Test
    fun a_trust_note_holds_the_row_on_watch() {
        assertEquals(
            "Watch because No analyst target.",
            explain(score = 42, trustNote = "No analyst target").why,
        )
    }

    @Test
    fun a_score_below_act_holds_the_row_on_watch() {
        assertEquals(
            "Watch because score 18 is below Act 30.",
            explain(score = 18).why,
        )
    }

    @Test
    fun provisional_confidence_holds_the_row_on_watch() {
        assertEquals(
            "Watch because confidence is not High.",
            explain(score = 42, confidence = ConfidenceBand.Provisional).why,
        )
    }

    @Test
    fun a_row_that_is_not_live_has_no_decision() {
        assertEquals(
            "No decision until the row is live.",
            explain(score = 42, freshness = RowFreshness.Loading).why,
        )
    }

    @Test
    fun the_score_gate_uses_the_selected_model_cut() {
        assertEquals(
            "Act because score 10 meets 10 and confidence is High.",
            explain(score = 10, scoringModel = OpportunityScoringModel.Legacy).why,
        )
    }

    private fun explain(
        score: Int,
        freshness: RowFreshness = RowFreshness.Updated,
        confidence: ConfidenceBand = ConfidenceBand.High,
        upsideBps: Int = 2_500,
        trustNote: String? = null,
        scoringModel: OpportunityScoringModel = OpportunityScoringModel.AggressiveV3,
    ) = explainOpportunityDecision(
        freshness = freshness,
        confidence = confidence,
        upsideBps = upsideBps,
        compositeScore = score,
        trustNote = trustNote,
        scoringModel = scoringModel,
    )
}
