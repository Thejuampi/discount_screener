package com.discountscreener.core.earnings

import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class DailyClose(val date: LocalDate, val closeCents: Long)

fun dailyCloseOf(epochSeconds: Long, closeCents: Long): DailyClose =
    DailyClose(Instant.ofEpochSecond(epochSeconds).atZone(EXCHANGE_ZONE).toLocalDate(), closeCents)

/**
 * What the report did to the price, measured on the day the report was actually filed.
 *
 * A calendar date is a plan. Companies move reports, and the date on file is the one the calendar
 * carried when the chain was captured. Reading the move on a day with no report hands the log a
 * reaction the report never caused, and that reaction becomes the median every later risk ratio
 * divides by. The 8-K is the filing itself, so it settles the argument: the move is read on the
 * filed day, and a date with no filing near it is left unsettled instead of invented.
 */
fun settlementOf(
    pre: PreReport,
    symbolCloses: List<DailyClose>,
    marketCloses: List<DailyClose>,
    reportedQuarters: List<ReportedQuarter> = emptyList(),
    marketBeta: Double? = null,
    announcements: List<EarningsAnnouncement> = emptyList(),
): PostReport? {
    var planned = LocalDate.ofEpochDay(pre.reportEpochDay)
    var filed = filedNear(announcements, planned)
    if (announcements.isNotEmpty() && filed == null) return null
    var reportDate = filed?.date ?: planned
    var timing = filed?.timing ?: pre.timing
    var stock = reactionOf(symbolCloses, reportDate, timing) ?: return null
    var market = reactionOf(marketCloses, reportDate, timing)
    var abnormal = market?.let { abnormalReturnBps(stock, it, marketBeta) }
    var reported = quarterReportedOn(reportedQuarters, reportDate)
    var eps = reported?.epsActual?.let { toCents(it) }
    var revenue = reported?.revenueActual?.let { toCents(it) }
    return PostReport(
        epsActualCents = eps,
        surpriseScoreBps = surpriseScoreBps(eps, pre),
        revenueActualCents = revenue,
        revenueSurpriseBps = revenueSurpriseBps(revenue, pre),
        stockReturnBps = stock,
        marketReturnBps = market,
        marketBetaBps = marketBeta?.let { (it * 10_000.0).roundToInt() },
        abnormalReturnBps = abnormal,
        reportedOnEpochDay = reportDate.toEpochDay(),
    )
}

private fun filedNear(
    announcements: List<EarningsAnnouncement>,
    planned: LocalDate,
): EarningsAnnouncement? = announcements
    .filter { abs(it.date.toEpochDay() - planned.toEpochDay()) <= REPORT_CONFIRM_DAYS }
    .minByOrNull { abs(it.date.toEpochDay() - planned.toEpochDay()) }

const val REPORT_CONFIRM_DAYS = 7L

private fun toCents(value: Double): Long? =
    if (value.isFinite()) (value * 100.0).roundToLong() else null

internal fun reactionOf(closes: List<DailyClose>, reportDate: LocalDate, timing: ReportTiming): Int? {
    var ordered = closes.sortedBy { it.date }
    var base = when (timing) {
        ReportTiming.AfterClose -> ordered.lastOrNull { it.date <= reportDate }
        else -> ordered.lastOrNull { it.date < reportDate }
    } ?: return null
    var reaction = when (timing) {
        ReportTiming.BeforeOpen -> ordered.firstOrNull { it.date >= reportDate }
        else -> ordered.firstOrNull { it.date > reportDate }
    } ?: return null
    if (reaction.date <= base.date || base.closeCents <= 0L) return null
    return ((reaction.closeCents.toDouble() / base.closeCents - 1.0) * 10_000.0).roundToInt()
}
