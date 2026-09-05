package com.discountscreener.core.earnings

import kotlinx.serialization.Serializable

@Serializable
data class EarningsEventRecord(
    val pre: PreReport,
    val decision: EventDecision? = null,
    val post: PostReport? = null,
)

@Serializable
enum class ReportTiming {
    BeforeOpen,

    AfterClose,

    Unknown,
}

@Serializable
data class PreReport(
    val symbol: String,
    val reportEpochDay: Long,
    val timing: ReportTiming,
    val priceCents: Long,
    val dcfComputedOnEpochDay: Long? = null,
    val dcfFairValueCents: Long? = null,
    val impliedMoveBps: Int? = null,
    val eventImpliedMoveBps: Int? = null,
    val normalDailyMoveBps: Int? = null,
    val quoteSpreadBps: Int? = null,
    val expiryEpochDay: Long? = null,
    val forwardPriceCents: Long? = null,
    val strikeCents: Long? = null,
    val medianAbsoluteAbnormalReturnBps: Int? = null,
    val riskRatioBps: Int? = null,
    val consensusEpsCents: Long? = null,
    val consensusEpsLowCents: Long? = null,
    val consensusEpsHighCents: Long? = null,
    val analystCount: Int? = null,
    val consensusRevenueCents: Long? = null,
    val protectivePutCostBps: Int? = null,
    val putSpreadCostBps: Int? = null,
    val hedgeLongStrikeCents: Long? = null,
    val hedgeShortStrikeCents: Long? = null,
)

@Serializable
data class EventDecision(
    val cell: DecisionCell,
    val action: EventAction,
    val positionSizeBps: Int,
    val hedge: HedgeKind,
    val hedgeCostBps: Int?,
    val sectorOverrideApplied: Boolean,
    val justification: String,
)

@Serializable
enum class DecisionCell {
    ExpensiveHighRisk,
    ExpensiveNormalRisk,
    CheapHighRisk,
    CheapNormalRisk,

    Undecided,
}

@Serializable
enum class EventAction { Hold, Reduce, Exit, Hedge }

@Serializable
enum class HedgeKind { None, ProtectivePut, PutSpread }

@Serializable
data class PostReport(
    val epsActualCents: Long? = null,
    val surpriseScoreBps: Int? = null,
    val revenueActualCents: Long? = null,
    val revenueSurpriseBps: Int? = null,
    val stockReturnBps: Int? = null,
    val marketReturnBps: Int? = null,
    val marketBetaBps: Int? = null,
    val abnormalReturnBps: Int? = null,
    val reportedOnEpochDay: Long? = null,
)
