package com.discountscreener.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ConfidenceBand {
    Low,
    Provisional,
    High,
}

@Serializable
enum class QualificationStatus {
    Qualified,
    Unprofitable,
    GapTooSmall,
}

@Serializable
enum class ExternalSignalStatus {
    Missing,
    Stale,
    Supportive,
    Divergent,
}

@Serializable
enum class AlertKind {
    EnteredQualified,
    ExitedQualified,
    ConfidenceUpgraded,
}

@Serializable
enum class ChartRange {
    Day,
    Week,
    Month,
    Year,
    FiveYears,
    TenYears,
}

@Serializable
enum class DcfSignal {
    Opportunity,
    Fair,
    Expensive,
}

@Serializable
enum class OpportunityScoringModel {
    Legacy,
    Aggressive,
    AggressiveV2,
}

@Serializable
enum class TrendSignal {
    Improving,
    Stable,
    Deteriorating,
    Mixed,
    Volatile,
    InsufficientData,
}

@Serializable
enum class ConfidenceLevel {
    Unavailable,
    Low,
    Medium,
    High,
}

@Serializable
enum class ProvenanceState {
    Live,
    Restored,
    Stale,
    Unavailable,
    ParseUncertain,
    ProviderUncertain,
}

@Serializable
data class Provenance(
    val source: String? = null,
    val asOfEpochSeconds: Long? = null,
    val state: ProvenanceState,
) {
    val isAvailable: Boolean
        get() = state != ProvenanceState.Unavailable && !source.isNullOrBlank()
}

@Serializable
enum class EvidenceStatus {
    Available,
    Unavailable,
    Stale,
    Sparse,
    ParseUncertain,
    ProviderUncertain,
}

@Serializable
enum class EvidenceDirection {
    Positive,
    Neutral,
    Negative,
    Unavailable,
}

@Serializable
data class PerformanceMetricEvidence(
    val id: String,
    val label: String,
    val status: EvidenceStatus,
    val direction: EvidenceDirection,
    val valueCents: Long? = null,
    val valueBps: Int? = null,
    val valueHundredths: Int? = null,
    val valueMillis: Int? = null,
    val detail: String? = null,
    val provenance: Provenance,
) {
    init {
        require(id.isNotBlank()) { "Evidence id is required." }
        require(label.isNotBlank()) { "Evidence label is required." }
        if (status == EvidenceStatus.Available) {
            require(provenance.isAvailable) { "Available evidence requires available provenance." }
        }
        if (status == EvidenceStatus.Unavailable) {
            require(direction == EvidenceDirection.Unavailable) {
                "Unavailable evidence must use the unavailable direction."
            }
        }
    }
}

@Serializable
enum class ScorecardSectionKind {
    Growth,
    Profitability,
    CashConversion,
    BalanceSheet,
    Valuation,
    Sentiment,
    Confidence,
}

@Serializable
data class PerformanceScorecardSection(
    val kind: ScorecardSectionKind,
    val evidence: List<PerformanceMetricEvidence>,
) {
    init {
        require(evidence.isNotEmpty()) { "Scorecard section requires at least one evidence row." }
    }
}

@Serializable
enum class RiskSeverity {
    Info,
    Warning,
    Critical,
}

@Serializable
data class RiskFlag(
    val id: String,
    val severity: RiskSeverity,
    val title: String,
    val evidenceIds: List<String>,
    val detail: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Risk id is required." }
        require(title.isNotBlank()) { "Risk title is required." }
        if (severity != RiskSeverity.Info) {
            require(evidenceIds.isNotEmpty()) { "Warning and critical risks require evidence references." }
        }
    }
}

@Serializable
enum class DecisionReadiness {
    ReadyForReview,
    NeedsManualThesisCheck,
    TooSparseToJudge,
    BlockedByUnavailableData,
}

@Serializable
data class PerformanceLens(
    val trajectory: TrendSignal,
    val confidence: ConfidenceLevel,
    val provenance: Provenance,
    val sections: List<PerformanceScorecardSection>,
    val riskFlags: List<RiskFlag>,
    val decisionReadiness: DecisionReadiness,
) {
    init {
        require(sections.isNotEmpty()) { "Performance lens requires at least one scorecard section." }
        if (confidence == ConfidenceLevel.High) {
            require(hasAvailableEvidence()) { "High confidence requires at least one available evidence row." }
        }
        if (decisionReadiness != DecisionReadiness.TooSparseToJudge) {
            require(provenance.isAvailable) { "Decision readiness requires available provenance." }
        }
    }

    private fun hasAvailableEvidence(): Boolean =
        sections.any { section -> section.evidence.any { it.status == EvidenceStatus.Available } }
}

@Serializable
data class ViewFilter(
    val query: String = "",
    val watchlistOnly: Boolean = false,
)

@Serializable
data class MarketSnapshot(
    val symbol: String,
    val companyName: String? = null,
    val profitable: Boolean,
    val marketPriceCents: Long,
    val intrinsicValueCents: Long,
)

