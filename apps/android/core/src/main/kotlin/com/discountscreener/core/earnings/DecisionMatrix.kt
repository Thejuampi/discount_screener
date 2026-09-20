package com.discountscreener.core.earnings

import kotlin.math.roundToInt

enum class EventRisk { Low, Normal, High, Unknown }

const val UNITY_BPS = 10_000

fun eventRiskOf(riskRatioBps: Int?): EventRisk = when {
    riskRatioBps == null -> EventRisk.Unknown
    riskRatioBps > UNITY_BPS -> EventRisk.High
    else -> EventRisk.Normal
}

fun priceToFairBps(pre: PreReport): Int? {
    var fair = pre.dcfFairValueCents ?: return null
    if (fair <= 0L) return null
    if (pre.priceCents <= 0L) return null
    return (pre.priceCents * 10_000.0 / fair).roundToInt()
}

fun identityChanged(next: EventDecision, prior: EventDecision?): Boolean {
    if (prior == null) return true
    return next.cell != prior.cell ||
        next.action != prior.action ||
        next.hedge != prior.hedge ||
        next.positionSizeBps != prior.positionSizeBps ||
        next.justification != prior.justification
}

fun decisionOf(pre: PreReport): EventDecision {
    if (pre.impliedMoveBps == null) return undecided(pre, EventRisk.Unknown, "chain_unavailable")
    if (pre.eventImpliedMoveBps == null) {
        return undecided(pre, EventRisk.Unknown, "quiet_dominates_implied")
    }
    if (isQuoteStale(pre)) return staleQuote(pre)
    if (pre.priceCents <= 0L) return undecided(pre, eventRiskOf(pre.riskRatioBps), "price_unavailable")
    var fair = pre.dcfFairValueCents
    if (fair == null || fair <= 0L) {
        return undecided(pre, eventRiskOf(pre.riskRatioBps), "dcf_unavailable")
    }
    var risk = eventRiskOf(pre.riskRatioBps)
    if (risk == EventRisk.Unknown) return undecided(pre, risk, "ar_unavailable")
    var valuation = priceToFairBps(pre) ?: return undecided(pre, risk, "dcf_unavailable")
    var cheap = valuation < UNITY_BPS
    return when {
        !cheap && risk == EventRisk.High -> EventDecision(
            cell = DecisionCell.ExpensiveHighRisk,
            action = EventAction.Exit,
            positionSizeBps = 0,
            hedge = HedgeKind.None,
            hedgeCostBps = null,
            justification = "Expensive on the DCF and the market pays " +
                "${ratioText(pre.riskRatioBps)} this ticker's own reaction. Leave before the report.",
        )

        !cheap -> EventDecision(
            cell = DecisionCell.ExpensiveNormalRisk,
            action = EventAction.Reduce,
            positionSizeBps = HALF_POSITION_BPS,
            hedge = HedgeKind.None,
            hedgeCostBps = null,
            justification = "Expensive on the DCF. Reduce for the price, not for the report.",
        )

        risk == EventRisk.High -> cheapHighRisk(pre)

        else -> applyRevenueOverride(
            EventDecision(
                cell = DecisionCell.CheapNormalRisk,
                action = EventAction.Hold,
                positionSizeBps = FULL_POSITION_BPS,
                hedge = HedgeKind.None,
                hedgeCostBps = null,
                justification = "Cheap on the DCF and the report is priced like the ones before it. Hold.",
            ),
            pre,
        )
    }
}

fun isQuoteStale(pre: PreReport): Boolean {
    var spread = pre.quoteSpreadBps ?: return false
    return spread >= UNITY_BPS
}

private fun staleQuote(pre: PreReport) = EventDecision(
    cell = DecisionCell.Undecided,
    action = EventAction.Hold,
    positionSizeBps = FULL_POSITION_BPS,
    hedge = HedgeKind.None,
    hedgeCostBps = null,
    unavailableReason = "option_width_ge_straddle",
    justification = "option_width_ge_straddle. The chain is quoted ${percentText(pre.quoteSpreadBps ?: 0)} " +
        "wide against its own mid, so the priced move is the spread and not the report. " +
        "Read it again while the market is open.",
)

private fun cheapHighRisk(pre: PreReport): EventDecision {
    var event = pre.eventImpliedMoveBps ?: return reduceUnpriced(pre)
    var spread = pre.putSpreadCostBps
    var put = pre.protectivePutCostBps
    if (spread != null && spread < event) return hedgeCall(pre, HedgeKind.PutSpread, spread)
    if (put != null && put < event) return hedgeCall(pre, HedgeKind.ProtectivePut, put)
    if (spread == null && put == null) return reduceUnpriced(pre)
    var cost = listOfNotNull(spread, put).minOrNull() ?: return reduceUnpriced(pre)
    var name = if (spread != null && spread >= event) "put spread" else "protective put"
    return dearHedge(pre, cost, event, name)
}

