package com.discountscreener.core.portfolio

enum class PositionDecision {
    Act,
    Watch,
    Avoid,
}

enum class PositionEvidence {
    Current,
    Stored,
    Missing,
}

sealed class PositionTrust {
    data object Trusted : PositionTrust()
    /** The projection emits this label for a model anchor. It carries no trust warning. */
    data object ModelValue : PositionTrust()
    data object LowConfidence : PositionTrust()
    data class Note(val text: String) : PositionTrust()
    data object Missing : PositionTrust()
}

enum class PositionLensState {
    None,
    Provisional,
    Disputed,
}

sealed class PositionPrimary {
    data class Model(val valueCents: Long) : PositionPrimary()
    data class Analyst(val valueCents: Long) : PositionPrimary()
    data object Tension : PositionPrimary()
    data object Disputed : PositionPrimary()
    data object Unavailable : PositionPrimary()
    data object Missing : PositionPrimary()
}

enum class PositionOpportunity {
    ReviewOpportunity,
    Monitor,
    ReviewPosition,
    CheckData,
}

data class PositionResearchInput(
    val decision: PositionDecision?,
    val evidence: PositionEvidence,
    val trust: PositionTrust,
    val primary: PositionPrimary,
    val marketPriceCents: Long?,
    val providerIssue: String? = null,
    val lensState: PositionLensState = PositionLensState.None,
    val score: Int? = null,
)

data class PositionResearchResult(
    val opportunity: PositionOpportunity,
    val reason: String,
    val valuationSource: String? = null,
    val valuationLabel: String? = null,
    val score: Int? = null,
    val storedMarker: String? = null,
)

fun positionResearch(input: PositionResearchInput): PositionResearchResult {
    val primaryLabel = when (input.primary) {
        is PositionPrimary.Model -> modelRelation(input.primary.valueCents, input.marketPriceCents)
        is PositionPrimary.Analyst -> analystRelation(input.primary.valueCents, input.marketPriceCents)
        PositionPrimary.Tension -> "Tension"
        PositionPrimary.Disputed -> "Disputed"
        PositionPrimary.Unavailable -> "Unavailable"
        PositionPrimary.Missing -> null
    }
    val source = when (input.primary) {
        is PositionPrimary.Model -> "Model"
        is PositionPrimary.Analyst -> "Analyst"
        else -> null
    }
    val dataReason = when {
        !input.providerIssue.isNullOrBlank() -> input.providerIssue
        input.marketPriceCents == null || input.marketPriceCents <= 0L -> "Missing quote"
        input.evidence == PositionEvidence.Stored -> "Stored evidence"
        input.evidence == PositionEvidence.Missing -> "Missing analysis"
        input.trust is PositionTrust.LowConfidence -> "Low confidence"
        input.trust is PositionTrust.Note && input.trust.text.isNotBlank() && input.trust.text != "Model value" ->
            input.trust.text
        input.trust is PositionTrust.Missing -> "Missing trust"
        input.lensState == PositionLensState.Provisional -> "Provisional evidence"
        input.lensState == PositionLensState.Disputed -> "Disputed evidence"
        input.primary == PositionPrimary.Missing -> "Missing valuation"
        input.primary is PositionPrimary.Model && input.primary.valueCents <= 0L -> "Missing valuation"
        input.primary is PositionPrimary.Analyst && input.primary.valueCents <= 0L -> "Missing valuation"
        input.primary == PositionPrimary.Tension -> "Tension"
        input.primary == PositionPrimary.Disputed -> "Disputed"
        input.primary == PositionPrimary.Unavailable -> "Unavailable valuation"
        input.decision == null -> "Missing decision"
        else -> null
    }
    val usable = dataReason == null
    val opportunity = if (!usable) {
        PositionOpportunity.CheckData
    } else {
        when (input.decision) {
            PositionDecision.Act -> PositionOpportunity.ReviewOpportunity
            PositionDecision.Watch -> PositionOpportunity.Monitor
            PositionDecision.Avoid -> PositionOpportunity.ReviewPosition
            null -> PositionOpportunity.CheckData
        }
    }
    return PositionResearchResult(
        opportunity = opportunity,
        reason = dataReason ?: when (input.decision) {
            PositionDecision.Act -> "Review opportunity"
            PositionDecision.Watch -> "Monitor"
            PositionDecision.Avoid -> "Review position"
            null -> "Missing decision"
        },
        valuationSource = source,
        valuationLabel = primaryLabel,
        score = input.score,
        storedMarker = if (input.evidence == PositionEvidence.Stored) "Stored" else null,
    )
}

private fun modelRelation(valueCents: Long, priceCents: Long?): String? {
    if (priceCents == null || priceCents <= 0L || valueCents <= 0L) return null
    return when {
        valueCents > priceCents -> "Model: Undervalued"
        valueCents < priceCents -> "Model: Overvalued"
        else -> "Model: At value"
    }
}

private fun analystRelation(valueCents: Long, priceCents: Long?): String? {
    if (priceCents == null || priceCents <= 0L || valueCents <= 0L) return null
    return when {
        valueCents > priceCents -> "Analyst: Above price"
        valueCents < priceCents -> "Analyst: Below price"
        else -> "Analyst: At price"
    }
}
