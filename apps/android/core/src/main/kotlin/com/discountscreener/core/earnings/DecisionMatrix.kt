package com.discountscreener.core.earnings

import kotlin.math.roundToInt

enum class EventRisk { Low, Normal, High, Unknown }

const val HIGH_RISK_RATIO_BPS = 13_000
const val LOW_RISK_RATIO_BPS = 8_000
const val CHEAP_PRICE_TO_FAIR_BPS = 9_000
const val HEDGE_COST_CAP_BPS = 100
const val PROTECTIVE_PUT_COST_CAP_BPS = 150
const val MAX_QUOTE_SPREAD_BPS = 5_000

fun eventRiskOf(riskRatioBps: Int?): EventRisk = when {
    riskRatioBps == null -> EventRisk.Unknown
    riskRatioBps > HIGH_RISK_RATIO_BPS -> EventRisk.High
    riskRatioBps < LOW_RISK_RATIO_BPS -> EventRisk.Low
    else -> EventRisk.Normal
}

fun priceToFairBps(pre: PreReport): Int? {
    var fair = pre.dcfFairValueCents ?: return null
    if (fair <= 0L) return null
    if (pre.priceCents <= 0L) return null
    return (pre.priceCents * 10_000.0 / fair).roundToInt()
}

fun decisionOf(pre: PreReport): EventDecision {
    var risk = eventRiskOf(pre.riskRatioBps)
    var valuation = priceToFairBps(pre)
    if (isQuoteStale(pre)) return staleQuote(pre)
    if (risk == EventRisk.Unknown || valuation == null) return undecided(pre, risk)
    var cheap = valuation <= CHEAP_PRICE_TO_FAIR_BPS
    return when {
        !cheap && risk == EventRisk.High -> EventDecision(
            cell = DecisionCell.ExpensiveHighRisk,
            action = EventAction.Exit,
            positionSizeBps = 0,
            hedge = HedgeKind.None,
            hedgeCostBps = null,
            sectorOverrideApplied = false,
            justification = "Expensive on the DCF and the market pays " +
                "${ratioText(pre.riskRatioBps)} this ticker's own reaction. Leave before the report.",
        )

        !cheap -> EventDecision(
            cell = DecisionCell.ExpensiveNormalRisk,
            action = EventAction.Reduce,
            positionSizeBps = HALF_POSITION_BPS,
            hedge = HedgeKind.None,
            hedgeCostBps = null,
            sectorOverrideApplied = false,
            justification = "Expensive on the DCF. Reduce for the price, not for the report.",
        )

        risk == EventRisk.High -> cheapHighRisk(pre)

        else -> EventDecision(
            cell = DecisionCell.CheapNormalRisk,
            action = EventAction.Hold,
            positionSizeBps = FULL_POSITION_BPS,
            hedge = HedgeKind.None,
            hedgeCostBps = null,
            sectorOverrideApplied = false,
            justification = "Cheap on the DCF and the report is priced like the ones before it. Hold.",
        )
    }
}

fun isQuoteStale(pre: PreReport): Boolean {
    var spread = pre.quoteSpreadBps ?: return false
    return spread > MAX_QUOTE_SPREAD_BPS
}

private fun staleQuote(pre: PreReport) = EventDecision(
    cell = DecisionCell.Undecided,
    action = EventAction.Hold,
    positionSizeBps = FULL_POSITION_BPS,
    hedge = HedgeKind.None,
    hedgeCostBps = null,
    sectorOverrideApplied = false,
    justification = "The chain is quoted ${percentText(pre.quoteSpreadBps ?: 0)} wide against its own " +
        "mid, so the priced move is the spread and not the report. Read it again while the market is open.",
)

