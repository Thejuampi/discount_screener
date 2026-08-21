package com.discountscreener.core.regime

import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.tanh
import kotlinx.serialization.Serializable

/**
 * The fourth V3 scoring bucket: how well a name fits the active regime policy, −100..+100.
 * Ported from `regime/regime_fit.rs`.
 */

/**
 * Why the market dimension is or is not part of a name's composite. Ported from
 * `commands.rs::RegimeScoreStatus`, and four-valued on purpose: "no fourth number" has four
 * different causes, and a user who turned the dimension off deserves a different sentence from one
 * whose phone cannot reach the market.
 */
@Serializable
enum class RegimeScoreStatus {
    /** Scored, and part of [com.discountscreener.core.model.OpportunityRow.compositeScore]. */
    Included,

    /** The user switched the dimension off. */
    Disabled,

    /** The market reading is missing or too weak to yield a policy, or this name has too little data. */
    Unavailable,

    /** This model or this asset never carries the dimension — V2, Legacy, Aggressive, ETFs, crypto. */
    NotApplicable,
}

/** Typed cause factor. No user-facing copy lives here — the UI decides how to say it. */
@Serializable
enum class RegimeCauseFactor(val legacyTag: String) {
    Quality("Quality"),
    LowBeta("LowBeta"),
    Value("Value"),
    OversoldQual("OversoldQual"),
    Extension("Extension"),
    Trend("Trend"),
    Defensive("Defensive"),
    Growth("Growth"),
    Liquidity("Liquidity"),
    GeneralFit("RegimeFit"),
    Neutral("RegimeNeutral"),
}

@Serializable
enum class RegimeCauseEffect { Support, Risk, Neutral }

@Serializable
data class RegimeCause(
    val factor: RegimeCauseFactor,
    val effect: RegimeCauseEffect,
    /** Internal magnitude, for ranking only. Not shown to users. */
    val contributionBps: Int,
)

@Serializable
enum class MarketContextUnavailableReason { MarketReadingUnavailable, InsufficientAssetData, Unknown }

data class RegimeFitResult(
    val score: Int? = null,
    val causes: List<RegimeCause> = emptyList(),
    /** Legacy string signals such as `+Quality`. */
    val signals: List<String> = emptyList(),
    val unavailableReason: MarketContextUnavailableReason? = null,
) {
    companion object {
        fun insufficient(): RegimeFitResult = RegimeFitResult(
            unavailableReason = MarketContextUnavailableReason.InsufficientAssetData,
        )
    }
}

private fun causeFromSigned(factor: RegimeCauseFactor, signed: Double, weight: Double) = RegimeCause(
    factor = factor,
    effect = when {
        signed > 0.0 -> RegimeCauseEffect.Support
        signed < 0.0 -> RegimeCauseEffect.Risk
        else -> RegimeCauseEffect.Neutral
    },
    contributionBps = roundHalfAwayFromZero(signed * weight * 10_000.0),
)

private fun legacySignal(cause: RegimeCause): String = when (cause.effect) {
    RegimeCauseEffect.Support -> "+${cause.factor.legacyTag}"
    // U+2212 MINUS SIGN, matching the Rust original rather than an ASCII hyphen.
    RegimeCauseEffect.Risk -> "−${cause.factor.legacyTag}"
    RegimeCauseEffect.Neutral -> cause.factor.legacyTag
}

/**
 * Score how well this name fits [policy].
 *
 * Refuses below two available features: one feature is not a fit, it is a coincidence, and the
 * L1-normalized mean would read as confident on the strength of a single input.
 */
/**
 * One term of the market fit, as it stands before the weighted mean absorbs it.
 *
 * [signed] is the feature mapped to -1..+1 and already carrying its own sign — the anti-extension
 * term is negated here, so a stretched price reads negative. [weight] is the regime policy's
 * weight for that factor, and it is stance-dependent: `wTrend` is 1.0 under Deploy and 0.2 under
 * Euphoria, while `wAntiExtension` runs the other way. Two terms can therefore read the same
 * observable with opposite signs and different weights, which is the arbitration the policy exists
 * to perform.
 */
data class RegimeFitTerm(
    val factor: RegimeCauseFactor,
    val signed: Double,
    val weight: Double,
)

