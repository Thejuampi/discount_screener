package com.discountscreener.core.regime

import com.discountscreener.core.engine.ScoreReading
import com.discountscreener.core.engine.scoreReading
import java.util.Locale
import kotlin.math.abs

/**
 * English prose from Windows `interpret.rs` and `narrative.rs`.
 *
 * Tone and radar radius stay in [pillarTone] / [radarRadius]. This layer only writes copy.
 */
const val SIGNAL_HINT_MIN_ABS = 25
private const val PARTIAL_CONFIDENCE_BPS = 2000
private const val MODERATE_CONFIDENCE_BPS = 4500
private const val DEGRADED_READING_CONFIDENCE_BPS = 4000
private const val CASH_BUFFER_NOTE_PCT = 25
private const val WEAK_BREADTH_PCT = 40.0

fun enrichRegime(regime: MarketRegime, composite: CompositeOutput): MarketRegime {
    var pillars = regime.pillars.map(::enrichPillar)
    var withPillars = regime.copy(pillars = pillars)
    return withPillars.copy(
        thesis = thesisOf(withPillars, composite),
        reading = buildReading(withPillars, composite),
        actionBullets = actionBullets(withPillars, composite),
        notes = withPillars.notes + policyNotes(withPillars, composite),
    )
}

internal fun interpretPillar(id: String, score: Int, confidenceBps: Int, stale: Boolean): String {
    var band = scoreBandOf(score)
    var body = when (id) {
        "trend" -> trendCopy(band)
        "breadth" -> breadthCopy(band)
        "volatility" -> volCopy(band)
        "sentiment" -> sentimentCopy(band)
        "cross_asset" -> crossCopy(band)
        "quality" -> qualityCopy(band)
        else -> "Pillar $id: score $score."
    }
    return when {
        stale || confidenceBps < PARTIAL_CONFIDENCE_BPS ->
            "$body (partial data — treat with caution)"
        confidenceBps < MODERATE_CONFIDENCE_BPS ->
            "$body Moderate confidence."
        else -> body
    }
}

internal fun interpretSignal(id: String, contribution: Int, detail: String?): String? {
    var d = detail.orEmpty()
    return when (id) {
        "cnn_fng" -> if (contribution >= SIGNAL_HINT_MIN_ABS) {
            "F&G in fear zone: contrarian accumulate bias ($d)."
        } else {
            "F&G in greed zone: bias against chasing ($d)."
        }
        "vix_term" -> if (contribution > 0) {
            "Term structure in backwardation: real stress, not just spot VIX."
        } else {
            "Term structure in contango: structural vol calm."
        }
        "vix_pctl", "vix_level" -> if (contribution > 0) {
            "Elevated VIX ($d): cut size and expect more volatility."
        } else {
            "Contained VIX ($d): less volatility friction."
        }
        "narrow_rally" -> "Narrow rally: index up without breadth — fragile."
        "broad_participation" -> "Broad participation: the advance has a base."
        "spy_ma200" -> if (contribution > 0) {
            "SPY above MA200: long-term uptrend."
        } else {
            "SPY below MA200: long-term regime impaired."
        }
        "breadth_ma200", "breadth_ma50" -> if (contribution > 0) {
            "Positive breadth ($d)."
        } else {
            "Weak breadth ($d)."
        }
        "credit_hy_ie", "credit_quality" -> if (contribution > 0) {
            "HY credit relatively strong: risk-on in fixed income."
        } else {
            "Credit under pressure: risk-off warning."
        }
        "leadership" -> if (contribution > 0) {
            "Growth/cyclical leadership: active risk appetite."
        } else {
            "Defensives lead: rotation to safety."
        }
        "avg_corr" -> if (contribution < 0) {
            "High name correlation: false diversification."
        } else {
            "Lower correlation: more useful dispersion."
        }
        "stress_breadth" -> "Stress + weak breadth together: fragile combo."
        else -> null
    }
}

