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

    /**
     * Freshness reports and does not decide. A row read back from the database is judged on the
     * numbers it was filed with, so the screen can show what the app last decided about it.
     */
    @Test
    fun a_row_that_is_not_live_is_still_judged_on_the_numbers_on_file() {
        assertEquals(
            RowDecisionState.Act,
            explain(score = 42, freshness = RowFreshness.Restored).state,
        )
    }

    /** And the gate says so, so the detail screen names why the tag is drawn faded. */
    @Test
    fun a_row_that_is_not_live_blocks_the_freshness_gate() {
        assertEquals(
            OpportunityDecisionGate("Freshness", "Not live", true),
            explain(score = 42, freshness = RowFreshness.Restored).gates.first(),
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
