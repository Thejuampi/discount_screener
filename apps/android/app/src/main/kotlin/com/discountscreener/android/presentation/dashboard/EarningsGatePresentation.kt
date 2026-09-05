package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.ui.dashboard.formatPct
import com.discountscreener.core.earnings.DecisionCell
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.EventRisk
import com.discountscreener.core.earnings.PostReport
import com.discountscreener.core.earnings.PreReport
import com.discountscreener.core.earnings.ratioText
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.earnings.eventRiskOf
import com.discountscreener.core.earnings.priceToFairBps
import java.time.LocalDate

data class EarningsGateUi(
    val upcoming: List<EarningsEventRowUi> = emptyList(),
    val settled: List<EarningsEventRowUi> = emptyList(),
    val damagedLines: Int = 0,
    val lastCapture: String? = null,
) {
    val isEmpty: Boolean get() = upcoming.isEmpty() && settled.isEmpty()
}

data class EarningsEventRowUi(
    val symbol: String,
    val reportDate: String,
    val timing: String,
    val risk: EventRisk,
    val cell: DecisionCell,
    val headline: String,
    val impliedMove: String,
    val eventMove: String,
    val ownHistory: String,
    val riskRatio: String,
    val priceToFair: String,
    val action: String,
    val positionSize: String,
    val hedge: String,
    val hedgeCost: String,
    val justification: String,
    val reaction: String?,
    val surprise: String?,
    val reportedOn: String?,
)

fun EarningsGateUi.matching(query: String): EarningsGateUi {
    var term = query.trim()
    if (term.isEmpty()) return this
    return copy(
        upcoming = upcoming.filter { it.symbol.startsWith(term, ignoreCase = true) },
        settled = settled.filter { it.symbol.startsWith(term, ignoreCase = true) },
    )
}

fun EarningsGateUi.eventsFor(symbol: String): List<EarningsEventRowUi> = listOfNotNull(
    upcoming.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) },
    settled.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) },
)

fun presentEarningsGate(
    events: List<EarningsEventRecord>,
    damagedLines: Int,
    today: LocalDate,
    lastCaptureEpochSeconds: Long? = null,
    nowEpochSeconds: Long? = null,
): EarningsGateUi {
    var upcoming = events.filter { it.pre.reportEpochDay >= today.toEpochDay() }
        .sortedBy { it.pre.reportEpochDay }
    var settled = events.filter { it.pre.reportEpochDay < today.toEpochDay() }
        .sortedByDescending { it.pre.reportEpochDay }
    return EarningsGateUi(
        upcoming = upcoming.map(::rowOf),
        settled = settled.map(::rowOf),
        damagedLines = damagedLines,
        lastCapture = lastCaptureText(lastCaptureEpochSeconds, nowEpochSeconds),
    )
}

/**
 * The capture runs on its own every ninety minutes, and a pass with nothing to write leaves no
 * trace in the list. Without this line a module that stopped running looks exactly like a module
 * with nothing to say.
 */
private fun lastCaptureText(lastCaptureEpochSeconds: Long?, nowEpochSeconds: Long?): String? {
    var last = lastCaptureEpochSeconds ?: return null
    var now = nowEpochSeconds ?: return null
    var minutes = (now - last) / 60L
    return when {
        minutes < 0L -> null
        minutes < 1L -> "Checked just now"
        minutes < 60L -> "Checked ${minutes}m ago"
        minutes < 60L * 48L -> "Checked ${minutes / 60L}h ago"
        else -> "Checked ${minutes / (60L * 24L)}d ago"
    }
}

private fun rowOf(record: EarningsEventRecord): EarningsEventRowUi {
    var pre = record.pre
    var decision = record.decision
    return EarningsEventRowUi(
        symbol = pre.symbol,
        reportDate = LocalDate.ofEpochDay(pre.reportEpochDay).toString(),
        timing = timingLabel(pre.timing),
        risk = eventRiskOf(pre.riskRatioBps),
        cell = decision?.cell ?: DecisionCell.Undecided,
        headline = cellLabel(decision?.cell ?: DecisionCell.Undecided),
        impliedMove = pre.impliedMoveBps?.let(::formatPct) ?: MISSING,
        eventMove = eventMoveText(pre),
        ownHistory = pre.medianAbsoluteAbnormalReturnBps?.let(::formatPct) ?: MISSING,
        riskRatio = pre.riskRatioBps?.let { ratioText(it) } ?: MISSING,
        priceToFair = priceToFairBps(pre)?.let(::formatPct) ?: MISSING,
        action = decision?.action?.name ?: MISSING,
        positionSize = decision?.positionSizeBps?.let { "${it / 100}%" } ?: MISSING,
        hedge = hedgeLabel(decision?.hedge?.name),
        hedgeCost = hedgeCostText(pre),
        justification = decision?.justification.orEmpty(),
        reaction = reactionText(record.post),
        surprise = surpriseText(record.post),
        reportedOn = reportedOnText(pre, record.post),
    )
}

