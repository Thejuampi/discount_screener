package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.ui.dashboard.formatPct
import com.discountscreener.core.earnings.DecisionCell
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.EventDecision
import com.discountscreener.core.earnings.EventAction
import com.discountscreener.core.earnings.EventRisk
import com.discountscreener.core.earnings.HedgeKind
import com.discountscreener.core.earnings.PostReport
import com.discountscreener.core.earnings.PreReport
import com.discountscreener.core.earnings.ratioText
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.earnings.eventRiskOf
import com.discountscreener.core.earnings.isQuoteStale
import com.discountscreener.core.earnings.priceToFairBps
import com.discountscreener.core.portfolio.isHeld
import com.discountscreener.core.portfolio.pinHeldFirst
import java.time.LocalDate
import kotlin.math.roundToInt

data class EarningsGateUi(
    val upcoming: List<EarningsEventRowUi> = emptyList(),
    val settled: List<EarningsEventRowUi> = emptyList(),
    val damagedLines: Int = 0,
    val lastCapture: String? = null,
    val alphaVantageKeyPresent: Boolean = false,
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
    val sueFit: String? = null,
    val revenueTrail: String? = null,
    val held: Boolean = false,
    val reportEpochDay: Long? = null,
    val simpleRisk: EarningsSimpleRiskUi,
    val optionExpiry: String,
    val optionExplanation: String,
    val optionWarning: String?,
)

data class EarningsRiskPathUi(val title: String, val tradeoff: String)