internal fun thesisOf(regime: MarketRegime, composite: CompositeOutput): String {
    var fg = regime.cnnFearGreed?.toString() ?: "—"
    var fgLabel = regime.cnnFearGreedLabel ?: "n/d"
    var exp = regime.suggestedExposurePct
    var mult = formatMult(regime.newRiskMultiplierBps)
    var br = regime.breadthAboveMa200Pct?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
    var vix = regime.vix?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
    var dd = regime.spyDrawdownFromAthPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"
    var stance = stanceEn(composite.actionStance)
    var band = bandEn(composite.environmentBand)
    return when {
        composite.primaryRegime == "Capitulation" && composite.actionStance == "BloodInStreets" ->
            "Capitulation: ceiling $exp% (hostile tape) but F&G $fg ($fgLabel) implies selective accumulation bias. New risk ${mult}×. Prefer quality; no all-in."
        composite.primaryRegime == "LateBull" ||
            composite.actionStance == "Euphoria" ||
            composite.actionStance == "Distribute" ->
            "Late bull / euphoria: ceiling $exp% but do not FOMO-add. Stance $stance; F&G $fg; breadth $br."
        composite.actionStance == "HealthyPullback" ||
            composite.actionStance == "Deploy" ||
            composite.actionStance == "TrendDeploy" ->
            "Constructive tape ($band) with stance $stance. Ceiling $exp%, mult ${mult}×. F&G $fg · VIX $vix."
        composite.actionStance == "Washout" ||
            composite.actionStance == "Accumulate" ||
            composite.actionStance == "SelectiveBuy" ->
            "Fear in the tape (F&G $fg): disciplined accumulate bias. Ceiling $exp% · risk ${mult}× · SPY DD $dd."
        composite.actionStance == "Defend" || composite.actionStance == "Denial" ->
            "Defend mode: $band environment with inconsistent sentiment. Ceiling $exp%, prefer quality and ${regime.cashBufferPct}% cash buffer."
        else ->
            "Regime ${phaseEn(composite.primaryRegime)}: environment $band, stance $stance. Exposure ceiling $exp% · F&G $fg · breadth $br."
    }
}

internal fun buildReading(regime: MarketRegime, composite: CompositeOutput): String {
    var ranked = regime.pillars.sortedByDescending { it.radarRadius }
    var best = ranked.take(2).joinToString(" / ") { it.name }
    var worst = ranked.sortedBy { it.radarRadius }.take(2).joinToString(" / ") { it.name }
    var fg = regime.cnnFearGreed?.toString() ?: "n/d"
    var fgLabel = regime.cnnFearGreedLabel ?: "n/d"
    var confNote = if (regime.globalConfidenceBps < DEGRADED_READING_CONFIDENCE_BPS) {
        " Degraded reading: low global confidence from partial data."
    } else {
        ""
    }
    return "Market phase: ${phaseEn(regime.primaryRegime)} with ${bandEn(regime.environmentBand)} environment " +
        "and «${stanceEn(composite.actionStance)}» stance. " +
        "On the radar, strongest is $best and weakest $worst. " +
        "Fear & Greed at $fg ($fgLabel) is read contrarian (fear→selective accumulate opportunity; greed→do not chase). " +
        "Implication: exposure ceiling ${regime.suggestedExposurePct}%, new risk ${formatMult(regime.newRiskMultiplierBps)}×, " +
        "cash buffer ${regime.cashBufferPct}%.$confNote"
}

