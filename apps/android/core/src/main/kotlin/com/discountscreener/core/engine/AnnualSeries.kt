package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue

/**
 * Whether two annual filings are consecutive fiscal years.
 *
 * [AnnualReportedValue.fiscalYear] already derives the year from the ISO `asOfDate` prefix and
 * reports null for a date it cannot parse, so a malformed filing refuses here rather than
 * producing a year that does not exist. A restated duplicate (the same fiscal year filed twice)
 * yields a delta of zero and refuses too: a filing compared against its own restatement is one
 * year of change wearing two rows.
 */
internal fun areConsecutiveFiscalYears(previous: AnnualReportedValue, latest: AnnualReportedValue): Boolean {
    var previousYear = previous.fiscalYear ?: return false
    var latestYear = latest.fiscalYear ?: return false
    return latestYear - previousYear == 1
}

/**
 * Adjacent-year transitions whose ratio means growth: both levels present, finite and positive.
 *
 * Three refusals, each for its own reason:
 *
 * * a non-adjacent pair spans more than one year, so dividing across it prints a multi-year move
 *   as one annual rate;
 * * a non-positive base inverts the sign of the ratio — a narrowing loss reads as decline — so no
 *   rate forms from it;
 * * a crossing (loss to profit) prints nonsense outright: −10 to +10 is −20 000 bps, the worst
 *   reading in the band, for a company that just doubled its profit.
 *
 * The positive-level rule is also the population rule: Yahoo's quarter EPS YoY is compared against
 * profit-year transitions only, because that is the population a quarter rate belongs to.
 */
internal fun positiveLevelTransitions(
    series: List<AnnualReportedValue>,
    maxYears: Int,
): List<Pair<Double, Double>> = series
    .filter { it.value.isFinite() && it.value > 0.0 }
    .sortedBy { it.asOfDate }
    .takeLast(maxYears)
    .zipWithNext { previous, latest ->
        if (areConsecutiveFiscalYears(previous, latest)) {
            previous.value to latest.value
        } else {
            null
        }
    }
    .filterNotNull()
