package com.discountscreener.core.earnings

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The surprise, measured in units of how far apart the analysts were.
 *
 * A textbook SUE divides the surprise by the standard deviation of the estimates. Yahoo never
 * publishes that number — it gives the low, the high and the count. So the dispersion used here is
 * half the spread of the estimates, fixed once and not moved: with estimates symmetric around the
 * mean it is the distance from the mean to either edge, which is the quantity a standard deviation
 * stands in for. A single analyst, or a panel that all said the same number, has no spread and the
 * score stays unreported rather than dividing by nothing.
 */
fun surpriseScoreBps(actualCents: Long?, pre: PreReport): Int? {
    var actual = actualCents ?: return null
    var consensus = pre.consensusEpsCents ?: return null
    var dispersion = dispersionCents(pre) ?: return null
    return ((actual - consensus).toDouble() / dispersion * 10_000.0).roundToInt()
}

fun revenueSurpriseBps(actualCents: Long?, pre: PreReport): Int? {
    var actual = actualCents ?: return null
    var consensus = pre.consensusRevenueCents?.takeIf { it > 0L } ?: return null
    return ((actual - consensus).toDouble() / consensus * 10_000.0).roundToInt()
}

private fun dispersionCents(pre: PreReport): Double? {
    var low = pre.consensusEpsLowCents ?: return null
    var high = pre.consensusEpsHighCents ?: return null
    return (abs(high - low) / 2.0).takeIf { it > 0.0 }
}
