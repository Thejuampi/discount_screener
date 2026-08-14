package com.discountscreener.core.plan

import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.model.OpportunityScoringModel

enum class PlanStance {
    ActNow,
    ScaleIn,
    WaitZone,
    Avoid,
}

enum class PlanDecision {
    Act,
    Watch,
    Avoid,
}

enum class SetupLabel {
    StrongBuy,
    Buy,
    StrongAccumulate,
    Accumulate,
    Watch,
    HoldWait,
    Hold,
    Neutral,
    Caution,
    Distribute,
    Avoid,
    StrongAvoid,
}

data class PlanEvidence(
    val code: String,
    val text: String,
)

data class ConditionalPlan(
    val symbol: String,
    val companyName: String?,
    val stance: PlanStance,
    val zoneLowCents: Long?,
    val zoneHighCents: Long?,
    val zoneConfidence: ZoneConfidence?,
    val zoneShown: Boolean,
    val pTouch20d: Int?,
    val expectedSessions: Int?,
    val invalidationCents: Long?,
    val headline: String,
    val support: List<PlanEvidence>,
    val caution: List<PlanEvidence>,
    val urgency: Int,
    val signalClarity: Int,
    val compositeScore: Int,
    val technicalScore: Int?,
    val technicalSignals: List<String>,
    val decision: PlanDecision,
    val setupLabel: SetupLabel,
    val marketPriceCents: Long,
    val spark: List<Long>,
    val timingMethod: TimingMethod?,
)

data class PlanRowInput(
    val symbol: String,
    val companyName: String? = null,
    val decision: PlanDecision,
    val compositeScore: Int,
    val technicalScore: Int?,
    val technicalSignals: List<String> = emptyList(),
    val forecastScore: Int? = null,
    val confidenceHigh: Boolean = false,
    val marketPriceCents: Long,
    val streetFairValueCents: Long = 0,
    val dcfValueCents: Long? = null,
    val analystLowCents: Long? = null,
    val gapBps: Int? = null,
    val path: CompactPricePath? = null,
    val spark: List<Long> = emptyList(),
    val scoringModel: OpportunityScoringModel = OpportunityScoringModel.AggressiveV4,
    val regimeStance: String? = null,
)

private val MATERIAL_RISKS: Set<PathMotiveCode> = setOf(
    PathMotiveCode.Extension,
    PathMotiveCode.FarFromSupport,
    PathMotiveCode.RsiRich,
    PathMotiveCode.RsiWashed,
    PathMotiveCode.TrendAgainst,
    PathMotiveCode.EarningsSoon,
    PathMotiveCode.WeakForecast,
    PathMotiveCode.AboveValue,
)

private val STRONG_SETUP: Set<SetupLabel> = setOf(SetupLabel.StrongBuy, SetupLabel.Buy, SetupLabel.StrongAccumulate)
private val POSITIVE_SETUP: Set<SetupLabel> = STRONG_SETUP + SetupLabel.Accumulate

private val HOSTILE_REGIME: Set<String> = setOf(
    "HoldTrim",
    "Reduce",
    "Defend",
    "Denial",
    "UnstableBlowoff",
    "Euphoria",
    "Distribute",
)

fun setupFromComposite(composite: Int): SetupLabel = when {
    composite >= 50 -> SetupLabel.StrongBuy
    composite >= 30 -> SetupLabel.Buy
    composite >= 15 -> SetupLabel.Accumulate
    composite >= 0 -> SetupLabel.Watch
    composite >= -20 -> SetupLabel.Hold
    composite >= -40 -> SetupLabel.Avoid
    else -> SetupLabel.StrongAvoid
}

