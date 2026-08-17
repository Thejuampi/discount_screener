package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.engine.PriceSpeechPolicy
import com.discountscreener.core.engine.ValuationDecisionPolicy
import com.discountscreener.core.engine.ValuationJudgmentReason
import com.discountscreener.core.engine.ValuationJudgmentStatus
import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.HonestyKnob
import com.discountscreener.core.model.ProjectedValuationJudgment
import com.discountscreener.core.model.ValuationHonesty
import java.util.Locale

data class ValuationJudgmentUi(
    val stanceLabel: String,
    val relationLabel: String,
    val showPrimary: Boolean,
    val primaryCents: Long?,
    val primarySourceLabel: String?,
    val reasonLines: List<String>,
    val alertLines: List<String>,
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
    val forecastHeadline: String,
    val forecastSourceLine: String,
    val caveatLines: List<String>,
    val honestyModeLabel: String,
    val honestValueLine: String?,
    val nonHonestValueLine: String?,
    val nonHonestReason: String?,
    val nonHonestTitle: String?,
    val nonHonestLines: List<String>,
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
        alertLines = buildList {
            snapshot.identityUnavailableReason?.takeIf { it.isNotBlank() }?.let(::add)
            addAll(snapshot.providerRefuseLines.filter { it.isNotBlank() })
        }.distinct(),
        caveatLines = snapshot.identityCaveatLines.filter { it.isNotBlank() },
        forecastHeadline = forecastHeadline(snapshot),
        forecastSourceLine = forecastSourceLine(snapshot),
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
        horizonPriceLabel = snapshot.identityModelLabel ?: "Identity model",
        cashLabel = "Cash identity",
        honestyModeLabel = honestyModeLabel(snapshot),
        honestValueLine = honestValueLine(snapshot),
        nonHonestValueLine = nonHonestValueLine(snapshot),
        nonHonestReason = nonHonestReason(snapshot),
        nonHonestTitle = nonHonestTitle(snapshot),
        nonHonestLines = nonHonestLines(snapshot),
    )
}

private fun forecastHeadline(snapshot: ProjectedValuationJudgment): String {
    var price = snapshot.lastPriceCents?.takeIf { it > 0L }
    var priceText = price?.let(::moneyLine) ?: "—"
    var street = snapshot.streetBaseCents?.takeIf { it > 0L }
    if (street == null) {
        return "Price $priceText  No analyst forecast"
    }
    return "Price $priceText  Analyst ${moneyLine(street)}"
}

private fun forecastSourceLine(snapshot: ProjectedValuationJudgment): String {
    var street = snapshot.streetBaseCents?.takeIf { it > 0L }
    if (street == null) {
        return "No analyst range."
    }
    return "Forecast is the analyst range."
}

private fun honestyModeLabel(snapshot: ProjectedValuationJudgment): String =
    when (snapshot.honestyMode) {
        ValuationHonesty.Honest -> "Working number is Honest."
        ValuationHonesty.NonHonest -> "Working number is Non-honest."
    }

private fun honestValueLine(snapshot: ProjectedValuationJudgment): String? {
    var cents = snapshot.streetImplied?.honestBaseCents
        ?: snapshot.identityBaseCents
        ?: snapshot.cashIdentityCents
        ?: return null
    if (cents <= 0L) return null
    return "Honest ${moneyLine(cents)}"
}

private fun nonHonestValueLine(snapshot: ProjectedValuationJudgment): String? {
    var implied = snapshot.streetImplied ?: return null
    var cents = implied.impliedBaseCents ?: return null
    if (cents <= 0L) return null
    return "Non-honest ${moneyLine(cents)}"
}

private fun nonHonestReason(snapshot: ProjectedValuationJudgment): String? {
    var implied = snapshot.streetImplied ?: return null
    if (implied.aligned) {
        return "Honest and Street already sit together. No input was bent."
    }
    var knob = implied.winningKnob ?: return "Street is not reachable by bending one input."
    var fromBps = implied.winningHonestBps ?: return "Street is not reachable by bending one input."
    var toBps = implied.winningImpliedBps ?: return "Street is not reachable by bending one input."
    return "This number bends the ${knobLabel(knob)} from ${bpsPercent(fromBps)} to ${bpsPercent(toBps)} so it matches Street."
}

private fun knobLabel(knob: HonestyKnob): String = when (knob) {
    HonestyKnob.StableMargin -> "stable cash margin"
    HonestyKnob.NearTermGrowth -> "near-term growth"
    HonestyKnob.DiscountRate -> "discount rate"
    HonestyKnob.StartingRoe -> "starting return on equity"
}

private fun bpsPercent(bps: Int): String {
    var pct = bps / 100.0
    return String.format(Locale.US, "%.2f%%", pct)
}

private fun moneyLine(cents: Long): String =
    "$" + String.format(Locale.US, "%.2f", cents / 100.0)

private fun nonHonestTitle(snapshot: ProjectedValuationJudgment): String? {
    if (snapshot.streetImplied == null) return null
    return nonHonestValueLine(snapshot) ?: "Non-honest"
}

private fun nonHonestLines(snapshot: ProjectedValuationJudgment): List<String> {
    var implied = snapshot.streetImplied ?: return emptyList()
    return implied.knobs.map { knob -> knob.note }
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