/**
 * The day the report was really filed, shown only when the calendar had it wrong.
 *
 * The date on the card is the one the calendar carried when the chain was captured, and companies
 * move reports. This line tells the reader the reaction below was read on another day, so a
 * report that landed late is never mistaken for one that landed on plan.
 */
private fun reportedOnText(pre: PreReport, post: PostReport?): String? {
    var filed = post?.reportedOnEpochDay ?: return null
    if (filed == pre.reportEpochDay) return null
    return "${LocalDate.ofEpochDay(filed)} (calendar said ${LocalDate.ofEpochDay(pre.reportEpochDay)})"
}

/**
 * What the report turned out to be, once it landed.
 *
 * The reaction alone says the price moved and never why. The surprise is the half the log already
 * held and no screen ever showed: the beat measured in how far apart the analysts were, and the
 * revenue against the number they had agreed on.
 */
private fun surpriseText(post: PostReport?): String? {
    if (post == null) return null
    var parts = listOfNotNull(
        post.surpriseScoreBps?.let { "EPS ${formatSigned(it)} of the analyst spread" },
        post.revenueSurpriseBps?.let { "revenue ${formatSignedPct(it)}" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private fun reactionText(post: PostReport?): String? {
    var abnormal = post?.abnormalReturnBps ?: return null
    var beta = post.marketBetaBps?.let { ", beta ${ratioText(it)}" }.orEmpty()
    return "Abnormal move ${formatSignedPct(abnormal)}$beta"
}

private fun formatSigned(bps: Int): String = "%+.2f".format(bps / 10_000.0)

private fun timingLabel(timing: ReportTiming): String = when (timing) {
    ReportTiming.BeforeOpen -> "Before open"
    ReportTiming.AfterClose -> "After close"
    ReportTiming.Unknown -> "Hour unconfirmed"
}

private fun cellLabel(cell: DecisionCell): String = when (cell) {
    DecisionCell.ExpensiveHighRisk -> "Expensive, high event risk"
    DecisionCell.ExpensiveNormalRisk -> "Expensive, normal event risk"
    DecisionCell.CheapHighRisk -> "Cheap, high event risk"
    DecisionCell.CheapNormalRisk -> "Cheap, normal event risk"
    DecisionCell.Undecided -> "Waiting on data"
}

private fun eventMoveText(pre: PreReport): String {
    var event = pre.eventImpliedMoveBps ?: return MISSING
    var quiet = pre.normalDailyMoveBps ?: return formatPct(event)
    return "${formatPct(event)} after ${formatPct(quiet)} a day of quiet drift"
}

private fun hedgeCostText(pre: PreReport): String {
    var spread = pre.putSpreadCostBps ?: return pre.protectivePutCostBps
        ?.let { "${formatPct(it)} of the position for a protective put" }
        ?: MISSING
    var strikes = strikeText(pre)
    return "${formatPct(spread)} of the position$strikes"
}

private fun strikeText(pre: PreReport): String {
    var long = pre.hedgeLongStrikeCents ?: return ""
    var short = pre.hedgeShortStrikeCents ?: return ""
    return " (${centsText(long)} / ${centsText(short)} puts)"
}

private fun centsText(cents: Long): String = "%.2f".format(cents / 100.0)

private fun hedgeLabel(name: String?): String = when (name) {
    "PutSpread" -> "Put spread"
    "ProtectivePut" -> "Protective put"
    "None" -> "None"
    else -> MISSING
}

private fun formatSignedPct(bps: Int): String = if (bps > 0) "+${formatPct(bps)}" else formatPct(bps)

private const val MISSING = "—"
