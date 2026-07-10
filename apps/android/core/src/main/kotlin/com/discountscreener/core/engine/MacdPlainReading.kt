package com.discountscreener.core.engine

import kotlin.math.abs
import kotlin.math.max

/**
 * Plain-language reading of MACD gap (histogram) plus its first and second derivatives.
 *
 * Uses continuous fuzzy scores (not hard zone tables) and returns a short
 * high-signal line: who has the push, how that push is changing, what to do.
 *
 * [scale] is a positive magnitude reference for the visible window (e.g. max abs of
 * macd / signal / histogram) so scores stay scale-free across cheap and expensive names.
 */
fun plainMacdReading(
    macd: Double?,
    histogram: Double?,
    histogramSlope: Double?,
    histogramAccel: Double?,
    scale: Double?,
): String {
    if (macd == null || histogram == null || histogramSlope == null || histogramAccel == null) {
        return "MACD needs more candles."
    }
    val safeScale = scale?.takeIf { it > 0.0 } ?: max(abs(macd), abs(histogram), 1.0)
    val scores = macdStoryScores(
        macd = macd,
        histogram = histogram,
        histogramSlope = histogramSlope,
        histogramAccel = histogramAccel,
        scale = safeScale,
    )
    return selectDominantMacdStory(scores).reading
}

/**
 * Last-bar slope and acceleration of the histogram series.
 * Slope = smoothed first difference; accel = smoothed second difference.
 */
fun macdHistogramDerivatives(histogram: List<Double>): Pair<Double?, Double?> {
    if (histogram.size < 2) return null to null
    val slopeSeries = smoothedDerivative(histogram)
    val accelSeries = smoothedDerivative(slopeSeries)
    return slopeSeries.lastOrNull() to accelSeries.lastOrNull()
}

/**
 * Positive magnitude reference for normalizing MACD readings.
 */
fun macdReadingScale(
    macdLine: List<Double>?,
    signalLine: List<Double>?,
    histogram: List<Double>?,
): Double? {
    var maxAbs = 0.0
    fun absorb(values: List<Double>?) {
        values?.forEach { value -> maxAbs = max(maxAbs, abs(value)) }
    }
    absorb(macdLine)
    absorb(signalLine)
    absorb(histogram)
    return maxAbs.takeIf { it > 0.0 }
}

private enum class MacdStory(val reading: String) {
    HotUpsideBuilding("Strong upside push and still building — late and hot. Don't chase."),
    BuyerControlBuilding("Buyers have the push. Edge still building. Ride with it; add only if the push holds."),
    BuyerEdgeFading("Buyers still ahead, but the push is fading. Watch only — no chase yet."),
    LateUpsideStall("Stretched high. Upside push is stalling. Look for reverse only after the stall fails."),
    SellerControlBuilding("Sellers have the push. Pressure building. Stay out."),
    SellerPressureEasing("Sellers still ahead, but pressure is easing. Watch the bounce; act only if buyers keep the edge."),
    LateDownsideStall("Washed out. Selling push is losing steam. Early — watch for a hold, don't size up yet."),
    DeepSelloffBuilding("Deep selloff and still getting worse. Avoid catching this knife."),
    Recovery("Weak stretch, buyers starting to take the push back. Watch the bounce; act only if the push holds."),
    Breakdown("Was firm; sellers are taking the push. Stay out until pressure eases."),
    FreshFlipUp("Just flipped to buyers. Early — watch for a hold, don't size up yet."),
    FreshFlipDown("Just flipped to sellers. Don't chase strength that just failed."),
    NeutralQuiet("No clear edge. Quiet. No push reason to act."),
}

private data class MacdStoryScore(
    val story: MacdStory,
    val score: Double,
    val priority: Int,
)