/**
 * Every term the market fit is built from, unfiltered and unranked.
 *
 * [RegimeFitResult.causes] is not a substitute for this. It keeps at most three causes and only
 * those above a magnitude and weight cut, so a study that correlated it would be correlating what
 * survived a filter — it could not tell which term carries which sign, which is the one thing
 * such a study is for.
 *
 * Zero-weight terms are returned too. Which terms the active stance has switched off is itself the
 * evidence about that stance.
 *
 * Returns empty when coverage is below the [scoreRegimeFit] floor, so a caller cannot read terms
 * for a symbol the scorer refuses.
 */
fun regimeFitTerms(
    fundamentals: FundamentalSnapshot?,
    daily: ChartRangeSummary?,
    policy: RegimeScoringPolicy,
    featureSet: MarketFeatureSet = MarketFeatureSet.Full,
): List<RegimeFitTerm> {
    val features = SymbolFeatures.extract(fundamentals, daily, featureSet)
    if (features.coverage < 2) return emptyList()

    val parts = ArrayList<RegimeFitTerm>()

    if (featureSet.scoresQuality) {
        features.quality?.let { quality ->
            parts.add(term(RegimeCauseFactor.Quality, clamp((quality - 0.45) / 0.45, -1.0, 1.0), policy.wQuality))
        }
    }
    if (featureSet.scoresLowBeta) {
        features.lowBeta?.let { lowBeta ->
            parts.add(term(RegimeCauseFactor.LowBeta, clamp((lowBeta - 0.5) / 0.5, -1.0, 1.0), policy.wLowBeta))
        }
    }
    if (featureSet.scoresValue) {
        features.value?.let { value ->
            parts.add(term(RegimeCauseFactor.Value, clamp((value - 0.45) / 0.45, -1.0, 1.0), policy.wValue))
        }
    }

    val oversold = features.oversold
    val quality = features.quality
    if (oversold != null && quality != null) {
        // An oversold junk name gets nothing: the gate is what stops this reading as a dip to buy.
        val gate = when {
            quality >= 0.45 -> 1.0
            quality >= 0.30 -> 0.4
            else -> 0.0
        }
        if (gate > 0.0) {
            val signed = clamp((oversold * 2.0) - 1.0, -1.0, 1.0) * gate
            parts.add(term(RegimeCauseFactor.OversoldQual, signed, policy.wOversoldQuality))
        }
    }

    features.extension?.let { extension ->
        parts.add(
            term(
                RegimeCauseFactor.Extension,
                -clamp((extension - 0.45) / 0.45, -1.0, 1.0),
                policy.wAntiExtension,
            ),
        )
    }
    features.trendAlign?.let { trend ->
        parts.add(term(RegimeCauseFactor.Trend, clamp((trend - 0.5) / 0.5, -1.0, 1.0), policy.wTrend))
    }
    if (features.defensiveSector) parts.add(term(RegimeCauseFactor.Defensive, 0.8, policy.wDefensive))
    if (features.growthSector) parts.add(term(RegimeCauseFactor.Growth, 0.7, policy.wGrowth))
    features.liquidity?.let { liquidity ->
        parts.add(term(RegimeCauseFactor.Liquidity, clamp((liquidity - 0.4) / 0.5, -1.0, 1.0), policy.wLiquidity))
    }
    return parts
}

private fun term(factor: RegimeCauseFactor, signed: Double, weight: Double) =
    RegimeFitTerm(factor, signed, weight)

fun scoreRegimeFit(
    fundamentals: FundamentalSnapshot?,
    daily: ChartRangeSummary?,
    policy: RegimeScoringPolicy,
    featureSet: MarketFeatureSet = MarketFeatureSet.Full,
): RegimeFitResult {
    val parts = regimeFitTerms(fundamentals, daily, policy, featureSet)
    if (parts.isEmpty()) return RegimeFitResult.insufficient()

    var numerator = 0.0
    var denominator = 0.0
    val candidates = ArrayList<RegimeCause>()
    for ((factor, signed, weight) in parts) {
        if (weight <= 0.0) continue
        numerator += signed * weight
        denominator += weight
        if (abs(signed) >= 0.35 && weight >= 0.25) candidates.add(causeFromSigned(factor, signed, weight))
    }
    if (denominator <= 0.0) return RegimeFitResult.insufficient()

    val raw = (numerator / denominator) * policy.strength
    val score = clampI32(roundHalfAwayFromZero(tanh(raw) * 100.0), -100, 100)

    val ranked = candidates
        .sortedWith(compareByDescending<RegimeCause> { abs(it.contributionBps) }.thenBy { it.factor.legacyTag })
        .take(3)

    val causes = ranked.ifEmpty {
        listOf(
            when {
                score >= 15 -> RegimeCause(RegimeCauseFactor.GeneralFit, RegimeCauseEffect.Support, score * 100)
                score <= -15 -> RegimeCause(RegimeCauseFactor.GeneralFit, RegimeCauseEffect.Risk, score * 100)
                else -> RegimeCause(RegimeCauseFactor.Neutral, RegimeCauseEffect.Neutral, 0)
            },
        )
    }

    return RegimeFitResult(
        score = score,
        causes = causes,
        signals = causes.map(::legacySignal),
        unavailableReason = null,
    )
}