private fun cheapHighRisk(pre: PreReport): EventDecision {
    var spread = pre.putSpreadCostBps
    var put = pre.protectivePutCostBps
    if (spread != null && spread <= HEDGE_COST_CAP_BPS) {
        return hedgeCall(pre, HedgeKind.PutSpread, spread)
    }
    if (put != null && put <= PROTECTIVE_PUT_COST_CAP_BPS) {
        return hedgeCall(pre, HedgeKind.ProtectivePut, put)
    }
    if (spread != null && spread > HEDGE_COST_CAP_BPS) {
        return dearHedge(pre, spread, HEDGE_COST_CAP_BPS, "put spread")
    }
    if (put != null && put > PROTECTIVE_PUT_COST_CAP_BPS) {
        return dearHedge(pre, put, PROTECTIVE_PUT_COST_CAP_BPS, "protective put")
    }
    return hedgeCall(pre, HedgeKind.PutSpread, spread)
}

private fun hedgeCall(pre: PreReport, kind: HedgeKind, cost: Int?) = EventDecision(
    cell = DecisionCell.CheapHighRisk,
    action = EventAction.Hedge,
    positionSizeBps = HALF_POSITION_BPS,
    hedge = kind,
    hedgeCostBps = cost,
    sectorOverrideApplied = false,
    justification = "Cheap on the DCF, and the market pays " +
        "${ratioText(pre.riskRatioBps)} this ticker's own reaction. Half size, or a " +
        hedgeName(kind) + (cost?.let { " at ${percentText(it)} of the position" } ?: "") + ".",
)

private fun dearHedge(pre: PreReport, cost: Int, cap: Int, name: String) = EventDecision(
    cell = DecisionCell.CheapHighRisk,
    action = EventAction.Reduce,
    positionSizeBps = HALF_POSITION_BPS,
    hedge = HedgeKind.None,
    hedgeCostBps = cost,
    sectorOverrideApplied = false,
    justification = "Cheap on the DCF, and the market pays " +
        "${ratioText(pre.riskRatioBps)} this ticker's own reaction. The $name costs " +
        "${percentText(cost)} of the position, over the ${percentText(cap)} cap, so cut the size instead.",
)

private fun hedgeName(kind: HedgeKind): String = when (kind) {
    HedgeKind.ProtectivePut -> "protective put"
    else -> "put spread"
}

private fun percentText(bps: Int): String {
    var percent = bps / 100.0
    return "${(percent * 100).roundToInt() / 100.0}%"
}

private fun undecided(pre: PreReport, risk: EventRisk) = EventDecision(
    cell = DecisionCell.Undecided,
    action = EventAction.Hold,
    positionSizeBps = FULL_POSITION_BPS,
    hedge = HedgeKind.None,
    hedgeCostBps = null,
    sectorOverrideApplied = false,
    justification = missingText(pre, risk),
)

/**
 * A chain that answered with nothing quoted is not the same as no chain at all.
 *
 * Outside the session Yahoo returns the whole ladder with a bid and an ask of zero, which the
 * straddle refuses on purpose. AVGO read "no option chain" all morning while the chain was there
 * and simply shut, so the card sent the reader looking for a fault that did not exist. The expiry
 * is on file whenever the chain answered, and that is enough to tell the two apart.
 */
private fun missingText(pre: PreReport, risk: EventRisk): String = when {
    risk == EventRisk.Unknown && pre.impliedMoveBps == null && pre.expiryEpochDay != null ->
        "The chain for this expiry is not quoted yet, so the report carries no priced move."
    risk == EventRisk.Unknown && pre.impliedMoveBps == null ->
        "No option chain for this expiry, so the report carries no priced move yet."
    risk == EventRisk.Unknown ->
        "No settled reaction of this ticker yet, so the priced move has nothing to be measured against."
    else -> "No fair value for this ticker yet."
}

fun ratioText(riskRatioBps: Int?): String = "%.2fx".format((riskRatioBps ?: 0) / 10_000.0)

private const val FULL_POSITION_BPS = 10_000
private const val HALF_POSITION_BPS = 5_000
