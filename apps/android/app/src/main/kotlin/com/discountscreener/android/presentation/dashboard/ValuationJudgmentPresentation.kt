package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.engine.PriceSpeechPolicy
import com.discountscreener.core.engine.ValuationDecisionPolicy
import com.discountscreener.core.engine.ValuationJudgmentReason
import com.discountscreener.core.engine.ValuationJudgmentStatus
import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.ProjectedValuationJudgment

data class ValuationJudgmentUi(
    val stanceLabel: String,
    val relationLabel: String,
    val showPrimary: Boolean,
    val primaryCents: Long?,
    val primarySourceLabel: String?,
    val reasonLines: List<String>,
    val identityBearCents: Long?,
    val identityBaseCents: Long?,
    val identityBullCents: Long?,
    val identityModelLabel: String?,
    val streetLowCents: Long?,
    val streetBaseCents: Long?,
    val streetHighCents: Long?,
    val femTargetCents: Long?,
    val officialGapBps: Int?,
    val lastPriceCents: Long?,
    val horizonPriceCents: Long?,
    val horizonDays: Int?,
    val cashIdentityCents: Long?,
    val upsideToHorizonBps: Int?,
    val lastPriceLabel: String,
    val horizonPriceLabel: String,
    val cashLabel: String,
)

fun presentValuationJudgment(snapshot: ProjectedValuationJudgment): ValuationJudgmentUi {
    var showPrimary = snapshot.primaryCents != null
    return ValuationJudgmentUi(
        stanceLabel = stanceLabel(snapshot.status),
        relationLabel = relationLabel(snapshot.status, snapshot.relation),
        showPrimary = showPrimary,
        primaryCents = snapshot.primaryCents,
        primarySourceLabel = if (showPrimary) primarySourceLabel(snapshot) else null,
        reasonLines = snapshot.reasonCodes.map(::reasonLabel),
        identityBearCents = snapshot.identityBearCents,
        identityBaseCents = snapshot.identityBaseCents,
        identityBullCents = snapshot.identityBullCents,
        identityModelLabel = snapshot.identityModelLabel,
        streetLowCents = snapshot.streetLowCents,
        streetBaseCents = snapshot.streetBaseCents,
        streetHighCents = snapshot.streetHighCents,
        femTargetCents = snapshot.femTargetCents,
        officialGapBps = officialGapBps(snapshot),
        lastPriceCents = snapshot.lastPriceCents,
        horizonPriceCents = snapshot.horizonPriceCents,
        horizonDays = snapshot.horizonDays,
        cashIdentityCents = snapshot.cashIdentityCents,
        upsideToHorizonBps = snapshot.upsideToHorizonBps,
        lastPriceLabel = "Price now",
        horizonPriceLabel = "Our price",
        cashLabel = "Cash identity",
    )
}

private fun stanceLabel(status: ValuationJudgmentStatus): String = when (status) {
    ValuationJudgmentStatus.Identity -> "Identity"
    ValuationJudgmentStatus.Street -> "Analyst range"
    ValuationJudgmentStatus.Tension -> "Tension"
    ValuationJudgmentStatus.Disputed -> "Disputed"
    ValuationJudgmentStatus.Unavailable -> "Unavailable"
}

private fun relationLabel(
    status: ValuationJudgmentStatus,
    relation: AnchorRelation,
): String {
    var label = when (relation) {
        AnchorRelation.Unavailable -> "No comparable pair"
        AnchorRelation.SingleSource -> "Single source"
        AnchorRelation.Aligned -> "Aligned"
        AnchorRelation.Tension -> "Tension"
        AnchorRelation.Disputed -> "Disputed"
    }
    if (label == stanceLabel(status)) return ""
    return label
}

private fun primarySourceLabel(snapshot: ProjectedValuationJudgment): String =
    when (snapshot.status) {
        ValuationJudgmentStatus.Identity -> snapshot.identityModelLabel ?: "Identity"
        ValuationJudgmentStatus.Street -> "Analyst range"
        ValuationJudgmentStatus.Tension,
        ValuationJudgmentStatus.Disputed,
        ValuationJudgmentStatus.Unavailable,
        -> snapshot.identityModelLabel ?: "Primary"
    }

private fun reasonLabel(reason: ValuationJudgmentReason): String = when (reason) {
    ValuationJudgmentReason.Unclassified -> "Business class unclassified. Valuation refused."
    ValuationJudgmentReason.NotEligible -> "This asset class is not eligible."
    ValuationJudgmentReason.MissingDrivers -> "Required drivers are missing."
    ValuationJudgmentReason.NoCompleteFamily -> "No complete identity or analyst range."
    ValuationJudgmentReason.ShareBasisMismatch -> "Share basis does not match."
    ValuationJudgmentReason.FemOnly -> "Justified multiple is attached. It is not primary."
    ValuationJudgmentReason.IncomparableAnchors -> "Anchors are not comparable."
    ValuationJudgmentReason.TensionNoPrimary -> "Tension names no single primary."
    ValuationJudgmentReason.DisputedGap -> "Disputed. Both series stay visible."
    ValuationJudgmentReason.SoftIdentity -> "Identity quality is soft."
    ValuationJudgmentReason.StreetPrimary -> "Primary is the analyst range."
    ValuationJudgmentReason.IdentityPrimary -> "Primary is the identity model."
    ValuationJudgmentReason.IllegalModelPair -> "Class and model pair is not legal."
    ValuationJudgmentReason.IncompleteStreet -> "Analyst range is incomplete."
    ValuationJudgmentReason.IncompleteIdentity -> "Identity is incomplete."
    ValuationJudgmentReason.UnusableIdentityFan -> "The identity fan is not usable."
}

private fun officialGapBps(snapshot: ProjectedValuationJudgment): Int? {
    var identityBase = snapshot.identityBaseCents ?: return null
    var streetBase = snapshot.streetBaseCents ?: return null
    return ValuationDecisionPolicy.differenceBps(identityBase, streetBase)
}