/**
 * Which market terms a model scores.
 *
 * V3 scores every one, and three of them repeat a fact another bucket already holds. The overlap
 * was measured on 498 live rows, per term, and the boundary that decides each case is whether the
 * regime policy can flip the term's sign:
 *
 *  * a term whose weight can invert what it says, by stance, is an **arbitration** and stays —
 *    `wTrend` runs 0.1 in BloodInStreets to 1.0 in Deploy while `wAntiExtension` runs the other
 *    way, so the trend pair reads one stretched chart in opposition and the stance decides;
 *  * a term whose sign is fixed in every stance and is already scored elsewhere is a **duplicate**
 *    and goes.
 *
 * [NonOverlapping] is the second rule applied. Each term it drops was removed in its own commit,
 * carrying the correlation that justified it, so that a later comparison of V3 against V4 can say
 * which removal moved the answer.
 */
enum class MarketFeatureSet(
    internal val scoresQuality: Boolean,
    internal val scoresValue: Boolean,
    internal val scoresLowBeta: Boolean,
) {
    /** Every term. V3's set, and the control the journal compares against. */
    Full(scoresQuality = true, scoresValue = true, scoresLowBeta = true),

    /** V4's set: the arbitrations, without the terms another bucket already scores. */
    NonOverlapping(scoresQuality = false, scoresValue = false, scoresLowBeta = false),
}

/**
 * Which set a model scores. A `when`, so the compiler names this place when a fifth model arrives.
 *
 * It lives here rather than beside the model enum because the answer is a regime concept, and the
 * model package should not have to know what a market term is.
 */
fun OpportunityScoringModel.marketFeatureSet(): MarketFeatureSet = when (this) {
    OpportunityScoringModel.Legacy,
    OpportunityScoringModel.Aggressive,
    OpportunityScoringModel.AggressiveV2,
    OpportunityScoringModel.AggressiveV3,
    -> MarketFeatureSet.Full
    OpportunityScoringModel.AggressiveV4,
    OpportunityScoringModel.AggressiveV5,
    -> MarketFeatureSet.NonOverlapping
}

private data class SymbolFeatures(
    val quality: Double?,
    val lowBeta: Double?,
    val value: Double?,
    val extension: Double?,
    val oversold: Double?,
    val trendAlign: Double?,
    val defensiveSector: Boolean,
    val growthSector: Boolean,
    val liquidity: Double?,
    val coverage: Int,
) {
    companion object {
        /**
         * Coverage counts the features this set can turn into a term, and nothing else.
         *
         * A floor counted over features the model never scores measures the wrong population: a
         * symbol could clear it on three facts V4 ignores and then produce no term at all. Quality
         * is the one that stays computed after it stops being scored, because the oversold term is
         * gated on it — an oversold junk name is not a dip to buy.
         */
        fun extract(
            fundamentals: FundamentalSnapshot?,
            daily: ChartRangeSummary?,
            featureSet: MarketFeatureSet,
        ): SymbolFeatures {
            val quality = qualityScore(fundamentals)
            val lowBeta = lowBetaScore(fundamentals?.betaMillis)
            val value = valueScore(fundamentals)
            val extension = extensionScore(daily)
            val oversold = oversoldScore(daily)
            val trendAlign = trendScore(daily)
            val sectors = sectorFlags(fundamentals?.sectorName)
            val liquidity = liquidityScore(fundamentals, daily)

            var coverage = 0
            if (featureSet.scoresQuality && quality != null) coverage += 1
            if (featureSet.scoresLowBeta && lowBeta != null) coverage += 1
            if (featureSet.scoresValue && value != null) coverage += 1
            if (extension != null) coverage += 1
            if (oversold != null) coverage += 1
            if (trendAlign != null) coverage += 1
            if (sectors.first || sectors.second) coverage += 1
            if (liquidity != null) coverage += 1

            return SymbolFeatures(
                quality = quality,
                lowBeta = lowBeta,
                value = value,
                extension = extension,
                oversold = oversold,
                trendAlign = trendAlign,
                defensiveSector = sectors.first,
                growthSector = sectors.second,
                liquidity = liquidity,
                coverage = coverage,
            )
        }
    }
}