private fun hedgeCall(pre: PreReport, kind: HedgeKind, cost: Int?) = EventDecision(
    cell = DecisionCell.CheapHighRisk,
    action = EventAction.Hedge,
    positionSizeBps = HALF_POSITION_BPS,
    hedge = kind,
    hedgeCostBps = cost,
    justification = "Cheap on the DCF, and the market pays " +
        "${ratioText(pre.riskRatioBps)} this ticker's own reaction. Half size, or a " +
        hedgeName(kind) + (cost?.let { " at ${percentText(it)} of the position" } ?: "") + ".",
)

private fun dearHedge(pre: PreReport, cost: Int, event: Int, name: String) = EventDecision(
    cell = DecisionCell.CheapHighRisk,
    action = EventAction.Reduce,
    positionSizeBps = HALF_POSITION_BPS,
    hedge = HedgeKind.None,
    hedgeCostBps = cost,
    justification = "Cheap on the DCF, and the market pays " +
        "${ratioText(pre.riskRatioBps)} this ticker's own reaction. The $name costs " +
        "${percentText(cost)} of the position, at or over the ${percentText(event)} event move, " +
        "so cut the size instead.",
)

private fun reduceUnpriced(pre: PreReport) = EventDecision(
    cell = DecisionCell.CheapHighRisk,
    action = EventAction.Reduce,
    positionSizeBps = HALF_POSITION_BPS,
    hedge = HedgeKind.None,
    hedgeCostBps = null,
    justification = "Cheap on the DCF, and the market pays " +
        "${ratioText(pre.riskRatioBps)} this ticker's own reaction. No quoted hedge costs less " +
        "than the event, so cut the size instead.",
)

private fun hedgeName(kind: HedgeKind): String = when (kind) {
    HedgeKind.ProtectivePut -> "protective put"
    else -> "put spread"
}

private fun percentText(bps: Int): String {
    var percent = bps / 100.0
    return "${(percent * 100).roundToInt() / 100.0}%"
}

private fun undecided(pre: PreReport, risk: EventRisk, reason: String) = EventDecision(
    cell = DecisionCell.Undecided,
    action = EventAction.Hold,
    positionSizeBps = FULL_POSITION_BPS,
    hedge = HedgeKind.None,
    hedgeCostBps = null,
    unavailableReason = reason,
    justification = "$reason. ${missingText(pre, risk, reason)}",
)

private fun missingText(pre: PreReport, risk: EventRisk, reason: String): String = when (reason) {
    "quiet_dominates_implied" ->
        "Quiet-day drift eats the priced move, so the report carries no event numerator."
    "price_unavailable" ->
        "The last price is missing or halted, so cheap versus fair cannot be read."
    "dcf_unavailable" ->
        "No fair value for this ticker yet."
    "ar_unavailable" ->
        "No settled reaction of this ticker yet, so the priced move has nothing to be measured against."
    else -> when {
        risk == EventRisk.Unknown && pre.impliedMoveBps == null && pre.expiryEpochDay != null ->
            "The chain for this expiry is not quoted yet, so the report carries no priced move."
        risk == EventRisk.Unknown && pre.impliedMoveBps == null ->
            "No option chain for this expiry, so the report carries no priced move yet."
        else -> "No fair value for this ticker yet."
    }
}

fun ratioText(riskRatioBps: Int?): String = "%.2fx".format((riskRatioBps ?: 0) / 10_000.0)

private fun applyRevenueOverride(decision: EventDecision, pre: PreReport): EventDecision {
    var latest = pre.revenueTrailLatestCents ?: return decision
    var centre = pre.trailCentre() ?: return decision
    var scale = pre.revenueTrailScaleCents ?: return decision
    if (latest >= centre) return decision
    var shortfall = (centre - latest).toDouble()
    if (shortfall <= scale.toDouble()) return decision
    var z = if (scale <= 0L) null else (shortfall / scale * 10_000.0).roundToInt()
    var cut = if (scale <= 0L) {
        ". Last print sits below a flat trail, so cut to half."
    } else {
        ". Last print revenue sits ${"%.2f".format((z ?: 0) / 10_000.0)} MAD below the trail centre, so cut to half."
    }
    return decision.copy(
        action = EventAction.Reduce,
        positionSizeBps = HALF_POSITION_BPS,
        revenueTrailCut = true,
        justification = decision.justification.trimEnd('.') + cut,
    )
}

private const val FULL_POSITION_BPS = 10_000
private const val HALF_POSITION_BPS = 5_000
