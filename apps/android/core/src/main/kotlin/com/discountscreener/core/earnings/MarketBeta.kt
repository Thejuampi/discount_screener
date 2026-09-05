package com.discountscreener.core.earnings

import java.time.LocalDate

const val MIN_BETA_SAMPLE = 60
const val NEUTRAL_BETA = 1.0

private const val EVENT_BLACKOUT_DAYS = 1L

/**
 * How much of the ticker's daily move the market explains, measured away from its own reports.
 *
 * The abnormal return is what is left of a report once the market's own move is taken out. Taking
 * it out one for one assumes every ticker rides the index exactly, which no ticker does: a name
 * that moves 1.4 times the index would have 40% of every index day counted as its own reaction, and
 * the median of those reactions is the denominator the whole risk score divides by.
 *
 * The report days themselves are cut out of the estimate, along with the day on either side. Those
 * are the days the report moved the price, and leaving them in would let the very events being
 * measured set the yardstick they are measured against.
 *
 * Under [MIN_BETA_SAMPLE] paired days the slope is noise, and the caller is told nothing rather
 * than a number it would trust.
 */
fun marketBetaExcludingEvents(
    symbolCloses: List<DailyClose>,
    marketCloses: List<DailyClose>,
    eventDates: Collection<LocalDate>,
): Double? {
    var blackout = blackoutOf(eventDates)
    var stock = dailyReturns(symbolCloses, blackout)
    var market = dailyReturns(marketCloses, blackout)
    var paired = stock.keys.intersect(market.keys)
    if (paired.size < MIN_BETA_SAMPLE) return null
    var marketMean = paired.sumOf { market.getValue(it) } / paired.size
    var stockMean = paired.sumOf { stock.getValue(it) } / paired.size
    var covariance = paired.sumOf { (market.getValue(it) - marketMean) * (stock.getValue(it) - stockMean) }
    var variance = paired.sumOf { (market.getValue(it) - marketMean) * (market.getValue(it) - marketMean) }
    if (variance <= 0.0) return null
    return (covariance / variance).takeIf { it.isFinite() }
}

fun abnormalReturnBps(stockReturnBps: Int, marketReturnBps: Int, beta: Double?): Int =
    (stockReturnBps - (beta ?: NEUTRAL_BETA) * marketReturnBps).toInt()

private fun blackoutOf(eventDates: Collection<LocalDate>): Set<LocalDate> =
    eventDates.flatMapTo(HashSet()) { date ->
        (-EVENT_BLACKOUT_DAYS..EVENT_BLACKOUT_DAYS).map { offset -> date.plusDays(offset) }
    }

/**
 * A close of zero costs its own day and the day after it, never the two welded into one.
 *
 * Dropping the bad row and pairing what is left would hand the next day a two-day return while the
 * index still reports one, and the slope would read the mismatch as a move the ticker made.
 */
private fun dailyReturns(closes: List<DailyClose>, blackout: Set<LocalDate>): Map<LocalDate, Double> {
    var ordered = closes.sortedBy { it.date }
    var returns = HashMap<LocalDate, Double>(ordered.size)
    ordered.zipWithNext { previous, current ->
        var usable = previous.closeCents > 0L && current.closeCents > 0L
        if (usable && current.date !in blackout && previous.date !in blackout) {
            returns[current.date] = current.closeCents.toDouble() / previous.closeCents - 1.0
        }
    }
    return returns
}
