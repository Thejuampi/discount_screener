package com.discountscreener.core.regime

/**
 * Composite policy — environment band, stance matrix, exposure and multipliers.
 * Ported from `regime/composite.rs`.
 */

data class CompositeInput(
    val trend: PillarResult,
    val breadth: PillarResult,
    /** Score is *stress*: high is bad for the environment, so it is inverted before weighting. */
    val volatility: PillarResult,
    /** Score is *contrarian*: positive means buy the fear. */
    val sentiment: PillarResult,
    val crossAsset: PillarResult,
    /** High is healthy, low is fragile. */
    val quality: PillarResult,
    val spyDrawdownFromAthPct: Double? = null,
    val breadthMa200Pct: Double? = null,
    val vixTermRatio: Double? = null,
    val cnnFng: Double? = null,
    val prevExposurePct: Int? = null,
)

data class CompositeOutput(
    val environmentScore: Int,
    val sentimentScore: Int,
    val qualityScore: Int,
    val environmentBand: String,
    val actionStance: String,
    val primaryRegime: String,
    val suggestedExposurePct: Int,
    val cashBufferPct: Int,
    val newRiskMultiplierBps: Int,
    val addBias: Int,
    val preferQuality: Boolean,
    val globalConfidenceBps: Int,
    val weightTrendBps: Int,
    val weightBreadthBps: Int,
    val weightVolBps: Int,
    val weightCrossBps: Int,
    val weightQualityBps: Int,
    val crisisCapApplied: Boolean,
    val qualityHaircutApplied: Boolean,
)

/** Base weights, scaled by each pillar's confidence and then renormalized. */
private const val W_TREND = 0.22
private const val W_BREADTH = 0.22
private const val W_VOL = 0.18
private const val W_CROSS = 0.16
private const val W_QUALITY = 0.14
// The residual 0.08 is reserved and absorbed by renormalization.

private fun confidenceWeight(base: Double, confidenceBps: Int): Double =
    base * clamp(confidenceBps.toDouble() / 10_000.0, 0.0, 1.0)