fun buildConditionalPlan(row: PlanRowInput): ConditionalPlan {
    var path = row.path
    var setup = setupFromComposite(row.compositeScore)
    var rawStance = deriveStance(row.decision, row.compositeScore, setup, path)
    var stance = applyTechnicalConsistency(rawStance, row.technicalScore)
    var zoneShown = shouldShowZone(path)
    var rawCaution = motivesToEvidence(path?.riskCodes.orEmpty())
    var rawSupport = motivesToEvidence(path?.supportCodes.orEmpty())
    var techCaution = technicalCautionEvidence(row.technicalScore)
    var mergedCaution = if (techCaution != null) listOf(techCaution) + rawCaution else rawCaution
    var picked = pickEvidence(stance, mergedCaution, rawSupport, row.compositeScore)
    var caution = picked.first
    var support = picked.second
    var gate = ActionableGateInput(
        stance = stance,
        technicalScore = row.technicalScore,
        technicalSignals = row.technicalSignals,
        compositeScore = row.compositeScore,
        zoneShown = zoneShown,
        zoneConfidence = path?.zoneConfidence,
        cautionCount = caution.size,
        scoringModel = row.scoringModel,
    )
    if ((stance == PlanStance.ActNow || stance == PlanStance.ScaleIn) && !passesActionableGates(gate)) {
        stance = PlanStance.WaitZone
        picked = pickEvidence(stance, mergedCaution, rawSupport, row.compositeScore)
        caution = picked.first
        support = picked.second
    }
    if (stance == PlanStance.ActNow && shouldDemoteForRegime(row, path, zoneShown)) {
        stance = PlanStance.WaitZone
        picked = pickEvidence(stance, mergedCaution, rawSupport, row.compositeScore)
        caution = picked.first
        support = picked.second
        if (caution.none { it.code == "regime" }) {
            caution = listOf(PlanEvidence("regime", PlanCopy.motive(PathMotiveCode.RegimeRisk))) + caution
            caution = caution.take(2)
        }
    }
    var p20 = publishableP20(path?.timingMethod ?: TimingMethod.Unavailable, path?.pTouch20d)
    var headline = PlanCopy.headline(
        stance = stance,
        zone = if (zoneShown) formatZone(path?.zoneLowCents, path?.zoneHighCents) else null,
        p20 = p20,
        inv = path?.invalidationCents?.let { formatDollars(it) },
        review = reviewHorizonLabel(path?.expectedSessions),
    )
    var clarity = computeSignalClarity(row, path, stance, zoneShown)
    var urgency = computeUrgency(row, path, stance, zoneShown, clarity)
    return ConditionalPlan(
        symbol = row.symbol,
        companyName = row.companyName,
        stance = stance,
        zoneLowCents = if (zoneShown) path?.zoneLowCents else null,
        zoneHighCents = if (zoneShown) path?.zoneHighCents else null,
        zoneConfidence = path?.zoneConfidence,
        zoneShown = zoneShown,
        pTouch20d = p20,
        expectedSessions = path?.expectedSessions,
        invalidationCents = path?.invalidationCents,
        headline = headline,
        support = support,
        caution = caution,
        urgency = urgency,
        signalClarity = clarity,
        compositeScore = row.compositeScore,
        technicalScore = row.technicalScore,
        technicalSignals = row.technicalSignals,
        decision = row.decision,
        setupLabel = setup,
        marketPriceCents = row.marketPriceCents,
        spark = row.spark,
        timingMethod = path?.timingMethod,
    )
}

fun applyTechnicalConsistency(stance: PlanStance, technicalScore: Int?): PlanStance {
    var adverse = technicalAdverse(technicalScore)
    if (adverse == null) return stance
    if (adverse == "strong") return PlanStance.Avoid
    if (stance == PlanStance.ActNow || stance == PlanStance.ScaleIn) return PlanStance.WaitZone
    return stance
}

fun technicalAdverse(technicalScore: Int?): String? {
    if (technicalScore == null) return null
    if (technicalScore <= -45) return "strong"
    if (technicalScore <= -15) return "mild"
    return null
}

fun hasStructuralConflict(signals: List<String>): Boolean =
    signals.contains("50/200-") || signals.contains("20/50-")

data class ActionableGateInput(
    val stance: PlanStance,
    val technicalScore: Int?,
    val technicalSignals: List<String>,
    val compositeScore: Int,
    val zoneShown: Boolean,
    val zoneConfidence: ZoneConfidence?,
    val cautionCount: Int,
    val scoringModel: OpportunityScoringModel,
)

fun passesActionableGates(plan: ActionableGateInput): Boolean {
    if (plan.technicalScore == null) return false
    if (!hasActionableTechnicalFloor(plan.technicalScore)) return false
    if (hasStructuralConflict(plan.technicalSignals) && !hasStrongTechnical(plan.technicalScore)) return false
    var actFloor = OpportunityEngine.actAtOrAboveScore(plan.scoringModel)
    if (plan.stance == PlanStance.ActNow) {
        return plan.compositeScore >= actFloor && plan.zoneShown
    }
    if (plan.stance == PlanStance.ScaleIn) {
        if (plan.compositeScore < 28) return false
        if (!plan.zoneShown) return false
        if (plan.zoneConfidence == ZoneConfidence.Low) return false
        if (plan.cautionCount >= 2) return false
        return true
    }
    return false
}

