package com.discountscreener.core.regime

/** Public types for the market regime engine. English copy lives in [enrichRegime]. */

const val REGIME_VERSION: Int = 2

/** One reason a pillar reads the way it does. [contribution] runs roughly −100..+100. */
data class RegimeSignal(
    val id: String,
    val label: String,
    val contribution: Int,
    val detail: String? = null,
    /** Short human hint, populated only when the contribution is material. */
    val hint: String? = null,
)

data class RegimePillar(
    val id: String,
    val name: String,
    /** −100..+100. For the volatility pillar, higher means more stress, so more hostile. */
    val score: Int,
    /** 0..10000 basis points. */
    val confidenceBps: Int,
    /** Weight after confidence renormalization, in bps of the total environment weight. */
    val weightUsedBps: Int,
    val signals: List<RegimeSignal>,
    val stale: Boolean,
    val interpretation: String,
    /** bullish | bearish | neutral | opportunity | caution */
    val tone: String,
    /** 0..100 radar radius, edge = better. Volatility is inverted; sentiment reads as contrarian. */
    val radarRadius: Int,
)

/** What a pillar computed, before it is dressed up as a [RegimePillar] for display. */
data class PillarResult(
    val score: Int = 0,
    val confidenceBps: Int = 0,
    val signals: List<RegimeSignal> = emptyList(),
    val stale: Boolean = false,
)

data class BreadthSnapshot(
    val aboveMa200Pct: Double? = null,
    val aboveMa50Pct: Double? = null,
    val sample: Int = 0,
    val pctRsiAbove50: Double? = null,
    val pctRsiAbove70: Double? = null,
    val pctRsiBelow30: Double? = null,
    val pctMacdPositive: Double? = null,
    val pctNear52wHigh: Double? = null,
    val pctNear52wLow: Double? = null,
    val medianPos52w: Double? = null,
)

data class CrossAssetSnapshot(
    val creditScore: Int? = null,
    val equityBondScore: Int? = null,
    val leadershipScore: Int? = null,
    val smallLargeScore: Int? = null,
    val signals: List<RegimeSignal> = emptyList(),
    val confidenceBps: Int = 0,
)

data class VolSnapshot(
    val vix: Double? = null,
    val vixPercentile1y: Double? = null,
    val vixTermRatio: Double? = null,
    val vixState: String = "Unknown",
    val realizedVolPct: Double? = null,
    val stressScore: Int = 0,
    val confidenceBps: Int = 0,
    val signals: List<RegimeSignal> = emptyList(),
)

data class CnnFearGreed(
    val score: Double,
    val rating: String,
    val previousClose: Double? = null,
    val previous1Week: Double? = null,
    val fetchedAtEpoch: Long = 0L,
)

/**
 * The whole market reading. Defaults reproduce Rust's `Default for MarketRegime`, which is what a
 * cold start or a fully offline refresh resolves to — an explicit "Unknown" with a neutral
 * exposure, never a fabricated bullish or bearish call.
 */
data class MarketRegime(
    /** StrongBull | Bull | LateBull | Range | Correction | Bear | Capitulation | Snapback | Unknown */
    val primaryRegime: String = "Unknown",
    /** StrongRiskOn | RiskOn | Neutral | RiskOff | Crisis | Unknown */
    val environmentBand: String = "Unknown",
    val actionStance: String = "Unknown",
    /** Suggested equity exposure ceiling, 15..100. */
    val suggestedExposurePct: Int = 60,
    val cashBufferPct: Int = 20,
    /** New-risk sizing multiplier in bps (2500 = 0.25×, 12500 = 1.25×). */
    val newRiskMultiplierBps: Int = 10_000,
    /** Add-bias, −2..+2. */
    val addBias: Int = 0,
    val preferQuality: Boolean = false,
    val globalConfidenceBps: Int = 0,
    val environmentScore: Int = 0,
    val sentimentScore: Int = 0,
    val qualityScore: Int = 0,
    val pillars: List<RegimePillar> = emptyList(),
    val vix: Double? = null,
    val vixPercentile1y: Double? = null,
    val vixTermRatio: Double? = null,
    val vixState: String = "Unknown",
    val cnnFearGreed: Int? = null,
    val cnnFearGreedLabel: String? = null,
    val cnnFearGreedPrevClose: Double? = null,
    val breadthAboveMa200Pct: Double? = null,
    val breadthAboveMa50Pct: Double? = null,
    val breadthSample: Int = 0,
    val spyAboveMa200: Boolean? = null,
    val spyPriceCents: Long? = null,
    val spyMa200Cents: Long? = null,
    val spyDrawdownFromAthPct: Double? = null,
    val creditScore: Int? = null,
    val leadershipScore: Int? = null,
    val avgCorrMilli: Int? = null,
    val thesis: String = "",
    /** Longer multi-sentence reading: environment, sentiment, action. */
    val reading: String = "",
    val actionBullets: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val asOfEpoch: Long = 0L,
    val version: Int = REGIME_VERSION,
)
