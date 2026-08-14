package com.discountscreener.core.plan

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ValuationModel

enum class ZoneConfidence {
    Low,
    Med,
    High,
}

enum class ZoneComponentKind {
    Support,
    Fib,
    AtrBand,
    Bb,
    Intrinsic,
    Dcf,
    AnalystLow,
    Ema,
}

enum class TimingMethod {
    EmpiricalTouches,
    AtrDistance,
    Hybrid,
    Unavailable,
}

enum class PathMotiveCode {
    Extension,
    FarFromSupport,
    RsiRich,
    RsiWashed,
    AboveValue,
    BelowValue,
    RegimeRisk,
    EarningsSoon,
    TrendAgainst,
    WeakForecast,
    NearZone,
    InZone,
}

enum class MotiveSeverity {
    Low,
    Med,
    High,
}

data class ZoneComponent(
    val kind: ZoneComponentKind,
    val priceCents: Long,
    val weightBps: Int,
)

data class PathMotive(
    val code: PathMotiveCode,
    val severity: MotiveSeverity,
    val metricLabel: String,
)

data class PriceZone(
    val lowCents: Long,
    val highCents: Long,
)

data class PathTiming(
    val expectedSessionsToZone: Int? = null,
    val pTouch5d: Int? = null,
    val pTouch20d: Int? = null,
    val pTouch60d: Int? = null,
    val method: TimingMethod = TimingMethod.Unavailable,
)

data class PathInvalidation(
    val priceCents: Long? = null,
    val sessionBudget: Int? = null,
    val reason: String = "",
)

data class CompactPricePath(
    val zoneLowCents: Long? = null,
    val zoneHighCents: Long? = null,
    val zoneConfidence: ZoneConfidence? = null,
    val pTouch20d: Int? = null,
    val expectedSessions: Int? = null,
    val invalidationCents: Long? = null,
    val riskCodes: List<PathMotiveCode> = emptyList(),
    val supportCodes: List<PathMotiveCode> = emptyList(),
    val timingMethod: TimingMethod = TimingMethod.Unavailable,
)

data class PricePathEstimate(
    val zone: PriceZone? = null,
    val zoneConfidence: ZoneConfidence = ZoneConfidence.Low,
    val zoneComponents: List<ZoneComponent> = emptyList(),
    val pathRisks: List<PathMotive> = emptyList(),
    val pathSupports: List<PathMotive> = emptyList(),
    val adversePriceCents: Long? = null,
    val baseZoneMidCents: Long? = null,
    val timing: PathTiming = PathTiming(),
    val invalidation: PathInvalidation = PathInvalidation(),
)

data class PricePathDaily(
    val ema50Cents: Long? = null,
    val ema200Cents: Long? = null,
    val rsi: Double? = null,
    val bbLowerCents: Long? = null,
    val high52wCents: Long? = null,
    val low52wCents: Long? = null,
    val atrCents: Long? = null,
)

data class PricePathInput(
    val marketPriceCents: Long,
    val streetFairValueCents: Long = 0,
    val dcfValueCents: Long? = null,
    val analystLowCents: Long? = null,
    val gapBps: Int? = null,
    val daily: PricePathDaily? = null,
    val candles: List<com.discountscreener.core.model.HistoricalCandle> = emptyList(),
    val nextEarningsEpoch: Long? = null,
    val nowEpoch: Long = 0,
    val regimeRisk: Boolean = false,
    val forecastScore: Int? = null,
    val technicalScore: Int? = null,
)

/** DCF dollars enter the zone only when the model is eligible for that class. */
fun eligibleDcfAnchorCents(analysis: DcfAnalysis?): Long? {
    if (analysis == null) return null
    if (analysis.baseIntrinsicValueCents <= 0L) return null
    return when (analysis.businessClass) {
        BusinessClass.NotEligible,
        BusinessClass.Unclassified,
        -> null
        BusinessClass.FinancialServices ->
            if (analysis.model == ValuationModel.ResidualIncomeEquity) {
                analysis.baseIntrinsicValueCents
            } else {
                null
            }
        BusinessClass.OperatingNonFinancial ->
            if (analysis.model == ValuationModel.None) null else analysis.baseIntrinsicValueCents
    }
}

fun publishableP20(method: TimingMethod, pTouch20d: Int?): Int? {
    if (pTouch20d == null) return null
    return when (method) {
        TimingMethod.EmpiricalTouches,
        TimingMethod.Hybrid,
        -> pTouch20d
        TimingMethod.AtrDistance,
        TimingMethod.Unavailable,
        -> null
    }
}
