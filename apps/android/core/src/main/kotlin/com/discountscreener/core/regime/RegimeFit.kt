package com.discountscreener.core.regime

import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.FundamentalSnapshot
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.tanh

/**
 * The fourth V3 scoring bucket: how well a name fits the active regime policy, −100..+100.
 * Ported from `regime/regime_fit.rs`.
 */

/** Typed cause factor. No user-facing copy lives here — the UI decides how to say it. */
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

enum class RegimeCauseEffect { Support, Risk, Neutral }

data class RegimeCause(
    val factor: RegimeCauseFactor,
    val effect: RegimeCauseEffect,
    /** Internal magnitude, for ranking only. Not shown to users. */
    val contributionBps: Int,
)

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
fun scoreRegimeFit(
    fundamentals: FundamentalSnapshot?,
    daily: ChartRangeSummary?,
    policy: RegimeScoringPolicy,
): RegimeFitResult {
    val features = SymbolFeatures.extract(fundamentals, daily)
    if (features.coverage < 2) return RegimeFitResult.insufficient()

    val parts = ArrayList<Triple<Double, Double, RegimeCauseFactor>>()

    features.quality?.let { quality ->
        parts.add(Triple(clamp((quality - 0.45) / 0.45, -1.0, 1.0), policy.wQuality, RegimeCauseFactor.Quality))
    }
    features.lowBeta?.let { lowBeta ->
        parts.add(Triple(clamp((lowBeta - 0.5) / 0.5, -1.0, 1.0), policy.wLowBeta, RegimeCauseFactor.LowBeta))
    }
    features.value?.let { value ->
        parts.add(Triple(clamp((value - 0.45) / 0.45, -1.0, 1.0), policy.wValue, RegimeCauseFactor.Value))
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
            parts.add(Triple(signed, policy.wOversoldQuality, RegimeCauseFactor.OversoldQual))
        }
    }

    features.extension?.let { extension ->
        parts.add(
            Triple(-clamp((extension - 0.45) / 0.45, -1.0, 1.0), policy.wAntiExtension, RegimeCauseFactor.Extension),
        )
    }
    features.trendAlign?.let { trend ->
        parts.add(Triple(clamp((trend - 0.5) / 0.5, -1.0, 1.0), policy.wTrend, RegimeCauseFactor.Trend))
    }
    if (features.defensiveSector) parts.add(Triple(0.8, policy.wDefensive, RegimeCauseFactor.Defensive))
    if (features.growthSector) parts.add(Triple(0.7, policy.wGrowth, RegimeCauseFactor.Growth))
    features.liquidity?.let { liquidity ->
        parts.add(Triple(clamp((liquidity - 0.4) / 0.5, -1.0, 1.0), policy.wLiquidity, RegimeCauseFactor.Liquidity))
    }

    var numerator = 0.0
    var denominator = 0.0
    val candidates = ArrayList<RegimeCause>()
    for ((signed, weight, factor) in parts) {
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
        fun extract(fundamentals: FundamentalSnapshot?, daily: ChartRangeSummary?): SymbolFeatures {
            val quality = qualityScore(fundamentals)
            val lowBeta = lowBetaScore(fundamentals?.betaMillis)
            val value = valueScore(fundamentals)
            val extension = extensionScore(daily)
            val oversold = oversoldScore(daily)
            val trendAlign = trendScore(daily)
            val sectors = sectorFlags(fundamentals?.sectorName)
            val liquidity = liquidityScore(fundamentals, daily)

            var coverage = 0
            if (quality != null) coverage += 1
            if (lowBeta != null) coverage += 1
            if (value != null) coverage += 1
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
