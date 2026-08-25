package com.discountscreener.core.plan

import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.HistoricalCandle

enum class DipLane {
    Now,
    Almost,
    Out,
}

enum class MacdPhase {
    Imminent,
    Turning,
    Flipped,
    Distant,
    Unavailable,
}

data class MacdTape(
    val histogram: Double,
    val histSlope: Double,
    val histAccel: Double,
    val macdPhase: MacdPhase,
)

enum class MacdHorizonSense {
    DipTurn,
    LeftoverFade,
    CrossFresh,
}

enum class DipModelQuality {
    Solid,
    Soft,
}

data class DipTape(
    val atrCents: Long,
    val high20dCents: Long,
    val lastCloseCents: Long,
    val dipAtrUnits: Double,
    val rsi: Double,
    val rsiSlope: Double,
    val rsiAccel: Double,
    val histogram: Double,
    val histSlope: Double,
    val histAccel: Double,
    val macdPhase: MacdPhase,
    val deathCross: Boolean,
)

data class DipRowInput(
    val symbol: String,
    val companyName: String? = null,
    val fundamentalsScore: Int?,
    val marketPriceCents: Long,
    val streetFairValueCents: Long,
    val analystCoverageCount: Int? = null,
    val technicalSignals: List<String> = emptyList(),
    val candles: List<HistoricalCandle> = emptyList(),
    val horizonCandles: List<HistoricalCandle> = emptyList(),
    val dcf: DcfAnalysis? = null,
)

data class DipSetup(
    val symbol: String,
    val companyName: String?,
    val lane: DipLane,
    val refuseReasons: List<String>,
    val tags: List<String>,
    val headline: String,
    val evidence: List<String>,
    val dipAtrUnits: Double?,
    val rsi: Double?,
    val macdPhase: MacdPhase,
    val macdHorizonScore: Int = 0,
    val barsSinceCross: Int? = null,
    val streetUpsideBps: Int?,
    val fundamentalsScore: Int?,
    val valuationRelation: AnchorRelation,
    val modelQuality: DipModelQuality?,
    val modelLabel: String?,
    val marketPriceCents: Long,
    val spark: List<Long>,
)