fun compose(input: CompositeInput): CompositeOutput {
    val weightTrend = confidenceWeight(W_TREND, input.trend.confidenceBps)
    val weightBreadth = confidenceWeight(W_BREADTH, input.breadth.confidenceBps)
    val weightVol = confidenceWeight(W_VOL, input.volatility.confidenceBps)
    val weightCross = confidenceWeight(W_CROSS, input.crossAsset.confidenceBps)
    val weightQuality = confidenceWeight(W_QUALITY, input.quality.confidenceBps)

    val environmentScore = weightedMeanI32(
        listOf(
            input.trend.score to weightTrend,
            input.breadth.score to weightBreadth,
            -input.volatility.score to weightVol,
            input.crossAsset.score to weightCross,
            input.quality.score to weightQuality,
        ),
    ) ?: 0
    val sentimentScore = input.sentiment.score
    val qualityScore = input.quality.score

    val totalWeight = weightTrend + weightBreadth + weightVol + weightCross + weightQuality
    fun toBps(weight: Double): Int =
        if (totalWeight <= 0.0) 0 else truncateToU32(roundHalfAwayFromZero(weight / totalWeight * 10_000.0).toDouble())

    val breadthCrash = input.breadthMa200Pct?.let { it < 30.0 } ?: false
    val backwardation = input.vixTermRatio?.let { it > 1.05 } ?: false
    val deepDrawdown = input.spyDrawdownFromAthPct?.let { it >= 20.0 } ?: false
    val crisisOverride = (input.volatility.score >= 70 && breadthCrash && backwardation) ||
        (environmentScore <= -60 && deepDrawdown)
    val environmentBand = if (crisisOverride) "Crisis" else envBand(environmentScore)

    val sentimentZone = input.cnnFng?.let { fngZone(it) } ?: sentimentZoneFromScore(sentimentScore)
    val actionStance = stanceMatrix(environmentBand, sentimentZone)
    val primaryRegime = classifyPrimaryRegime(
        env = environmentBand,
        sentimentZone = sentimentZone,
        environmentScore = environmentScore,
        drawdown = input.spyDrawdownFromAthPct,
        breadth200 = input.breadthMa200Pct,
        stress = input.volatility.score,
        sentiment = sentimentScore,
    )

    var raw = logisticExposurePct(environmentScore)
    var qualityHaircutApplied = false
    if (qualityScore < -30 && input.quality.confidenceBps >= 3000) {
        raw *= 0.80
        qualityHaircutApplied = true
    } else if (qualityScore < -10 && input.quality.confidenceBps >= 3000) {
        raw *= 0.90
        qualityHaircutApplied = true
    }

    var crisisCapApplied = false
    if (environmentBand == "Crisis" || (input.volatility.score >= 75 && breadthCrash)) {
        raw = minOf(raw, 35.0)
        crisisCapApplied = true
    }

    raw = clamp(raw, 15.0, 100.0)
    val stepped = clampI32(roundToStep(raw, 5.0), 15, 100)
    val suggestedExposurePct = hysteresisU32(input.prevExposurePct, stepped, 5)
    val cashBufferPct = minOf((100 - suggestedExposurePct).coerceAtLeast(0), 50)

    val risk = stanceRiskParams(actionStance, suggestedExposurePct)

    val present = listOf(
        input.trend.confidenceBps,
        input.breadth.confidenceBps,
        input.volatility.confidenceBps,
        input.sentiment.confidenceBps,
        input.crossAsset.confidenceBps,
        input.quality.confidenceBps,
    ).filter { it > 0 }
    // Integer division, matching Rust's u32 arithmetic — the truncation is part of the number.
    val meanConfidence = if (present.isEmpty()) 0 else present.sum() / present.size
    val active = present.count { it >= 2000 }
    val coverage = clamp(active.toDouble() / 6.0, 0.25, 1.0)

    return CompositeOutput(
        environmentScore = environmentScore,
        sentimentScore = sentimentScore,
        qualityScore = qualityScore,
        environmentBand = environmentBand,
        actionStance = actionStance,
        primaryRegime = primaryRegime,
        suggestedExposurePct = suggestedExposurePct,
        cashBufferPct = cashBufferPct,
        newRiskMultiplierBps = risk.multiplierBps,
        addBias = risk.addBias,
        preferQuality = risk.preferQuality,
        globalConfidenceBps = truncateToU32(roundHalfAwayFromZero(meanConfidence.toDouble() * coverage).toDouble()),
        weightTrendBps = toBps(weightTrend),
        weightBreadthBps = toBps(weightBreadth),
        weightVolBps = toBps(weightVol),
        weightCrossBps = toBps(weightCross),
        weightQualityBps = toBps(weightQuality),
        crisisCapApplied = crisisCapApplied,
        qualityHaircutApplied = qualityHaircutApplied,
    )
}

fun envBand(environmentScore: Int): String = when {
    environmentScore <= -60 -> "Crisis"
    environmentScore <= -20 -> "RiskOff"
    environmentScore < 20 -> "Neutral"
    environmentScore < 60 -> "RiskOn"
    else -> "StrongRiskOn"
}

/** The sentiment score is contrarian: +100 is extreme fear, −100 extreme greed. */
private fun sentimentZoneFromScore(score: Int): String = when {
    score >= 50 -> "ExtremeFear"
    score >= 20 -> "Fear"
    score > -20 -> "Neutral"
    score > -50 -> "Greed"
    else -> "ExtremeGreed"
}

