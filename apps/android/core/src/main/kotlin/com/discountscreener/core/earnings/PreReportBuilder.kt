package com.discountscreener.core.earnings

import com.discountscreener.core.math.medianOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

const val CAPTURE_WINDOW_DAYS = 10L

data class DcfAsOf(val fairValueCents: Long, val computedOn: LocalDate)

val EXCHANGE_ZONE: ZoneId = ZoneId.of("America/New_York")

fun reportTimingOf(epochSeconds: Long): Pair<LocalDate, ReportTiming> {
    var moment = Instant.ofEpochSecond(epochSeconds).atZone(EXCHANGE_ZONE)
    var time = moment.toLocalTime()
    var timing = when {
        time < MARKET_OPENS -> ReportTiming.BeforeOpen
        time >= MARKET_CLOSES -> ReportTiming.AfterClose
        else -> ReportTiming.Unknown
    }
    return moment.toLocalDate() to timing
}

fun preReportOf(
    symbol: String,
    reportDate: LocalDate,
    timing: ReportTiming,
    priceCents: Long,
    dcf: DcfAsOf? = null,
    chain: OptionChainSnapshot? = null,
    expiry: LocalDate? = null,
    consensus: ConsensusEstimate? = null,
    pastAbnormalReturnsBps: List<Int> = emptyList(),
    normalDailyMoveBps: Int? = null,
): PreReport {
    var forwardCents = chain?.underlyingPriceCents?.takeIf { it > 0L } ?: priceCents
    var forward = forwardCents / 100.0
    var move: ImpliedMove? = chain?.let { impliedMove(it.rows, forward) }
    var hedge = move?.let { hedgeQuoteOf(chain?.rows.orEmpty(), it, forward) }
    var impliedMoveBps = move?.fraction?.let { toBps(it) }
    var settlementDate = expiry ?: chain?.expiry
    var eventMove = eventMoveBps(
        totalMoveBps = impliedMoveBps,
        normalDailyBps = normalDailyMoveBps,
        tradingDaysToExpiry = settlementDate?.let { tradingDaysBetween(reportDate, it) } ?: 0,
    )
    var medianAbsolute = medianOf(pastAbnormalReturnsBps.map { abs(it.toDouble()) })
    return PreReport(
        symbol = symbol,
        reportEpochDay = reportDate.toEpochDay(),
        timing = timing,
        dcfComputedOnEpochDay = dcf?.computedOn?.toEpochDay(),
        dcfFairValueCents = dcf?.fairValueCents,
        priceCents = priceCents,
        impliedMoveBps = impliedMoveBps,
        eventImpliedMoveBps = eventMove,
        normalDailyMoveBps = normalDailyMoveBps,
        quoteSpreadBps = move?.quoteSpreadBps,
        expiryEpochDay = settlementDate?.toEpochDay(),
        forwardPriceCents = move?.let { forwardCents },
        strikeCents = move?.strike?.let { toCents(it) },
        medianAbsoluteAbnormalReturnBps = medianAbsolute?.let { it.roundToInt() },
        riskRatioBps = riskRatioBps(eventMove, medianAbsolute),
        consensusEpsCents = consensus?.avgEps?.let { toCents(it) },
        consensusEpsLowCents = consensus?.lowEps?.let { toCents(it) },
        consensusEpsHighCents = consensus?.highEps?.let { toCents(it) },
        analystCount = consensus?.analystCount,
        consensusRevenueCents = consensus?.avgRevenue?.let { toCents(it) },
        protectivePutCostBps = hedge?.protectivePutCostBps,
        putSpreadCostBps = hedge?.putSpreadCostBps,
        hedgeLongStrikeCents = hedge?.longStrike?.let { toCents(it) },
        hedgeShortStrikeCents = hedge?.shortStrike?.let { toCents(it) },
    )
}

private fun riskRatioBps(eventMoveBps: Int?, medianAbsoluteBps: Double?): Int? {
    if (eventMoveBps == null || medianAbsoluteBps == null || medianAbsoluteBps <= 0.0) return null
    return (eventMoveBps / medianAbsoluteBps * 10_000.0).roundToInt()
}

private fun toBps(fraction: Double): Int? =
    if (fraction.isFinite()) (fraction * 10_000.0).roundToInt() else null

private fun toCents(value: Double): Long? =
    if (value.isFinite()) (value * 100.0).roundToLong() else null
