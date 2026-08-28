package com.discountscreener.core.earnings

import kotlin.math.roundToInt

enum class EventRisk { Low, Normal, High, Unknown }

const val HIGH_RISK_RATIO_BPS = 13_000
const val LOW_RISK_RATIO_BPS = 8_000
const val CHEAP_PRICE_TO_FAIR_BPS = 9_000
const val HEDGE_COST_CAP_BPS = 100
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
    if (spread != null && spread > HEDGE_COST_CAP_BPS) {
        return EventDecision(
            cell = DecisionCell.CheapHighRisk,
            action = EventAction.Reduce,
            positionSizeBps = HALF_POSITION_BPS,
            hedge = HedgeKind.None,
            hedgeCostBps = spread,
            sectorOverrideApplied = false,
            justification = "Cheap on the DCF, and the market pays " +
                "${ratioText(pre.riskRatioBps)} this ticker's own reaction. The put spread costs " +
                "${percentText(spread)} of the position, over the ${percentText(HEDGE_COST_CAP_BPS)} " +
                "cap, so cut the size instead.",
        )
    }
    return EventDecision(
        cell = DecisionCell.CheapHighRisk,
        action = EventAction.Hedge,
        positionSizeBps = HALF_POSITION_BPS,
        hedge = HedgeKind.PutSpread,
        hedgeCostBps = spread,
        sectorOverrideApplied = false,
        justification = "Cheap on the DCF, and the market pays " +
            "${ratioText(pre.riskRatioBps)} this ticker's own reaction. Half size, or a put spread" +
            (spread?.let { " at ${percentText(it)} of the position" } ?: "") + ".",
    )
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

private fun missingText(pre: PreReport, risk: EventRisk): String = when {
    risk == EventRisk.Unknown && pre.impliedMoveBps == null ->
        "No option chain for this expiry, so the report carries no priced move yet."
    risk == EventRisk.Unknown ->
        "No settled reaction of this ticker yet, so the priced move has nothing to be measured against."
    else -> "No fair value for this ticker yet."
}

private fun ratioText(riskRatioBps: Int?): String {
    var ratio = (riskRatioBps ?: 0) / 10_000.0
    return "${(ratio * 10).toInt() / 10.0}x"
}

private const val FULL_POSITION_BPS = 10_000
private const val HALF_POSITION_BPS = 5_000
