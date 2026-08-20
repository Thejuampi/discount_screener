package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries

/**
 * Whether a name's latest earnings look like a cycle peak, and what that is worth in points.
 *
 * The question this answers is narrow: an oil producer earning a record margin in year five of five
 * is not five times the business it was in year one, and every term that extrapolates the latest
 * level says it is. The penalty marks that, and only that.
 *
 * **What it is not.** This is not a cycle-adjusted earnings figure. A mid-cycle margin needs seven
 * to ten years of an operating line, and this repository has neither: [FundamentalTimeseries] holds
 * five annual points at most and carries no operating income at all. Five points cannot place a
 * commodity cycle. They can say where the latest year sits inside the only history there is, which
 * is a weaker statement and is the one made here.
 */
internal data class CyclePeakReading(
    /** Points to subtract from the fundamentals bucket. Zero when nothing fired. */
    val penaltyPoints: Int,
    /** Where the latest net margin sits inside its own window, 0 lowest and 10 000 highest. */
    val marginPercentileBps: Int?,
    /** Null when the revenue history was too short to classify. */
    val regime: DriverRegime?,
    val throughCycleIndustry: Boolean,
)

/**
 * At or under the middle of its own window, a margin says nothing about a peak and costs nothing.
 * The best year of the window costs the whole penalty.
 */
internal const val V4_CYCLE_PEAK_LOWER_BPS = 5_000.0
internal const val V4_CYCLE_PEAK_UPPER_BPS = 10_000.0

/**
 * Eight points of the hundred-and-sixteen the bucket can award. Sized to move a name a rank or two,
 * never to decide it: the reading is an approximation over five points and must not outweigh a
 * measured term.
 */
internal const val V4_CYCLE_PEAK_MAX_PENALTY_POINTS = 8

/** Three margins is the floor. Two points have no middle and every window would read as a peak. */
internal const val V4_CYCLE_PEAK_MIN_YEARS = 3

internal const val CYCLE_PEAK_LABEL = "CyclePeak"

/**
 * Both conditions must hold: the industry is one the beta policy already calls through-cycle, and
 * the company's own revenue run reads as [DriverRegime.CyclicalOrTransition].
 *
 * The industry list alone would penalise a chemicals company whose revenue has been flat for five
 * years, where the peak reading is an artefact of a short window. The regime alone would penalise
 * any business having a good year after a bad one. Neither is the claim being made.
 *
 * The industry is matched by `industryKey` first. Semiconductors sit under the `technology` sector,
 * so a sector-keyed lookup would ask the wrong table — and, as the policy stands today, would give
 * the wrong answer as well: only `oil_gas_ep`, `oil_gas_integrated` and `specialty_chemicals` carry
 * `throughCycle`. Semiconductors, `energy_sector` and `basic_materials` do not. That flag also sets
 * the cost-of-equity shrink weights, so it is not moved from here.
 */
internal fun cyclePeakReading(
    fundamentals: FundamentalSnapshot,
    timeseries: FundamentalTimeseries?,
    maxYears: Int,
): CyclePeakReading {
    var prior = resolveIndustryBetaPrior(
        sectorName = fundamentals.sectorName,
        industryName = fundamentals.industryName,
        sectorKey = fundamentals.sectorKey,
        industryKey = fundamentals.industryKey,
    )
    var margins = netMarginsBps(timeseries, maxYears)
    var percentile = marginPercentileBps(margins)
    var regime = revenueRegime(timeseries, maxYears)
    var fires = prior.throughCycle && regime == DriverRegime.CyclicalOrTransition && percentile != null
    return CyclePeakReading(
        penaltyPoints = if (fires) penaltyPointsFor(percentile!!) else 0,
        marginPercentileBps = percentile,
        regime = regime,
        throughCycleIndustry = prior.throughCycle,
    )
}

private fun penaltyPointsFor(percentileBps: Int): Int {
    var fraction = (percentileBps - V4_CYCLE_PEAK_LOWER_BPS) / (V4_CYCLE_PEAK_UPPER_BPS - V4_CYCLE_PEAK_LOWER_BPS)
    return (fraction.coerceIn(0.0, 1.0) * V4_CYCLE_PEAK_MAX_PENALTY_POINTS).toInt()
}

/**
 * Net margin per year, in bps of that year's revenue, oldest first.
 *
 * A year is only usable when both lines are present for it, so the two series are joined on
 * `asOfDate` instead of being zipped by position. Revenue must be positive; a negative margin is
 * kept, because a loss year is part of the range a peak is measured against.
 */
private fun netMarginsBps(timeseries: FundamentalTimeseries?, maxYears: Int): List<Int> {
    var revenue = timeseries?.revenue.orEmpty().filter { it.value > 0.0 && it.value.isFinite() }
    var income = timeseries?.netIncome.orEmpty().filter { it.value.isFinite() }.associateBy { it.asOfDate }
    return revenue
        .sortedBy { it.asOfDate }
        .takeLast(maxYears)
        .mapNotNull { year ->
            income[year.asOfDate]?.let { net -> (net.value / year.value * 10_000.0).toInt() }
        }
}

/**
 * The share of the window strictly below the latest year, in bps.
 *
 * With five points the answer can only be one of five values, and the ramp above is deliberately
 * wide enough that the top two of them are the ones that cost anything.
 */
private fun marginPercentileBps(marginsBps: List<Int>): Int? {
    if (marginsBps.size < V4_CYCLE_PEAK_MIN_YEARS) return null
    var latest = marginsBps.last()
    var others = marginsBps.dropLast(1)
    return others.count { it < latest } * 10_000 / others.size
}

private fun revenueRegime(timeseries: FundamentalTimeseries?, maxYears: Int): DriverRegime? {
    var growths = annualGrowthsBps(timeseries?.revenue.orEmpty(), maxYears)
    if (growths.size < 2) return null
    return classifyDriverRegime(growths, emptyList())
}

private fun annualGrowthsBps(series: List<AnnualReportedValue>, maxYears: Int): List<Int> = series
    .filter { it.value > 0.0 && it.value.isFinite() }
    .sortedBy { it.asOfDate }
    .takeLast(maxYears)
    .zipWithNext { previous, latest -> ((latest.value / previous.value - 1.0) * 10_000.0).toInt() }
