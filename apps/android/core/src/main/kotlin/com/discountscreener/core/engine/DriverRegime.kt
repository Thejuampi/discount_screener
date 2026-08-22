package com.discountscreener.core.engine

import kotlin.math.roundToInt

/**
 * What a run of annual revenue-growth rates says about the shape of the business.
 *
 * Lifted out of [DcfAnalysisEngine], where it was written, so the scoring bucket can ask the same
 * question the valuation already asks. Two detectors that both answer "is this cyclical?" would
 * drift apart, and the screen would then show a name the DCF treats as cyclical and the score does
 * not. Nothing about the classification changed in the move.
 */
internal enum class DriverRegime {
    SecularExpansion,
    StableOperating,
    CyclicalOrTransition,
    ;

    fun asString(): String = when (this) {
        SecularExpansion -> "secular_expansion"
        StableOperating -> "stable_operating"
        CyclicalOrTransition -> "cyclical_or_transition"
    }
}

/**
 * Both lists are annual growth rates in bps: [recentGrowths] the near window, [priorGrowths] the
 * window before it. [priorGrowths] may be empty; the near window may not.
 *
 * Dispersion is the interquartile spread of the near window. Twenty points of spread between the
 * quarter-best and quarter-worst year, or half the years negative, reads as a cycle. A run that is
 * both positive and steady, and no slower than the window before it, reads as expansion.
 */
internal fun classifyDriverRegime(recentGrowths: List<Int>, priorGrowths: List<Int>): DriverRegime {
    val recentMedian = medianBps(recentGrowths)
    val positiveShare = recentGrowths.count { it > 0 } * 10_000 / recentGrowths.size
    val dispersion = quantileBps(recentGrowths, 0.75) - quantileBps(recentGrowths, 0.25)
    val priorMedian = medianBps(priorGrowths)
    return when {
        recentMedian >= 500 && positiveShare >= 7_500 &&
            (priorGrowths.isEmpty() || recentMedian >= priorMedian) &&
            (dispersion <= 4_000 || recentMedian >= 1_000) ->
            DriverRegime.SecularExpansion
        dispersion >= 2_000 || positiveShare <= 5_000 -> DriverRegime.CyclicalOrTransition
        else -> DriverRegime.StableOperating
    }
}

/**
 * Plain median of a bps series, zero on an empty list.
 *
 * This is a bare middle element and not the robust centre the rest of the repository prefers. It
 * stays as written because every DCF golden in `shared/contracts` was produced by it; changing the
 * statistic here would move published valuations, which is its own change and not this one.
 */
internal fun medianBps(values: List<Int>): Int {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        ((sorted[middle - 1].toLong() + sorted[middle].toLong()) / 2L).toInt()
    } else {
        sorted[middle]
    }
}

/** Nearest-rank quantile of a bps series, zero on an empty list. */
internal fun quantileBps(values: List<Int>, quantile: Double): Int {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    val index = (((sorted.size - 1) * quantile).roundToInt())
        .coerceIn(0, sorted.lastIndex)
    return sorted[index]
}