internal fun actionBullets(regime: MarketRegime, composite: CompositeOutput): List<String> {
    var bullets = ArrayList<String>()
    bullets.add(
        "Respect ${regime.suggestedExposurePct}% exposure ceiling for new risk (not a forced portfolio target).",
    )
    bullets.add(
        "Size multiplier ${formatMult(regime.newRiskMultiplierBps)}× versus your normal ATR/risk sizing.",
    )
    if (regime.cashBufferPct >= CASH_BUFFER_NOTE_PCT) {
        bullets.add("Keep ~${regime.cashBufferPct}% cash buffer until environment or quality improves.")
    }
    when (composite.actionStance) {
        "BloodInStreets", "Washout", "Accumulate", "SelectiveBuy" ->
            bullets.add(
                "Accumulate bias: prefer quality and scaled entries; fear is opportunity, not a reason for all-in.",
            )
        "Euphoria", "Distribute", "Reduce", "HoldTrim" ->
            bullets.add(
                "Reduce/no-chase bias: skip FOMO adds; take partial profits or demand exceptional setups.",
            )
        "Defend", "Denial" ->
            bullets.add(
                "Defend mode: prioritize capital preservation; new entries only with high edge and small size.",
            )
        "HealthyPullback", "Deploy", "TrendDeploy" ->
            bullets.add(
                "Environment to deploy: you can run the trend playbook while respecting stops and the risk ceiling.",
            )
    }
    if (regime.preferQuality) {
        bullets.add("Prefer balance-sheet quality / lower beta while stress or fragility lasts.")
    }
    var breadth = regime.breadthAboveMa200Pct
    if (breadth != null && breadth < WEAK_BREADTH_PCT && regime.spyAboveMa200 == true) {
        bullets.add("Weak breadth with SPY up: distrust index strength; demand per-name confirmation.")
    }
    return bullets
}

fun stanceEn(stance: String): String = when (stance) {
    "BloodInStreets" -> "blood in the streets"
    "Washout" -> "washout"
    "Accumulate" -> "accumulate"
    "SelectiveBuy" -> "selective buy"
    "HealthyPullback" -> "healthy pullback"
    "Deploy" -> "deploy"
    "TrendDeploy" -> "trend deploy"
    "Neutral" -> "neutral"
    "Hold" -> "hold"
    "HoldTrim" -> "hold / trim"
    "Reduce" -> "reduce"
    "Euphoria" -> "euphoria"
    "Distribute" -> "distribute"
    "Denial" -> "denial"
    "Defend" -> "defend"
    "UnstableBlowoff" -> "unstable blow-off"
    "Mixed" -> "mixed"
    else -> "unknown"
}

fun phaseEn(phase: String): String = when (phase) {
    "StrongBull" -> "strong bull"
    "Bull" -> "bull"
    "LateBull" -> "late bull"
    "Range" -> "range"
    "Correction" -> "correction"
    "Bear" -> "bear"
    "Capitulation" -> "capitulation"
    "Snapback" -> "snapback"
    else -> "unknown"
}

internal fun bandEn(band: String): String = when (band) {
    "StrongRiskOn" -> "strong risk-on"
    "RiskOn" -> "risk-on"
    "Neutral" -> "neutral"
    "RiskOff" -> "risk-off"
    "Crisis" -> "crisis"
    else -> "unknown"
}

fun phaseTitle(phase: String): String = when (phase) {
    "StrongBull" -> "Strong bull"
    "Bull" -> "Bull"
    "LateBull" -> "Late bull"
    "Range" -> "Range"
    "Correction" -> "Correction"
    "Bear" -> "Bear"
    "Capitulation" -> "Capitulation"
    "Snapback" -> "Snapback"
    else -> "Unknown"
}

fun stanceTitle(stance: String): String = when (stance) {
    "BloodInStreets" -> "Blood in the streets"
    "Washout" -> "Washout"
    "Accumulate" -> "Accumulate"
    "SelectiveBuy" -> "Selective buy"
    "HealthyPullback" -> "Healthy pullback"
    "Deploy" -> "Deploy"
    "TrendDeploy" -> "Trend deploy"
    "Neutral" -> "Neutral"
    "Hold" -> "Hold"
    "HoldTrim" -> "Hold / trim"
    "Reduce" -> "Reduce"
    "Euphoria" -> "Euphoria"
    "Distribute" -> "Distribute"
    "Denial" -> "Denial"
    "Defend" -> "Defend"
    "UnstableBlowoff" -> "Unstable blow-off"
    "Mixed" -> "Mixed"
    else -> "Unknown"
}

fun radarAxisLabel(id: String): String = when (id) {
    "trend" -> "Trend"
    "breadth" -> "Breadth"
    "volatility" -> "Calm"
    "sentiment" -> "F&G opp."
    "cross_asset" -> "Cross-asset"
    "quality" -> "Quality"
    else -> id
}

