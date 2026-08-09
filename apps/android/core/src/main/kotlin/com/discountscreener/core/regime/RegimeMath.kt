package com.discountscreener.core.regime

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Pure math helpers for the regime engine, ported from `regime/math.rs`.
 *
 * Every function here has a Rust counterpart that must agree with it to the integer. Where the two
 * languages differ in a primitive — rounding, integer casts — the difference is spelled out at the
 * function that absorbs it rather than left to chance.
 */

/**
 * Rust's `f64::round` rounds halves *away from zero*; Kotlin's [Double.roundToInt] rounds them
 * *up*. They disagree on exactly the negative halves: `-3.5` is `-4` in Rust and `-3` in Kotlin.
 *
 * Pillar scores are signed and land on halves often enough that this is not theoretical, so every
 * ported function rounds through here. Do not substitute `roundToInt`.
 */
internal fun roundHalfAwayFromZero(value: Double): Int =
    if (value < 0.0) -floor(-value + 0.5).toInt() else floor(value + 0.5).toInt()

/** Rust's `as u32` on an `f64`: truncate toward zero, saturating at the bounds. */
internal fun truncateToU32(value: Double): Int = when {
    value.isNaN() -> 0
    value <= 0.0 -> 0
    value >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
    else -> value.toInt()
}

internal fun clamp(value: Double, low: Double, high: Double): Double = value.coerceIn(low, high)

internal fun clampI32(value: Int, low: Int, high: Int): Int = value.coerceIn(low, high)

/** Linear map of [x] from `[x0, x1]` into `[y0, y1]`, clamped at both ends. */
internal fun linmap(x: Double, x0: Double, x1: Double, y0: Double, y1: Double): Double {
    if (abs(x1 - x0) < 1e-12) return y0
    val t = clamp((x - x0) / (x1 - x0), 0.0, 1.0)
    return y0 + (t * (y1 - y0))
}

/** Logistic exposure ceiling: E≈−100 → ~15%, E≈0 → ~57%, E≈+100 → ~100%. */
internal fun logisticExposurePct(environmentScore: Int): Double {
    val x = environmentScore.toDouble() / 35.0
    return 15.0 + (85.0 / (1.0 + exp(-x)))
}

/** Round to the nearest [step] (e.g. 5), floored at zero. */
internal fun roundToStep(value: Double, step: Double): Int {
    if (step <= 0.0) return truncateToU32(roundHalfAwayFromZero(value).toDouble())
    return truncateToU32((roundHalfAwayFromZero(value / step) * step).coerceAtLeast(0.0))
}

/**
 * Percentile of [value] within a [sorted] sample, 0..100, counting ties at half rank.
 *
 * The Rust original breaks out of both scans on the first non-matching element, which is correct
 * only because the input is sorted — the same assumption is made here, and the parameter name is
 * the only thing enforcing it on either side.
 */
internal fun percentileOf(sorted: List<Double>, value: Double): Double? {
    if (sorted.isEmpty()) return null
    var below = 0
    for (sample in sorted) {
        if (sample < value) below += 1 else break
    }
    var equal = 0
    for (sample in sorted.drop(below)) {
        if (abs(sample - value) < 1e-9) equal += 1 else break
    }
    val rank = below.toDouble() + (equal.toDouble() * 0.5)
    return clamp(rank / sorted.size.toDouble() * 100.0, 0.0, 100.0)
}

/** Simple returns from a close series, skipping any non-positive base. */
internal fun simpleReturns(closes: List<Double>): List<Double> {
    val out = ArrayList<Double>(maxOf(closes.size - 1, 0))
    for (index in 1 until closes.size) {
        val previous = closes[index - 1]
        if (previous > 0.0) out.add((closes[index] - previous) / previous)
    }
    return out
}

/** Annualized realized vol from daily simple returns, as a percent. */
internal fun realizedVolPct(returns: List<Double>): Double? {
    if (returns.size < 5) return null
    val n = returns.size.toDouble()
    val mean = returns.sum() / n
    val variance = returns.sumOf { value -> (value - mean) * (value - mean) } / (n - 1.0)
    if (variance < 0.0) return null
    return sqrt(variance) * sqrt(252.0) * 100.0
}