data class EarningsSimpleRiskUi(
    val headline: String,
    val reportMove: String,
    val pastMove: String,
    val paths: List<EarningsRiskPathUi>,
    val outcome: String?,
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
    alphaVantageKeyPresent: Boolean = false,
    held: Set<String> = emptySet(),
): EarningsGateUi {
    var heldSet = held.map { it.trim().uppercase() }.toSet()
    var upcoming = events.filter { it.pre.reportEpochDay >= today.toEpochDay() }
        .sortedBy { it.pre.reportEpochDay }
    var settled = events.filter { it.pre.reportEpochDay < today.toEpochDay() }
        .sortedByDescending { it.pre.reportEpochDay }
    return EarningsGateUi(
        upcoming = pinHeldFirst(upcoming.map { rowOf(it, heldSet, upcoming = true) }, heldSet) { it.symbol },
        settled = pinHeldFirst(settled.map { rowOf(it, heldSet, upcoming = false) }, heldSet) { it.symbol },
        damagedLines = damagedLines,
        lastCapture = lastCaptureText(lastCaptureEpochSeconds, nowEpochSeconds),
        alphaVantageKeyPresent = alphaVantageKeyPresent,
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

private fun rowOf(record: EarningsEventRecord, held: Set<String>, upcoming: Boolean): EarningsEventRowUi {
    var pre = record.pre
    var decision = record.decision
    return EarningsEventRowUi(
        symbol = pre.symbol,
        reportDate = LocalDate.ofEpochDay(pre.reportEpochDay).toString(),
        timing = timingLabel(pre.timing),
        risk = if (decision?.cell == DecisionCell.Undecided) {
            EventRisk.Unknown
        } else {
            eventRiskOf(pre.riskRatioBps)
        },
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
        sueFit = sueFitText(pre),
        revenueTrail = revenueTrailText(pre, decision),
        held = isHeld(pre.symbol, held),
        reportEpochDay = pre.reportEpochDay,
        simpleRisk = simpleRiskOf(pre, decision, record.post, upcoming, isHeld(pre.symbol, held)),
        optionExpiry = pre.expiryEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "Not saved",
        optionExplanation = optionExplanation(pre, decision?.hedge),
        optionWarning = decision?.takeIf {
            (it.action == EventAction.Hedge) != (it.hedge != HedgeKind.None)
        }?.let { "Saved action and put example disagree. No trade steps are available." },
    )
}

private fun simpleRiskOf(
    pre: PreReport,
    decision: EventDecision?,
    post: PostReport?,
    upcoming: Boolean,
    held: Boolean,
): EarningsSimpleRiskUi {
    val risk = if (decision?.cell == DecisionCell.Undecided) EventRisk.Unknown else eventRiskOf(pre.riskRatioBps)
    val headline = when {
        risk == EventRisk.High && upcoming -> "Saved prices suggest a larger move than past reports showed."
        risk == EventRisk.High -> "Before this report, saved prices suggested a larger move than earlier reports showed."
        risk != EventRisk.Unknown && upcoming -> "Saved prices suggest a move near or below past reports."
        risk != EventRisk.Unknown -> "Before this report, saved prices suggested a move near or below earlier reports."
        upcoming -> "There is not enough reliable data to compare this report with past reports."
        else -> "There was not enough reliable data to compare this report with earlier reports."
    }
    val reportMove = pre.eventImpliedMoveBps?.takeUnless { isQuoteStale(pre) }?.let {
        if (upcoming) {
            "Saved options prices suggest about ${formatPct(it)} around this report. Direction is unknown."
        } else {
            "Before this report, saved options prices suggested about ${formatPct(it)}. Direction was unknown."
        }
    } ?: "No reliable report move is saved."
    val pastMove = pre.medianAbsoluteAbnormalReturnBps?.let {
        "Past reports moved about ${formatPct(it)} beyond the broad market."
    } ?: "No usable past report moves are saved."
    val paths = if (!upcoming) {
        emptyList()
    } else if (held) {
        listOf(
            EarningsRiskPathUi("Keep current shares", "The full position stays exposed to a move in either direction."),
            EarningsRiskPathUi("Hold fewer shares", "A smaller position changes less when this stock moves, up or down."),
            EarningsRiskPathUi("Wait before adding", "You can see the report first, but the price may move before you buy."),
        )
    } else {
        listOf(
            EarningsRiskPathUi("Wait before buying", "You can see the report first, but the price may move before you buy."),
            EarningsRiskPathUi("Buy before the report", "You take the full price move in either direction."),
        )
    }
    val abnormalReturnBps = post?.abnormalReturnBps
    val outcome = when {
        upcoming -> null
        abnormalReturnBps != null ->
            "After this report, the stock moved ${formatSignedPct(abnormalReturnBps)} beyond the broad market."
        else -> "No report outcome is saved yet."
    }
    return EarningsSimpleRiskUi(headline, reportMove, pastMove, paths, outcome)
}

private fun optionExplanation(pre: PreReport, hedge: HedgeKind?): String = when (hedge) {
    HedgeKind.PutSpread -> {
        val high = pre.hedgeLongStrikeCents
        val low = pre.hedgeShortStrikeCents
        if (high != null && low != null) {
            "A bought \$${centsText(high)} put and a sold \$${centsText(low)} put form this saved spread. " +
                "Protection stops growing below \$${centsText(low)}."
        } else {
            "A put spread uses two puts. Protection stops growing below the lower strike."
        }
    }
    HedgeKind.ProtectivePut -> "A put costs a premium and can limit some losses until expiry."
    HedgeKind.None, null -> "The model has no option example for this report."
}

private fun revenueTrailText(pre: PreReport, decision: EventDecision?): String? {
    var latest = pre.revenueTrailLatestCents ?: return null
    var centre = pre.trailCentre() ?: return null
    var scale = pre.revenueTrailScaleCents ?: return null
    var cut = if (decision?.trailCut() == true) " · size cut" else ""
    if (scale <= 0L) {
        return if (latest < centre) "Last print below a flat trail$cut" else null
    }
    var z = pre.revenueTrailShortfallZBps
        ?: ((centre - latest).toDouble() / scale * 10_000.0).roundToInt()
    return "Last print ${"%.2f".format(z / 10_000.0)} MAD vs the trail centre$cut"
}

private fun sueFitText(pre: PreReport): String? {
    var slope = pre.surpriseFitSueSlopeArBps
    var n = pre.surpriseFitN
    if (slope != null && n != null) {
        var shape = if (pre.surpriseFitAsymmetric == true) ", truncated at zero SUE" else ""
        return "SUE slope ${formatPct(slope)} AR per dispersion, n=$n$shape"
    }
    var reason = pre.surpriseFitUnavailableReason ?: return null
    return when (reason) {
        "short_history" -> "SUE history too short"
        "missing_key" -> "SUE key missing"
        "no_history" -> "SUE history empty"
        else -> "SUE history $reason"
    }
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