private fun enrichPillar(pillar: RegimePillar): RegimePillar {
    var signals = pillar.signals.map { signal ->
        if (abs(signal.contribution) < SIGNAL_HINT_MIN_ABS) {
            signal
        } else {
            signal.copy(hint = interpretSignal(signal.id, signal.contribution, signal.detail))
        }
    }
    return pillar.copy(
        interpretation = interpretPillar(pillar.id, pillar.score, pillar.confidenceBps, pillar.stale),
        signals = signals,
    )
}

private fun policyNotes(regime: MarketRegime, composite: CompositeOutput): List<String> {
    var notes = ArrayList<String>()
    if (composite.crisisCapApplied) notes.add("Crisis cap applied to exposure ceiling")
    if (composite.qualityHaircutApplied) notes.add("Haircut for market quality/fragility")
    if (regime.preferQuality) notes.add("Prefer quality / lower-beta names")
    return notes
}

private fun formatMult(bps: Int): String =
    String.format(Locale.US, "%.2f", bps / 10_000.0)

private fun scoreBandOf(score: Int): Int = when (scoreReading(score)) {
    ScoreReading.Strong -> 2
    ScoreReading.Good -> 1
    ScoreReading.Neutral -> 0
    ScoreReading.Weak -> -1
    ScoreReading.Poor -> -2
}

private fun trendCopy(band: Int): String = when (band) {
    2 -> "Solid trend: the index is stacked bullish; the tape supports long risk."
    1 -> "Moderately positive trend; mild upside bias without a clean breakout."
    0 -> "Mixed/sideways trend: no clear directional edge in the index."
    -1 -> "Weakening trend: the market fights aggressive long entries."
    else -> "Hostile trend: structural downside bias; prioritize defense and small size."
}

private fun breadthCopy(band: Int): String = when (band) {
    2 -> "Broad participation: many stocks join the move; the advance looks healthy."
    1 -> "Acceptable breadth: participation is present without an extreme thrust."
    0 -> "Mixed breadth: the index can mislead if you only watch SPY price."
    -1 -> "Weak breadth: the move is narrow; fragility sits under the surface."
    else -> "Collapsed breadth: few leaders; risk of false strength or broad panic."
}

private fun volCopy(band: Int): String = when (band) {
    2 -> "Extreme volatility stress: VIX/structure signal crisis; keep sizing defensive."
    1 -> "Elevated volatility: nervous tape; expect gaps and demand more margin of error."
    0 -> "Normal volatility zone: neither deep calm nor panic; VIX is not dominating the regime."
    -1 -> "Contained volatility: relatively calm tape; more room to deploy risk."
    else -> "Volatility calm: low stress (quiet VIX/contango); friendly for normal size."
}

private fun sentimentCopy(band: Int): String = when (band) {
    2 -> "Extreme fear (contrarian): historically favors selective accumulation — not all-in, but a disciplined quality buy bias."
    1 -> "Moderate fear: sentiment leaves room to add selectively; not euphoria yet."
    0 -> "Neutral sentiment: neither washout nor euphoria; F&G offers little contrarian edge."
    -1 -> "Rising greed: crowded tape; curb appetite for new risk adds."
    else -> "Euphoria / extreme greed (contrarian): historically a poor chase zone; bias to trim or skip FOMO adds."
}

private fun crossCopy(band: Int): String = when (band) {
    2 -> "Cross-asset risk-on: credit and cyclicals confirm risk appetite."
    1 -> "Mildly constructive cross-asset: risk preference without a clear excess."
    0 -> "Mixed cross-asset: equity, credit, and defensives disagree."
    -1 -> "Defensive rotation / soft credit: risk appetite cools outside spot equity."
    else -> "Cross-asset risk-off: credit stress or flight-to-quality confirms a hostile backdrop."
}

private fun qualityCopy(band: Int): String = when (band) {
    2 -> "High move quality: participation and structure do not scream fragility."
    1 -> "Acceptable quality: no major fragility red flags."
    0 -> "Neutral quality: the move is neither clearly healthy nor broken."
    -1 -> "Visible fragility: narrow rally, high correlation, or soft credit cut confidence."
    else -> "Fragile structure: poor internal quality; do not trust price alone."
}