private fun ramp(observed: Double, lower: Double, upper: Double): Double =
    OpportunityEngine.smoothRamp(observed, lower, upper)

/** A ramp mapped from −1..+1 onto 0..1. */
private fun rampUnit(observed: Double, lower: Double, upper: Double): Double =
    clamp((ramp(observed, lower, upper) + 1.0) / 2.0, 0.0, 1.0)

private fun qualityScore(fundamentals: FundamentalSnapshot?): Double? {
    val f = fundamentals ?: return null
    var accumulator = 0.0
    var weight = 0.0

    val fcf = f.freeCashFlowDollars
    val marketCap = f.marketCapDollars
    if (fcf != null && marketCap != null && marketCap > 0L) {
        accumulator += rampUnit(fcf.toDouble() / marketCap.toDouble(), -0.02, 0.08)
        weight += 1.0
    } else if (fcf != null) {
        accumulator += if (fcf > 0L) 0.7 else 0.2
        weight += 0.7
    } else {
        f.operatingCashFlowDollars?.let { ocf ->
            accumulator += if (ocf > 0L) 0.55 else 0.25
            weight += 0.5
        }
    }

    val debtToEquity = f.debtToEquityHundredths
    val cash = f.totalCashDollars
    val debt = f.totalDebtDollars
    if (debtToEquity != null) {
        accumulator += clamp((-ramp(debtToEquity.toDouble(), 30.0, 200.0) + 1.0) / 2.0, 0.0, 1.0)
        weight += 1.0
    } else if (cash != null && debt != null) {
        accumulator += if (cash >= debt) 0.75 else 0.35
        weight += 0.7
    }

    f.returnOnEquityBps?.let { roe ->
        accumulator += rampUnit(roe.toDouble(), 0.0, 2000.0)
        weight += 0.8
    }

    val operating = f.operatingCashFlowDollars
    if (fcf != null && operating != null && operating > 0L) {
        accumulator += clamp(clamp(fcf.toDouble() / operating.toDouble(), 0.0, 1.5) / 1.5, 0.0, 1.0)
        weight += 0.6
    }

    return if (weight <= 0.0) null else clamp(accumulator / weight, 0.0, 1.0)
}

private fun lowBetaScore(betaMillis: Int?): Double? {
    val beta = betaMillis ?: return null
    return clamp((-ramp(beta.toDouble(), 700.0, 1600.0) + 1.0) / 2.0, 0.0, 1.0)
}

/**
 * Cheapness across whichever multiples are present.
 *
 * A plain mean over at most three ramps that are already clamped to 0..1, matching
 * `regime_fit.rs::value_score`. Not a naked average of raw quantities — each term is a bounded
 * score — and the two platforms have to produce the same integer.
 */
private fun valueScore(fundamentals: FundamentalSnapshot?): Double? {
    val f = fundamentals ?: return null
    val values = ArrayList<Double>()
    f.forwardPeHundredths?.takeIf { it > 0 }?.let { pe ->
        values.add(clamp((-ramp(pe.toDouble(), 800.0, 3500.0) + 1.0) / 2.0, 0.0, 1.0))
    }
    f.enterpriseToEbitdaHundredths?.takeIf { it > 0 }?.let { ev ->
        values.add(clamp((-ramp(ev.toDouble(), 600.0, 2000.0) + 1.0) / 2.0, 0.0, 1.0))
    }
    f.priceToBookHundredths?.takeIf { it > 0 }?.let { pb ->
        values.add(clamp((-ramp(pb.toDouble(), 100.0, 500.0) + 1.0) / 2.0, 0.0, 1.0))
    }
    return if (values.isEmpty()) null else values.sum() / values.size.toDouble()
}

