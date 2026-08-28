package com.discountscreener.core.earnings

import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class DailyClose(val date: LocalDate, val closeCents: Long)

fun dailyCloseOf(epochSeconds: Long, closeCents: Long): DailyClose =
    DailyClose(Instant.ofEpochSecond(epochSeconds).atZone(EXCHANGE_ZONE).toLocalDate(), closeCents)

fun settlementOf(
    pre: PreReport,
    symbolCloses: List<DailyClose>,
    marketCloses: List<DailyClose>,
    reportedQuarters: List<ReportedQuarter> = emptyList(),
    marketBeta: Double? = null,
): PostReport? {
    var reportDate = LocalDate.ofEpochDay(pre.reportEpochDay)
    var stock = reactionOf(symbolCloses, reportDate, pre.timing) ?: return null
    var market = reactionOf(marketCloses, reportDate, pre.timing)
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
        abnormalReturnBps = abnormal,
    )
}

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