fun isActionablePriority(plan: ConditionalPlan, scoringModel: OpportunityScoringModel): Boolean =
    passesActionableGates(
        ActionableGateInput(
            stance = plan.stance,
            technicalScore = plan.technicalScore,
            technicalSignals = plan.technicalSignals,
            compositeScore = plan.compositeScore,
            zoneShown = plan.zoneShown,
            zoneConfidence = plan.zoneConfidence,
            cautionCount = plan.caution.size,
            scoringModel = scoringModel,
        ),
    )

fun isWaitPriority(plan: ConditionalPlan): Boolean {
    if (plan.stance != PlanStance.WaitZone) return false
    if (plan.compositeScore < 35) return false
    if (!plan.zoneShown) return false
    if (plan.zoneConfidence != ZoneConfidence.High && plan.zoneConfidence != ZoneConfidence.Med) return false
    return true
}

fun deriveStance(
    decision: PlanDecision,
    compositeScore: Int,
    setup: SetupLabel,
    path: CompactPricePath?,
): PlanStance {
    if (decision == PlanDecision.Avoid || setup == SetupLabel.StrongAvoid) return PlanStance.Avoid
    if (compositeScore < 0) return PlanStance.Avoid
    var riskCodes = path?.riskCodes.orEmpty()
    var material = riskCodes.count { MATERIAL_RISKS.contains(it) }
    var inZone = path?.supportCodes.orEmpty().contains(PathMotiveCode.InZone)
    var nearZone = path?.supportCodes.orEmpty().contains(PathMotiveCode.NearZone)
    var inOrNear = inZone || nearZone
    var far = riskCodes.contains(PathMotiveCode.FarFromSupport) ||
        riskCodes.contains(PathMotiveCode.Extension) ||
        riskCodes.contains(PathMotiveCode.RsiRich)
    var zoneConf = path?.zoneConfidence
    var solidZone = zoneConf == ZoneConfidence.High || zoneConf == ZoneConfidence.Med
    if (decision == PlanDecision.Act) {
        if (inOrNear && material == 0) return PlanStance.ActNow
        if (far || material >= 2) return PlanStance.WaitZone
        if (nearZone && material == 1 && solidZone && compositeScore >= 30) return PlanStance.ScaleIn
        if (inZone && material >= 1) return PlanStance.WaitZone
        if (STRONG_SETUP.contains(setup) && !far && material <= 1) {
            return if (material == 0) PlanStance.ActNow else PlanStance.WaitZone
        }
        return PlanStance.WaitZone
    }
    if (compositeScore < 15 && !POSITIVE_SETUP.contains(setup)) return PlanStance.WaitZone
    if (far || material >= 1) return PlanStance.WaitZone
    if (inOrNear && solidZone && compositeScore >= 28 && material == 0) return PlanStance.ScaleIn
    if (inOrNear && material == 0 && STRONG_SETUP.contains(setup) && solidZone) return PlanStance.ScaleIn
    return PlanStance.WaitZone
}

fun reviewHorizonLabel(sessions: Int?): String {
    if (sessions == null) return "a few weeks"
    var s = sessions.coerceAtLeast(0)
    return when {
        s <= 2 -> "1–2 sessions"
        s <= 5 -> "$s sessions"
        s <= 12 -> "1–2 weeks"
        s <= 25 -> "2–4 weeks"
        s <= 45 -> "1–2 months"
        else -> "several months"
    }
}

fun formatDollars(cents: Long): String = "$%.2f".format(cents / 100.0)

fun formatZone(low: Long?, high: Long?): String? {
    if (low == null || high == null) return null
    return "${formatDollars(low)}–${formatDollars(high)}"
}

private fun shouldShowZone(path: CompactPricePath?): Boolean {
    if (path?.zoneLowCents == null || path.zoneHighCents == null) return false
    return path.zoneConfidence == ZoneConfidence.Med || path.zoneConfidence == ZoneConfidence.High
}