@Serializable
data class ExternalValuationSignal(
    val symbol: String,
    val fairValueCents: Long,
    val ageSeconds: Long,
    val lowFairValueCents: Long? = null,
    val highFairValueCents: Long? = null,
    val analystOpinionCount: Int? = null,
    val recommendationMeanHundredths: Int? = null,
    val strongBuyCount: Int? = null,
    val buyCount: Int? = null,
    val holdCount: Int? = null,
    val sellCount: Int? = null,
    val strongSellCount: Int? = null,
    val weightedFairValueCents: Long? = null,
    val weightedAnalystCount: Int? = null,
)

@Serializable
data class FundamentalSnapshot(
    val symbol: String,
    val sectorKey: String? = null,
    val sectorName: String? = null,
    val industryKey: String? = null,
    val industryName: String? = null,
    val marketCapDollars: Long? = null,
    val sharesOutstanding: Long? = null,
    val trailingPeHundredths: Int? = null,
    val forwardPeHundredths: Int? = null,
    val priceToBookHundredths: Int? = null,
    val returnOnEquityBps: Int? = null,
    val ebitdaDollars: Long? = null,
    val enterpriseValueDollars: Long? = null,
    val enterpriseToEbitdaHundredths: Int? = null,
    val totalDebtDollars: Long? = null,
    val totalCashDollars: Long? = null,
    val debtToEquityHundredths: Int? = null,
    val freeCashFlowDollars: Long? = null,
    val operatingCashFlowDollars: Long? = null,
    val betaMillis: Int? = null,
    val trailingEpsCents: Long? = null,
    val earningsGrowthBps: Int? = null,
) {
    fun hasAnyValues(): Boolean = listOf(
        sectorKey,
        sectorName,
        industryKey,
        industryName,
        marketCapDollars,
        sharesOutstanding,
        trailingPeHundredths,
        forwardPeHundredths,
        priceToBookHundredths,
        returnOnEquityBps,
        ebitdaDollars,
        enterpriseValueDollars,
        enterpriseToEbitdaHundredths,
        totalDebtDollars,
        totalCashDollars,
        debtToEquityHundredths,
        freeCashFlowDollars,
        operatingCashFlowDollars,
        betaMillis,
        trailingEpsCents,
        earningsGrowthBps,
    ).any { it != null }
}

@Serializable
data class CandidateRow(
    val symbol: String,
    val marketPriceCents: Long,
    val intrinsicValueCents: Long,
    val gapBps: Int,
    val upsideBps: Int = gapBps,
    val isQualified: Boolean,
    val confidence: ConfidenceBand,
    val companyName: String? = null,
)

@Serializable
data class TapeEvent(
    val symbol: String,
    val gapBps: Int,
    val isQualified: Boolean,
    val confidence: ConfidenceBand,
)

@Serializable
data class AlertEvent(
    val symbol: String,
    val kind: AlertKind,
    val sequence: Int,
)

@Serializable
data class PriceHistoryPoint(
    val sequence: Int,
    val marketPriceCents: Long,
)

@Serializable
data class SymbolDetail(
    val symbol: String,
    val profitable: Boolean,
    val marketPriceCents: Long,
    val intrinsicValueCents: Long,
    val gapBps: Int,
    val upsideBps: Int = gapBps,
    val minimumGapBps: Int,
    val qualification: QualificationStatus,
    val externalStatus: ExternalSignalStatus,
    val externalSignalFairValueCents: Long? = null,
    val externalSignalLowFairValueCents: Long? = null,
    val externalSignalHighFairValueCents: Long? = null,
    val weightedExternalSignalFairValueCents: Long? = null,
    val weightedAnalystCount: Int? = null,
    val externalSignalGapBps: Int? = null,
    val externalSignalAgeSeconds: Long? = null,
    val externalSignalMaxAgeSeconds: Long,
    val analystOpinionCount: Int? = null,
    val recommendationMeanHundredths: Int? = null,
    val strongBuyCount: Int? = null,
    val buyCount: Int? = null,
    val holdCount: Int? = null,
    val sellCount: Int? = null,
    val strongSellCount: Int? = null,
    val fundamentals: FundamentalSnapshot? = null,
    val confidence: ConfidenceBand,
    val lastSequence: Int,
    val updateCount: Int,
    val isWatched: Boolean,
    val companyName: String? = null,
)

@Serializable
data class HistoricalCandle(
    val epochSeconds: Long,
    val openCents: Long,
    val highCents: Long,
    val lowCents: Long,
    val closeCents: Long,
    val volume: Long,
)

@Serializable
data class PricingCandle(
    val symbol: String,
    val range: ChartRange,
    val candle: HistoricalCandle,
)

@Serializable
data class AnnualReportedValue(
    val asOfDate: String,
    val value: Double,
)

@Serializable
data class FundamentalTimeseries(
    val freeCashFlow: List<AnnualReportedValue> = emptyList(),
    val operatingCashFlow: List<AnnualReportedValue> = emptyList(),
    val capitalExpenditure: List<AnnualReportedValue> = emptyList(),
    val dilutedAverageShares: List<AnnualReportedValue> = emptyList(),
    val interestExpense: List<AnnualReportedValue> = emptyList(),
    val pretaxIncome: List<AnnualReportedValue> = emptyList(),
    val taxRateForCalcs: List<AnnualReportedValue> = emptyList(),
    val netIncome: List<AnnualReportedValue> = emptyList(),
)