private fun extensionScore(daily: ChartRangeSummary?): Double? {
    val d = daily ?: return null
    var accumulator = 0.0
    var weight = 0.0
    d.pos52wPct?.let { position ->
        accumulator += clamp(position / 100.0, 0.0, 1.0)
        weight += 1.0
    }
    d.latestWilderRsi?.let { rsi ->
        accumulator += clamp((rsi - 30.0) / 50.0, 0.0, 1.0)
        weight += 1.0
    }
    val price = d.latestCloseCents ?: 0L
    val ema50 = d.ema50Cents
    if (price > 0L && ema50 != null && ema50 > 0L) {
        val distance = (price - ema50).toDouble() / ema50.toDouble()
        accumulator += clamp((distance + 0.05) / 0.20, 0.0, 1.0)
        weight += 0.8
    }
    return if (weight <= 0.0) null else clamp(accumulator / weight, 0.0, 1.0)
}

private fun oversoldScore(daily: ChartRangeSummary?): Double? {
    val d = daily ?: return null
    var accumulator = 0.0
    var weight = 0.0
    d.latestWilderRsi?.let { rsi ->
        accumulator += clamp(1.0 - ((rsi - 25.0) / 30.0), 0.0, 1.0)
        weight += 1.0
    }
    d.pos52wPct?.let { position ->
        accumulator += clamp(1.0 - (position / 100.0), 0.0, 1.0)
        weight += 1.0
    }
    d.bbPercentB?.let { percentB ->
        accumulator += clamp(1.0 - percentB, 0.0, 1.0)
        weight += 0.7
    }
    return if (weight <= 0.0) null else clamp(accumulator / weight, 0.0, 1.0)
}

private fun trendScore(daily: ChartRangeSummary?): Double? {
    val d = daily ?: return null
    val price = d.latestCloseCents ?: return null
    if (price <= 0L) return null
    var score = 0.5
    var used = false
    d.ema20Cents?.let { ema ->
        used = true
        score += if (price > ema) 0.15 else -0.15
    }
    d.ema50Cents?.let { ema ->
        used = true
        score += if (price > ema) 0.15 else -0.15
    }
    d.ema200Cents?.let { ema ->
        used = true
        score += if (price > ema) 0.2 else -0.2
    }
    val ema50 = d.ema50Cents
    val ema200 = d.ema200Cents
    if (ema50 != null && ema200 != null) score += if (ema50 > ema200) 0.1 else -0.1
    return if (!used) null else clamp(score, 0.0, 1.0)
}

private fun sectorFlags(sector: String?): Pair<Boolean, Boolean> {
    val name = sector.orEmpty().lowercase()
    val defensive = listOf(
        "utilities", "consumer defensive", "consumer staples", "healthcare", "health care", "real estate",
    ).any { name.contains(it) }
    val growth = listOf(
        "technology", "communication", "consumer cyclical", "consumer discretionary", "semiconductor",
    ).any { name.contains(it) }
    return defensive to growth
}

/**
 * Size and turnover.
 *
 * The volume term reads [ChartRangeSummary.volumeRatioHundredths], where 100 means "at the series
 * median". The Rust original applied `((vr - 50.0) / 100.0)` to a *raw* ratio — a formula written
 * against this hundredths convention but fed a number near 1.0, so it clamped to zero for every
 * realistic input while still charging 0.5 to the denominator. That made the volume half of this
 * feature dead weight on Windows: present in the divisor, absent from the sum.
 *
 * Both platforms now centre on the median instead, which is the neutral the original formula was
 * reaching for: at the median the term contributes 0.5, above it more, below it less.
 */
private fun liquidityScore(fundamentals: FundamentalSnapshot?, daily: ChartRangeSummary?): Double? {
    var accumulator = 0.0
    var weight = 0.0
    fundamentals?.marketCapDollars?.takeIf { it > 0L }?.let { marketCap ->
        accumulator += rampUnit(ln(marketCap.toDouble()), ln(2e9), ln(50e9))
        weight += 1.0
    }
    daily?.volumeRatioHundredths?.let { hundredths ->
        accumulator += clamp((hundredths.toDouble() / 100.0) - 0.5, 0.0, 1.0)
        weight += 0.5
    }
    return if (weight <= 0.0) null else clamp(accumulator / weight, 0.0, 1.0)
}