/** Relative performance of [a] against [b] over the last [lookback] closes, in percent. */
internal fun relativeReturnPct(a: List<Double>, b: List<Double>, lookback: Int): Double? {
    if (a.size < lookback + 1 || b.size < lookback + 1) return null
    val a0 = a[a.size - 1 - lookback]
    val a1 = a.last()
    val b0 = b[b.size - 1 - lookback]
    val b1 = b.last()
    if (a0 <= 0.0 || b0 <= 0.0) return null
    return (((a1 - a0) / a0) - ((b1 - b0) / b0)) * 100.0
}

/** Map a relative return percent into −100..+100 with a soft cap. */
internal fun relReturnToScore(relPct: Double, softCap: Double): Int {
    val cap = softCap.coerceAtLeast(0.1)
    return roundHalfAwayFromZero(clamp(relPct / cap, -1.0, 1.0) * 100.0)
}

/** Contrarian map: Fear & Greed 0 → +100, 50 → 0, 100 → −100. */
internal fun fngToContrarianScore(fng: Double): Int =
    roundHalfAwayFromZero(100.0 - (2.0 * clamp(fng, 0.0, 100.0)))

internal fun fngZone(score: Double): String = when {
    score <= 24.0 -> "ExtremeFear"
    score <= 44.0 -> "Fear"
    score <= 55.0 -> "Neutral"
    score <= 75.0 -> "Greed"
    else -> "ExtremeGreed"
}

internal fun fngLabelFromZone(zone: String): String = when (zone) {
    "ExtremeFear" -> "Extreme Fear"
    "Fear" -> "Fear"
    "Neutral" -> "Neutral"
    "Greed" -> "Greed"
    "ExtremeGreed" -> "Extreme Greed"
    else -> "Unknown"
}

/** VIX state from the absolute level, the fallback when no percentile is available. */
internal fun vixState(vix: Double): String = when {
    vix < 15.0 -> "Calm"
    vix < 20.0 -> "Normal"
    vix < 28.0 -> "Elevated"
    vix < 40.0 -> "Fear"
    else -> "Crisis"
}

/** Stress from a VIX percentile (0..100), roughly −40..+100. */
internal fun stressFromVixPercentile(percentile: Double): Double = when {
    percentile < 20.0 -> linmap(percentile, 0.0, 20.0, -40.0, 0.0)
    percentile < 50.0 -> linmap(percentile, 20.0, 50.0, 0.0, 10.0)
    percentile < 80.0 -> linmap(percentile, 50.0, 80.0, 10.0, 40.0)
    percentile < 95.0 -> linmap(percentile, 80.0, 95.0, 40.0, 75.0)
    else -> linmap(percentile, 95.0, 100.0, 75.0, 100.0)
}

/** Fallback stress from the absolute VIX level. */
internal fun stressFromVixLevel(vix: Double): Double = when {
    vix < 12.0 -> linmap(vix, 8.0, 12.0, -40.0, -20.0)
    vix < 18.0 -> linmap(vix, 12.0, 18.0, -20.0, 0.0)
    vix < 26.0 -> linmap(vix, 18.0, 26.0, 0.0, 35.0)
    vix < 40.0 -> linmap(vix, 26.0, 40.0, 35.0, 75.0)
    else -> linmap(vix, 40.0, 80.0, 75.0, 100.0)
}

/** Term-structure contribution, where the ratio is VIX / VIX3M. */
internal fun termStructureStress(ratio: Double): Double = when {
    ratio < 0.90 -> -15.0
    ratio < 1.00 -> -5.0
    ratio < 1.10 -> 15.0
    else -> 35.0
}

/** Weighted mean of (score, weight) pairs. Null when no pair carries positive weight. */
internal fun weightedMeanI32(parts: List<Pair<Int, Double>>): Int? {
    var numerator = 0.0
    var denominator = 0.0
    for ((score, weight) in parts) {
        if (weight > 0.0) {
            numerator += score.toDouble() * weight
            denominator += weight
        }
    }
    if (denominator <= 0.0) return null
    return roundHalfAwayFromZero(numerator / denominator)
}

/** Only move off [previous] when the change clears [deadband]. */
internal fun hysteresisU32(previous: Int?, next: Int, deadband: Int): Int = when {
    previous == null -> next
    abs(previous - next) <= deadband -> previous
    else -> next
}