private fun macdStoryScores(
    macd: Double,
    histogram: Double,
    histogramSlope: Double,
    histogramAccel: Double,
    scale: Double,
): List<MacdStoryScore> {
    val histNorm = histogram / scale
    val macdNorm = macd / scale
    val slopeNorm = histogramSlope / scale
    val accelNorm = histogramAccel / scale

    val stance = signedRamp(histNorm, halfWidth = 0.35)
    val stretchUp = smooth01(macdNorm, low = 0.35, high = 0.9)
    val stretchDown = smooth01(-macdNorm, low = 0.35, high = 0.9)
    val stretch = max(stretchUp, stretchDown)

    val drift = signedRamp(slopeNorm, halfWidth = 0.15)
    val turn = signedRamp(accelNorm, halfWidth = 0.08)

    val buyerStance = clamp01(stance)
    val sellerStance = clamp01(-stance)
    val buyerDrift = clamp01(drift)
    val sellerDrift = clamp01(-drift)

    val building =
        if (abs(slopeNorm) < 0.02 && abs(accelNorm) < 0.01) {
            0.0
        } else if (histogramSlope * histogramAccel > 0.0) {
            clamp01(abs(turn) * 0.65 + abs(drift) * 0.35)
        } else {
            0.0
        }
    val exhausting =
        if (histogramSlope * histogramAccel < 0.0) {
            clamp01(abs(turn) * 0.7 + abs(drift) * 0.3)
        } else if (buyerStance > 0.25 && sellerDrift > 0.35) {
            // Positive gap but slope already against buyers counts as fade even without accel.
            clamp01(sellerDrift * 0.85)
        } else if (sellerStance > 0.25 && buyerDrift > 0.35) {
            clamp01(buyerDrift * 0.85)
        } else {
            0.0
        }

    val quiet =
        clamp01(1.0 - abs(stance) * 1.6) *
            clamp01(1.0 - abs(drift) * 1.6) *
            clamp01(1.0 - abs(turn) * 1.4) *
            clamp01(1.0 - stretch * 0.8)

    // Fresh flip: gap near zero with strong slope through zero.
    val nearZeroGap = clamp01(1.0 - abs(histNorm) / 0.12)
    val freshFlipUp = nearZeroGap * buyerDrift * (0.45 + clamp01(turn) * 0.55)
    val freshFlipDown = nearZeroGap * sellerDrift * (0.45 + clamp01(-turn) * 0.55)

    return listOf(
        MacdStoryScore(
            story = MacdStory.HotUpsideBuilding,
            score = stretchUp * buyerDrift * max(building, 0.35) * (0.55 + buyerStance * 0.45),
            priority = 3,
        ),
        MacdStoryScore(
            story = MacdStory.DeepSelloffBuilding,
            score = stretchDown * sellerDrift * max(building, 0.35) * (0.55 + sellerStance * 0.45),
            priority = 3,
        ),
        MacdStoryScore(
            story = MacdStory.LateUpsideStall,
            score = stretchUp * max(buyerStance, 0.2) * exhausting * (0.5 + stretch * 0.5),
            priority = 4,
        ),
        MacdStoryScore(
            story = MacdStory.LateDownsideStall,
            score = stretchDown * max(sellerStance, 0.2) * exhausting * (0.5 + stretch * 0.5),
            priority = 4,
        ),
        MacdStoryScore(
            story = MacdStory.BuyerControlBuilding,
            score = buyerStance * buyerDrift * max(building, 0.25) * (1.0 - stretchUp * 0.55),
            priority = 2,
        ),
        MacdStoryScore(
            story = MacdStory.SellerControlBuilding,
            score = sellerStance * sellerDrift * max(building, 0.25) * (1.0 - stretchDown * 0.45),
            priority = 2,
        ),
        MacdStoryScore(
            story = MacdStory.BuyerEdgeFading,
            score = buyerStance * exhausting * (0.45 + sellerDrift * 0.55) * (1.0 - stretchUp * 0.35),
            priority = 4,
        ),
        MacdStoryScore(
            story = MacdStory.SellerPressureEasing,
            score = sellerStance * exhausting * (0.45 + buyerDrift * 0.55) * (1.0 - stretchDown * 0.35),
            priority = 4,
        ),
        MacdStoryScore(
            story = MacdStory.Recovery,
            score = sellerStance * buyerDrift * (0.4 + clamp01(turn) * 0.6) * (0.55 + stretchDown * 0.45),
            priority = 4,
        ),
        MacdStoryScore(
            story = MacdStory.Breakdown,
            score = buyerStance * sellerDrift * (0.4 + clamp01(-turn) * 0.6) * (0.55 + buyerStance * 0.45),
            priority = 4,
        ),
        MacdStoryScore(
            story = MacdStory.FreshFlipUp,
            score = freshFlipUp,
            priority = 5,
        ),
        MacdStoryScore(
            story = MacdStory.FreshFlipDown,
            score = freshFlipDown,
            priority = 5,
        ),
        MacdStoryScore(
            story = MacdStory.NeutralQuiet,
            score = quiet * 0.9 + 0.05,
            priority = 1,
        ),
    )
}

private fun selectDominantMacdStory(scores: List<MacdStoryScore>): MacdStory {
    val best = scores.maxWithOrNull(
        compareBy<MacdStoryScore> { it.score }
            .thenBy { it.priority },
    ) ?: return MacdStory.NeutralQuiet

    val nearTop = scores
        .filter { candidate -> candidate.score >= best.score - 0.08 }
        .maxWithOrNull(compareBy<MacdStoryScore> { it.priority }.thenBy { it.score })
    return (nearTop ?: best).story
}

private fun smoothedDerivative(values: List<Double>): List<Double> {
    if (values.isEmpty()) return emptyList()
    val raw = MutableList(values.size) { 0.0 }
    for (index in 1 until values.size) {
        raw[index] = values[index] - values[index - 1]
    }
    return ema3(raw)
}

private fun ema3(values: List<Double>): List<Double> {
    if (values.isEmpty()) return emptyList()
    val multiplier = 2.0 / (3 + 1)
    val output = ArrayList<Double>(values.size)
    var previous = values.first()
    values.forEachIndexed { index, value ->
        previous = if (index == 0) value else ((value - previous) * multiplier) + previous
        output += previous
    }
    return output
}

private fun signedRamp(value: Double, halfWidth: Double): Double {
    if (halfWidth <= 0.0) return 0.0
    return (value / halfWidth).coerceIn(-1.0, 1.0)
}

private fun smooth01(value: Double, low: Double, high: Double): Double {
    if (high <= low) return if (value >= high) 1.0 else 0.0
    return ((value - low) / (high - low)).coerceIn(0.0, 1.0)
}

private fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)

private fun max(a: Double, b: Double, c: Double): Double = max(a, max(b, c))