private fun hasActionableTechnicalFloor(tech: Int): Boolean = tech >= 10

private fun hasStrongTechnical(tech: Int): Boolean = tech >= 30

private fun shouldDemoteForRegime(row: PlanRowInput, path: CompactPricePath?, zoneShown: Boolean): Boolean {
    var stance = row.regimeStance ?: return false
    if (!HOSTILE_REGIME.contains(stance)) return false
    var inZone = path?.supportCodes.orEmpty().contains(PathMotiveCode.InZone)
    var highConf = path?.zoneConfidence == ZoneConfidence.High
    if (inZone && highConf && zoneShown && row.compositeScore >= 40) return false
    return true
}

private fun motivesToEvidence(codes: List<PathMotiveCode>): List<PlanEvidence> =
    codes.map { PlanEvidence(it.name, PlanCopy.motive(it)) }

private fun technicalCautionEvidence(technicalScore: Int?): PlanEvidence? {
    var adverse = technicalAdverse(technicalScore) ?: return null
    if (technicalScore == null) return null
    var text = if (adverse == "strong") {
        PlanCopy.techStrongAdverse(technicalScore)
    } else {
        PlanCopy.techMildAdverse(technicalScore)
    }
    return PlanEvidence("score", text)
}

private fun pickEvidence(
    stance: PlanStance,
    caution: List<PlanEvidence>,
    support: List<PlanEvidence>,
    composite: Int,
): Pair<List<PlanEvidence>, List<PlanEvidence>> {
    if (stance == PlanStance.ActNow) {
        var s = support.take(2).toMutableList()
        var c = caution.take(1)
        if (s.isEmpty()) s.add(PlanEvidence("score", PlanCopy.composite(composite)))
        return c to s
    }
    if (stance == PlanStance.WaitZone) {
        var c = caution.take(2).toMutableList()
        var s = support.take(1)
        if (c.isEmpty()) c.add(PlanEvidence("decision", PlanCopy.timingWeak()))
        return c to s
    }
    if (stance == PlanStance.ScaleIn) {
        return caution.take(1) to support.take(2)
    }
    return caution.take(2) to support.take(1)
}

private fun computeSignalClarity(
    row: PlanRowInput,
    path: CompactPricePath?,
    stance: PlanStance,
    zoneShown: Boolean,
): Int {
    var c = 0
    c += when (stance) {
        PlanStance.ActNow -> 40
        PlanStance.WaitZone -> 25
        PlanStance.ScaleIn -> 10
        PlanStance.Avoid -> -20
    }
    if (zoneShown && path?.zoneConfidence == ZoneConfidence.High) c += 20
    else if (zoneShown && path?.zoneConfidence == ZoneConfidence.Med) c += 10
    else if (path?.zoneConfidence == ZoneConfidence.Low) c -= 25
    var material = path?.riskCodes.orEmpty().count { MATERIAL_RISKS.contains(it) }
    if (stance == PlanStance.ActNow && material == 0) c += 15
    if (stance == PlanStance.WaitZone && material >= 1) c += 10
    if (path?.supportCodes.orEmpty().contains(PathMotiveCode.InZone) && material >= 2) c -= 20
    c += when {
        row.compositeScore >= 40 -> 15
        row.compositeScore >= 30 -> 8
        row.compositeScore < 15 -> -15
        else -> 0
    }
    if (row.confidenceHigh) c += 5
    return c
}

private fun computeUrgency(
    row: PlanRowInput,
    path: CompactPricePath?,
    stance: PlanStance,
    zoneShown: Boolean,
    signalClarity: Int,
): Int {
    var u = row.compositeScore + signalClarity
    u += when (stance) {
        PlanStance.ActNow -> 50
        PlanStance.WaitZone -> 20
        PlanStance.ScaleIn -> 5
        PlanStance.Avoid -> -40
    }
    if (path?.supportCodes.orEmpty().contains(PathMotiveCode.InZone) && stance == PlanStance.ActNow) u += 30
    if (path?.supportCodes.orEmpty().contains(PathMotiveCode.NearZone) && stance == PlanStance.WaitZone) u += 15
    if (zoneShown && path?.zoneConfidence == ZoneConfidence.High) u += 12
    if (!zoneShown && path?.zoneConfidence == ZoneConfidence.Low) u -= 30
    return u
}