/** The full environment × sentiment policy matrix. Every one of the 25 cells is explicit. */
fun stanceMatrix(env: String, sentiment: String): String = when (env) {
    "Crisis" -> when (sentiment) {
        "ExtremeFear" -> "BloodInStreets"
        "Fear" -> "Defend"
        "Neutral" -> "Defend"
        "Greed" -> "Denial"
        "ExtremeGreed" -> "UnstableBlowoff"
        else -> "Mixed"
    }
    "RiskOff" -> when (sentiment) {
        "ExtremeFear" -> "Washout"
        "Fear" -> "SelectiveBuy"
        "Neutral" -> "Hold"
        "Greed" -> "Reduce"
        "ExtremeGreed" -> "Denial"
        else -> "Mixed"
    }
    "Neutral" -> when (sentiment) {
        "ExtremeFear" -> "Accumulate"
        "Fear" -> "SelectiveBuy"
        "Neutral" -> "Neutral"
        "Greed" -> "HoldTrim"
        "ExtremeGreed" -> "Reduce"
        else -> "Mixed"
    }
    "RiskOn" -> when (sentiment) {
        "ExtremeFear" -> "HealthyPullback"
        "Fear" -> "Deploy"
        "Neutral" -> "TrendDeploy"
        "Greed" -> "HoldTrim"
        "ExtremeGreed" -> "Euphoria"
        else -> "Mixed"
    }
    "StrongRiskOn" -> when (sentiment) {
        "ExtremeFear" -> "Deploy"
        "Fear" -> "TrendDeploy"
        "Neutral" -> "TrendDeploy"
        "Greed" -> "Euphoria"
        "ExtremeGreed" -> "Distribute"
        else -> "Mixed"
    }
    "Unknown" -> "Unknown"
    else -> "Mixed"
}

private fun classifyPrimaryRegime(
    env: String,
    sentimentZone: String,
    environmentScore: Int,
    drawdown: Double?,
    breadth200: Double?,
    stress: Int,
    sentiment: Int,
): String {
    val dd = drawdown ?: 0.0
    val breadth = breadth200 ?: 50.0
    val narrow = breadth < 40.0 && environmentScore > 20

    if (env == "Crisis" && (sentimentZone == "ExtremeFear" || stress >= 70)) return "Capitulation"
    if (env == "Crisis" || (environmentScore < -40 && dd >= 20.0)) return "Bear"
    // Snapback: recovering out of deep fear while the tape is still scared.
    if (environmentScore > -20 && environmentScore < 40 && sentiment >= 30 && dd >= 10.0 && stress < 60) {
        return "Snapback"
    }
    if (env == "RiskOff" && dd >= 5.0 && dd < 20.0) return "Correction"
    if (env == "RiskOff") return "Bear"
    if (env == "StrongRiskOn" && sentimentZone != "ExtremeGreed" && breadth >= 50.0) return "StrongBull"
    if ((env == "RiskOn" || env == "StrongRiskOn") && (sentimentZone == "ExtremeGreed" || narrow)) return "LateBull"
    if (env == "RiskOn" || env == "StrongRiskOn") return "Bull"
    if (env == "Neutral") return "Range"
    return "Unknown"
}

private data class StanceRisk(val multiplierBps: Int, val addBias: Int, val preferQuality: Boolean)

private fun stanceRiskParams(stance: String, ceiling: Int): StanceRisk {
    val base = ceiling.toDouble() / 100.0
    val (multiplier, bias, quality) = when (stance) {
        "BloodInStreets" -> Triple(base * 0.55, 2, true)
        "Washout" -> Triple(base * 0.65, 2, true)
        "Accumulate" -> Triple(base * 0.80, 1, true)
        "SelectiveBuy" -> Triple(base * 0.70, 1, true)
        "HealthyPullback" -> Triple(base * 1.05, 1, false)
        "Deploy" -> Triple(base * 1.05, 1, false)
        "TrendDeploy" -> Triple(base * 1.10, 1, false)
        "Neutral", "Hold" -> Triple(base * 0.90, 0, false)
        "HoldTrim" -> Triple(base * 0.75, -1, false)
        "Reduce" -> Triple(base * 0.60, -1, true)
        "Euphoria" -> Triple(base * 0.65, -2, true)
        "Distribute" -> Triple(base * 0.55, -2, true)
        "Denial" -> Triple(base * 0.45, -2, true)
        "Defend" -> Triple(base * 0.50, -1, true)
        "UnstableBlowoff" -> Triple(base * 0.35, -2, true)
        else -> Triple(base, 0, false)
    }
    return StanceRisk(
        multiplierBps = clampI32(roundHalfAwayFromZero(multiplier * 10_000.0), 2500, 12_500),
        addBias = bias,
        preferQuality = quality,
    )
}
