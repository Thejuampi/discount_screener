package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.ui.dashboard.formatPct
import com.discountscreener.core.earnings.DecisionCell
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.EventRisk
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
)

fun presentEarningsGate(
    events: List<EarningsEventRecord>,
    damagedLines: Int,
    today: LocalDate,
): EarningsGateUi {
    var upcoming = events.filter { it.pre.reportEpochDay >= today.toEpochDay() }
        .sortedBy { it.pre.reportEpochDay }
    var settled = events.filter { it.pre.reportEpochDay < today.toEpochDay() }
        .sortedByDescending { it.pre.reportEpochDay }
    return EarningsGateUi(
        upcoming = upcoming.map(::rowOf),
        settled = settled.map(::rowOf),
        damagedLines = damagedLines,
    )
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
        reaction = record.post?.abnormalReturnBps?.let { "Abnormal move ${formatSignedPct(it)}" },
    )
}

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
