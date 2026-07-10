package com.discountscreener.core.engine

import kotlin.math.abs
import kotlin.math.max

/**
 * Plain-language reading of RSI level plus its first and second derivatives.
 *
 * Uses continuous fuzzy scores (not hard zone tables) and returns a short
 * high-signal line: who has the edge, how that edge is changing, what to do.
 */
fun plainRsiReading(
    level: Double?,
    slope: Double?,
    acceleration: Double?,
): String {
    if (level == null || slope == null || acceleration == null) {
        return "RSI needs more candles."
    }

    val scores = rsiStoryScores(level = level, slope = slope, acceleration = acceleration)
    val story = selectDominantStory(scores)
    return story.reading
}

private enum class RsiStory(val reading: String) {
    HotUpsideBuilding("Strong run and still building — late and hot. Don't chase."),
    BuyerControlBuilding("Buyers in control. Edge still building. Ride with it; buy weakness only if it holds."),
    BuyerEdgeFading("Buyers still ahead, but the edge is fading. Watch only — no chase yet."),
    LateUpsideStall("Stretched high. Push is stalling. Look for reverse only after it breaks down from the stall."),
    SellerControlBuilding("Sellers in charge. Pressure building. Stay out."),
    SellerPressureEasing("Sellers still ahead, but pressure is easing. Watch the bounce; act only if buyers keep the edge."),
    LateDownsideStall("Washed out. Selling is losing steam. Early — watch for a hold, don't size up yet."),
    DeepSelloffBuilding("Deep selloff and still getting worse. Avoid catching this knife."),
    Recovery("Weak stretch, buyers starting to take back ground. Watch the bounce; act only if buyers keep the edge."),
    Breakdown("Was firm; sellers are taking the edge. Stay out until pressure eases."),
    EarlyLift("Quiet stretch. Early lift forming. Watch only — no chase yet."),
    EarlySlip("Quiet stretch. Early slip forming. Don't chase strength that just failed."),
    NeutralQuiet("No clear edge. Quiet. No RSI reason to act."),
}

private data class StoryScore(
    val story: RsiStory,
    val score: Double,
    /** Prefer turn/fade stories when scores are close. */
    val priority: Int,
)

private fun rsiStoryScores(
    level: Double,
    slope: Double,
    acceleration: Double,
): List<StoryScore> {
    val stance = signedRamp(level - 50.0, halfWidth = 30.0)
    val stretchUp = smooth01(level, low = 65.0, high = 88.0)
    val stretchDown = smooth01(35.0 - level, low = 0.0, high = 25.0)
    val stretch = max(stretchUp, stretchDown)

    val drift = signedRamp(slope, halfWidth = 2.0)
    val turn = signedRamp(acceleration, halfWidth = 0.8)

    val buyerStance = clamp01(stance)
    val sellerStance = clamp01(-stance)
    val buyerDrift = clamp01(drift)
    val sellerDrift = clamp01(-drift)

    val building =
        if (abs(slope) < 0.08 && abs(acceleration) < 0.04) {
            0.0
        } else if (slope * acceleration > 0.0) {
            clamp01(abs(turn) * 0.65 + abs(drift) * 0.35)
        } else {
            0.0
        }
    val exhausting =
        if (slope * acceleration < 0.0) {
            clamp01(abs(turn) * 0.7 + abs(drift) * 0.3)
        } else {
            0.0
        }
    val quiet =
        clamp01(1.0 - abs(stance) * 1.4) *
            clamp01(1.0 - abs(drift) * 1.6) *
            clamp01(1.0 - abs(turn) * 1.4)

    val earlyTurnUp = quiet * clamp01(turn) * clamp01(1.0 - abs(stance))
    val earlyTurnDown = quiet * clamp01(-turn) * clamp01(1.0 - abs(stance))

    return listOf(
        StoryScore(
            story = RsiStory.HotUpsideBuilding,
            score = stretchUp * buyerDrift * max(building, 0.35) * (0.55 + buyerStance * 0.45),
            priority = 3,
        ),
        StoryScore(
            story = RsiStory.DeepSelloffBuilding,
            score = stretchDown * sellerDrift * max(building, 0.35) * (0.55 + sellerStance * 0.45),
            priority = 3,
        ),
        StoryScore(
            story = RsiStory.LateUpsideStall,
            score = stretchUp * max(buyerDrift, 0.25) * exhausting * (0.5 + stretch * 0.5),
            priority = 4,
        ),
        StoryScore(
            story = RsiStory.LateDownsideStall,
            score = stretchDown * max(sellerDrift, 0.2) * exhausting * (0.5 + stretch * 0.5),
            priority = 4,
        ),
        StoryScore(
            story = RsiStory.BuyerControlBuilding,
            score = buyerStance * buyerDrift * max(building, 0.25) * (1.0 - stretchUp * 0.55),
            priority = 2,
        ),
        StoryScore(
            story = RsiStory.SellerControlBuilding,
            score = sellerStance * sellerDrift * max(building, 0.25) * (1.0 - stretchDown * 0.45),
            priority = 2,
        ),
        StoryScore(
            story = RsiStory.BuyerEdgeFading,
            score = buyerStance * exhausting * (0.45 + clamp01(-drift) * 0.55) * (1.0 - stretchUp * 0.35),
            priority = 4,
        ),
        StoryScore(
            story = RsiStory.SellerPressureEasing,
            score = sellerStance * exhausting * (0.45 + clamp01(drift) * 0.55) * (1.0 - stretchDown * 0.35),
            priority = 4,
        ),
        StoryScore(
            story = RsiStory.Recovery,
            score = sellerStance * buyerDrift * (0.4 + clamp01(turn) * 0.6) * (0.55 + stretchDown * 0.45),
            priority = 4,
        ),
        StoryScore(
            story = RsiStory.Breakdown,
            score = buyerStance * sellerDrift * (0.4 + clamp01(-turn) * 0.6) * (0.55 + clamp01(stance) * 0.45),
            priority = 4,
        ),
        StoryScore(
            story = RsiStory.EarlyLift,
            score = earlyTurnUp,
            priority = 5,
        ),
        StoryScore(
            story = RsiStory.EarlySlip,
            score = earlyTurnDown,
            priority = 5,
        ),
        StoryScore(
            story = RsiStory.NeutralQuiet,
            score = quiet * 0.9 + 0.05,
            priority = 1,
        ),
    )
}

private fun selectDominantStory(scores: List<StoryScore>): RsiStory {
    val best = scores.maxWithOrNull(
        compareBy<StoryScore> { it.score }
            .thenBy { it.priority },
    ) ?: return RsiStory.NeutralQuiet

    // Prefer higher-priority turn/fade stories when nearly tied with static control.
    val nearTop = scores
        .filter { candidate -> candidate.score >= best.score - 0.08 }
        .maxWithOrNull(compareBy<StoryScore> { it.priority }.thenBy { it.score })
    return (nearTop ?: best).story
}

/** Maps value into [-1, 1] with soft shoulders around ±halfWidth. */
private fun signedRamp(value: Double, halfWidth: Double): Double {
    if (halfWidth <= 0.0) return 0.0
    return (value / halfWidth).coerceIn(-1.0, 1.0)
}

/** Linear membership rising from [low, high] into [0, 1]. */
private fun smooth01(value: Double, low: Double, high: Double): Double {
    if (high <= low) return if (value >= high) 1.0 else 0.0
    return ((value - low) / (high - low)).coerceIn(0.0, 1.0)
}

private fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)