@Serializable
enum class DcfSource {
    YahooFinance,
    SecEdgar,
}

@Serializable
data class DcfSourceCandidate(
    val source: DcfSource,
    val timeseries: FundamentalTimeseries? = null,
    val analysis: DcfAnalysis? = null,
)

@Serializable
data class DcfSourceSelection(
    val selectedSource: DcfSource? = null,
    val timeseries: FundamentalTimeseries? = null,
    val analysis: DcfAnalysis? = null,
)

@Serializable
data class DcfAnalysis(
    val bearIntrinsicValueCents: Long,
    val baseIntrinsicValueCents: Long,
    val bullIntrinsicValueCents: Long,
    val waccBps: Int,
    val baseGrowthBps: Int,
    val netDebtDollars: Long,
    val source: DcfSource? = null,
    val sourceFingerprint: String? = null,
)

@Serializable
data class ChartRangeSummary(
    val range: ChartRange,
    val capturedAt: Long,
    val candleCount: Int,
    val latestCloseCents: Long? = null,
    val ema20Cents: Long? = null,
    val ema50Cents: Long? = null,
    val ema200Cents: Long? = null,
    val macdCents: Long? = null,
    val signalCents: Long? = null,
    val histogramCents: Long? = null,
)

@Serializable
data class OpportunityRow(
    val symbol: String,
    val marketPriceCents: Long,
    val intrinsicValueCents: Long,
    val gapBps: Int,
    val upsideBps: Int = gapBps,
    val confidence: ConfidenceBand,
    val isWatched: Boolean,
    val fundamentalsScore: Int? = null,
    val technicalScore: Int? = null,
    val forecastScore: Int? = null,
    val compositeScore: Int,
    val coverageCount: Int,
    val fundamentalsSignals: List<String> = emptyList(),
    val technicalSignals: List<String> = emptyList(),
    val forecastSignals: List<String> = emptyList(),
    val companyName: String? = null,
)

@Serializable
data class SymbolRevision(
    val symbol: String,
    val evaluatedAtEpochSeconds: Long,
    val detail: SymbolDetail,
    val chartSummaries: Map<ChartRange, ChartRangeSummary> = emptyMap(),
    val dcfAnalysis: DcfAnalysis? = null,
)

@Serializable
data class IssueRecord(
    val key: String,
    val title: String,
    val detail: String,
    val severity: String,
    val active: Boolean,
    val count: Int,
    val lastSeenEpochSeconds: Long,
)

@Serializable
data class PersistedSymbolState(
    val symbol: String,
    val snapshot: MarketSnapshot? = null,
    val externalSignal: ExternalValuationSignal? = null,
    val fundamentals: FundamentalSnapshot? = null,
    val lastSequence: Int = 0,
    val updateCount: Int = 0,
    val priceHistory: List<PriceHistoryPoint> = emptyList(),
    val dcfAnalysis: DcfAnalysis? = null,
)

@Serializable
data class PersistedReportState(
    val trackedSymbols: List<String> = emptyList(),
    val watchlist: List<String> = emptyList(),
    val symbolStates: List<PersistedSymbolState> = emptyList(),
    val revisions: Map<String, List<SymbolRevision>> = emptyMap(),
    val chartCache: Map<String, List<HistoricalCandle>> = emptyMap(),
    val issues: List<IssueRecord> = emptyList(),
    val lastPersistedAtEpochSeconds: Long? = null,
)

@Serializable
enum class EstimateScenario {
    BearDcf,
    BaseDcf,
    BullDcf,
    AnalystLow,
    AnalystHigh,
}

@Serializable
data class ScenarioEstimate(
    val scenario: EstimateScenario,
    val weightedPriceCents: Long,
    val coverageCount: Int,
    val impliedUpsideBps: Int,
)

@Serializable
enum class DcfCoverageStatus {
    Unavailable,
    LowConfidence,
    Partial,
    Provisional,
    Ready,
}

@Serializable
data class DcfSourceDistribution(
    val yahooCount: Int = 0,
    val secCount: Int = 0,
    val unknownCount: Int = 0,
    val unavailableCount: Int = 0,
)

@Serializable
data class DcfCoverageSummary(
    val totalEligibleSymbols: Int = 0,
    val coveredSymbols: Int = 0,
    val coverageBps: Int = 0,
    val status: DcfCoverageStatus = DcfCoverageStatus.Unavailable,
    val sourceDistribution: DcfSourceDistribution = DcfSourceDistribution(),
)

@Serializable
data class IndexEstimatesReport(
    val profileName: String,
    val currentWeightedPriceCents: Long,
    val totalSymbols: Int,
    val scenarios: List<ScenarioEstimate>,
    val computedAtEpochSeconds: Long,
    val dcfCoverage: DcfCoverageSummary = DcfCoverageSummary(),
)
