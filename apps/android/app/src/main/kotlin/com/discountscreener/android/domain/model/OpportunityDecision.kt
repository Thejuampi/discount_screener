package com.discountscreener.android.domain.model

import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel

data class OpportunityDecisionGate(
    val name: String,
    val value: String,
    val blocked: Boolean,
)

data class OpportunityDecisionExplanation(
    val state: RowDecisionState?,
    val why: String,
    val gates: List<OpportunityDecisionGate>,
)

/**
 * Why an opportunity row is Act, Watch or Avoid.
 *
 * The gates run in a fixed order. The first one that fails sets the state. Act is the residue
 * when every gate passes and the score meets the model cut with High confidence.
 *
 * Freshness reports and does not decide. A row restored from the database is judged on the numbers
 * it was filed with, which is what the app last decided about it, and the Freshness gate says the
 * decision is not from this refresh. The screen fades the tag to say the same thing without words.
 */
fun explainOpportunityDecision(
    freshness: RowFreshness,
    confidence: ConfidenceBand,
    upsideBps: Int?,
    compositeScore: Int,
    trustNote: String?,
    scoringModel: OpportunityScoringModel,
): OpportunityDecisionExplanation {
    var avoidBelow = OpportunityEngine.avoidBelowScore(scoringModel)
    var actAtOrAbove = OpportunityEngine.actAtOrAboveScore(scoringModel)
    var live = decisionTagIsCurrent(freshness)
    var namedUpside = upsideBps != null
    var upsideOk = upsideBps != null && upsideBps > 0
    var scoreAvoids = compositeScore < avoidBelow
    var scoreMeetsAct = compositeScore >= actAtOrAbove
    var trustClear = trustNote == null
    var highConfidence = confidence == ConfidenceBand.High

    var state: RowDecisionState?
    var why: String
    var blocked: String?
    when {
        confidence == ConfidenceBand.Low -> {
            state = RowDecisionState.Avoid
            why = "Avoid because confidence is Low."
            blocked = "Confidence"
        }
        !namedUpside -> {
            state = RowDecisionState.Watch
            why = "Watch because judgment names no primary."
            blocked = "Upside"
        }
        !upsideOk -> {
            state = RowDecisionState.Avoid
            why = "Avoid because upside is not positive."
            blocked = "Upside"
        }
        scoreAvoids -> {
            state = RowDecisionState.Avoid
            why = "Avoid because score $compositeScore is below $avoidBelow."
            blocked = "Composite"
        }
        !trustClear -> {
            state = RowDecisionState.Watch
            why = "Watch because $trustNote."
            blocked = "Trust"
        }
        highConfidence && scoreMeetsAct -> {
            state = RowDecisionState.Act
            why = "Act because score $compositeScore meets $actAtOrAbove and confidence is High."
            blocked = null
        }
        !scoreMeetsAct && !highConfidence -> {
            state = RowDecisionState.Watch
            why = "Watch because score $compositeScore is below Act $actAtOrAbove and confidence is not High."
            blocked = "Composite"
        }
        !scoreMeetsAct -> {
            state = RowDecisionState.Watch
            why = "Watch because score $compositeScore is below Act $actAtOrAbove."
            blocked = "Composite"
        }
        else -> {
            state = RowDecisionState.Watch
            why = "Watch because confidence is not High."
            blocked = "Confidence"
        }
    }

    var scoreValue = when {
        scoreAvoids -> "$compositeScore < $avoidBelow"
        scoreMeetsAct -> "$compositeScore ≥ $actAtOrAbove"
        else -> "$compositeScore < $actAtOrAbove"
    }
    var gates = listOf(
        OpportunityDecisionGate("Freshness", if (live) "Live" else "Not live", !live),
        OpportunityDecisionGate("Confidence", confidence.name, blocked == "Confidence"),
        OpportunityDecisionGate("Upside", formatDecisionUpside(upsideBps), blocked == "Upside"),
        OpportunityDecisionGate("Composite", scoreValue, blocked == "Composite"),
        OpportunityDecisionGate("Trust", trustNote ?: "Clear", blocked == "Trust"),
    )
    return OpportunityDecisionExplanation(state = state, why = why, gates = gates)
}

fun explainOpportunityDecision(
    row: OpportunityListRow,
    scoringModel: OpportunityScoringModel,
): OpportunityDecisionExplanation = explainOpportunityDecision(
    freshness = row.freshness,
    confidence = row.confidence,
    upsideBps = row.upsideBps,
    compositeScore = row.compositeScore,
    trustNote = row.trustNote,
    scoringModel = scoringModel,
)

private fun formatDecisionUpside(upsideBps: Int?): String {
    if (upsideBps == null) return "No named primary"
    var percent = upsideBps / 100
    return if (percent > 0) "+$percent%" else "$percent%"
}
