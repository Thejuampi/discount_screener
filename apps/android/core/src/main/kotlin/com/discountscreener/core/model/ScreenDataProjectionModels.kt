package com.discountscreener.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ScreenDataProjectionRequest(
    val profile: ProjectionProfileFacts = ProjectionProfileFacts(),
    val route: ProjectionRoute = ProjectionRoute(),
    val nowEpochSeconds: Long = 0L,
    val trackedSymbols: List<String> = emptyList(),
    val watchlistSymbols: Set<String> = emptySet(),
    val detailsBySymbol: Map<String, SymbolDetail> = emptyMap(),
    val candidateRows: List<CandidateRow> = emptyList(),
    val opportunityDecisionFactsBySymbol: Map<String, ProjectedOpportunityDecisionFacts> = emptyMap(),
    val chartCandles: Map<SymbolRangeKey, List<HistoricalCandle>> = emptyMap(),
    val chartSummariesBySymbol: Map<String, Map<ChartRange, ChartRangeSummary>> = emptyMap(),
    val dcfBySymbol: Map<String, DcfAnalysis> = emptyMap(),
    val analystTargetStatisticBySymbol: Map<String, ProjectedAnalystTargetStatistic> = emptyMap(),
    val revisionsBySymbol: Map<String, List<SymbolRevision>> = emptyMap(),
    val alertsBySymbol: Map<String, List<AlertEvent>> = emptyMap(),
    val issues: List<IssueRecord> = emptyList(),
    val symbolStateBySymbol: Map<String, ProjectionSymbolState> = emptyMap(),
    val baselines: ProjectionComparisonBaselines = ProjectionComparisonBaselines(),
)

@Serializable
data class ProjectedOpportunityDecisionFacts(
    val compositeScore: Int? = null,
)

@Serializable
data class ProjectionProfileFacts(
    val currentProfile: String = "",
    val availableProfiles: List<String> = emptyList(),
)

@Serializable
data class ProjectionRoute(
    val filter: ViewFilter = ViewFilter(),
    val selectedSymbol: String? = null,
    val selectedRange: ChartRange = ChartRange.Month,
    val replayOffset: Int = 0,
    val opportunityScoringModel: OpportunityScoringModel = OpportunityScoringModel.Legacy,
    val volumeProfileBinCount: Int = 24,
) {
    init {
        require(selectedSymbol == null || selectedSymbol.isNotBlank()) { "Selected symbol cannot be blank." }
        require(replayOffset >= 0) { "Replay offset cannot be negative." }
        require(volumeProfileBinCount > 0) { "Volume profile bin count must be positive." }
    }
}

@Serializable
data class ProjectionSymbolState(
    val symbol: String,
    val providerCategory: ProjectedProviderCategory = ProjectedProviderCategory.Unavailable,
    val provenanceState: ProjectedProvenanceState = ProjectedProvenanceState.Unavailable,
    val capturedAtEpochSeconds: Long? = null,
    val stale: Boolean = false,
) {
    init {
        require(symbol.isNotBlank()) { "Projection symbol state symbol is required." }
    }
}

@Serializable
data class ProjectionComparisonBaselines(
    val previousRankBySymbol: Map<String, Int> = emptyMap(),
    val previousFairValueCentsBySymbol: Map<String, Long> = emptyMap(),
)

@Serializable
data class SymbolRangeKey(
    val symbol: String,
    val range: ChartRange,
) {
    init {
        require(symbol.isNotBlank()) { "Symbol range key symbol is required." }
    }
}

@Serializable
data class ProjectedDashboardData(
    val trackedRows: List<ProjectedTrackedRow> = emptyList(),
    val watchlistRows: List<ProjectedTrackedRow> = emptyList(),
    val opportunityRows: List<ProjectedOpportunityRow> = emptyList(),
    val candidateRows: List<CandidateRow> = emptyList(),
    val selectedDetail: ProjectedDetailData? = null,
    val estimates: ProjectedEstimatesData = ProjectedEstimatesData(),
    val providerState: ProjectedProviderState = ProjectedProviderState(),
) {
    companion object {
        fun empty(): ProjectedDashboardData = ProjectedDashboardData()
    }
}

@Serializable
data class ProjectedTrackedRow(
    val symbol: String,
    val detail: SymbolDetail? = null,
    val marketPriceCents: Long? = null,
    val fairValueAnchor: ProjectedFairValueAnchor = ProjectedFairValueAnchor.unavailable(),
    val upsideBps: Int? = null,
    val confidence: ProjectedConfidence = ProjectedConfidence.Unavailable,
    val freshness: ProjectedRowFreshness = ProjectedRowFreshness.Loading,
    val trustSignal: ProjectedTrustSignal? = null,
    val decision: ProjectedRowDecision? = null,
    val explanation: ProjectedRowExplanation? = null,
    val quantLensSummary: QuantLensRowSummary? = null,
) {
    init {
        require(symbol.isNotBlank()) { "Projected tracked row symbol is required." }
    }
}

@Serializable
data class ProjectedOpportunityRow(
    val symbol: String,
    val candidateRow: CandidateRow,
    val fairValueAnchor: ProjectedFairValueAnchor = ProjectedFairValueAnchor.unavailable(),
    val upsideBps: Int? = null,
    val confidence: ProjectedConfidence = ProjectedConfidence.Unavailable,
    val freshness: ProjectedRowFreshness = ProjectedRowFreshness.Loading,
    val trustSignal: ProjectedTrustSignal? = null,
    val decision: ProjectedRowDecision? = null,
    val quantLensSummary: QuantLensRowSummary? = null,
) {
    init {
        require(symbol.isNotBlank()) { "Projected opportunity row symbol is required." }
    }
}

@Serializable
data class ProjectedDetailData(
    val symbol: String,
    val detail: SymbolDetail,
    val fairValueAnchor: ProjectedFairValueAnchor = ProjectedFairValueAnchor.unavailable(),
    val valuationAnchors: List<ProjectedValuationAnchor> = emptyList(),
    val chart: ProjectedChartData = ProjectedChartData(),
    val revisions: List<SymbolRevision> = emptyList(),
    val alerts: List<AlertEvent> = emptyList(),
) {
    init {
        require(symbol.isNotBlank()) { "Projected detail symbol is required." }
    }
}

@Serializable
data class ProjectedEstimatesData(
    val report: IndexEstimatesReport = IndexEstimatesReport(
        profileName = "",
        currentWeightedPriceCents = 0L,
        totalSymbols = 0,
        scenarios = emptyList(),
        computedAtEpochSeconds = 0L,
    ),
)

@Serializable
data class ProjectedProviderState(
    val category: ProjectedProviderCategory = ProjectedProviderCategory.Unavailable,
    val statusCopy: String = category.defaultStatusCopy(),
    val retryable: Boolean = category.defaultRetryable(),
    val computedAtEpochSeconds: Long = 0L,
    val issues: List<IssueRecord> = emptyList(),
    val affectedSymbols: List<String> = emptyList(),
)

@Serializable
data class ProjectedChartData(
    val range: ChartRange = ChartRange.Month,
    val candles: List<HistoricalCandle> = emptyList(),
    val summary: ChartRangeSummary? = null,
    val analysis: ProjectedChartAnalysis = ProjectedChartAnalysis(),
)

@Serializable
data class ProjectedChartAnalysis(
    val status: ProjectedChartStatus = ProjectedChartStatus.Unavailable,
    val replayWindow: ProjectedReplayWindow = ProjectedReplayWindow(),
    val price: ProjectedPriceChartAnalysis? = null,
    val volume: ProjectedVolumeChartAnalysis? = null,
    val volumeProfile: ProjectedVolumeProfileAnalysis? = null,
    val macd: ProjectedMacdChartAnalysis? = null,
    val rsi: ProjectedRsiChartAnalysis? = null,
    val technicalSignals: List<ProjectedTechnicalSignal> = emptyList(),
)

@Serializable
enum class ProjectedChartStatus {
    Unavailable,
    SummaryOnly,
    Available,
}

@Serializable
data class ProjectedReplayWindow(
    val visibleCandles: List<HistoricalCandle> = emptyList(),
    val totalCandles: Int = 0,
    val replayOffset: Int = 0,
) {
    val visibleCount: Int = visibleCandles.size
    val isLive: Boolean = replayOffset == 0
}

@Serializable
data class ProjectedPriceChartAnalysis(
    val domain: ProjectedPriceDomain = ProjectedPriceDomain(),
    val latestCloseCents: Long? = null,
    val ema20: List<Double> = emptyList(),
    val ema50: List<Double> = emptyList(),
    val ema200: List<Double> = emptyList(),
    val latestEma20Cents: Long? = null,
    val latestEma50Cents: Long? = null,
    val latestEma200Cents: Long? = null,
)

@Serializable
data class ProjectedPriceDomain(
    val minValue: Float = 0f,
    val maxValue: Float = 0f,
) {
    val span: Float = (maxValue - minValue).coerceAtLeast(1f)
}

@Serializable
data class ProjectedVolumeChartAnalysis(
    val maxVolume: Long = 0L,
)

@Serializable
data class ProjectedVolumeProfileAnalysis(
    val bins: List<ProjectedVolumeProfileBin> = emptyList(),
    val maxBinVolume: Long = 0L,
)

@Serializable
data class ProjectedVolumeProfileBin(
    val upVolume: Long,
    val downVolume: Long,
) {
    val totalVolume: Long = upVolume + downVolume
}

@Serializable
data class ProjectedMacdChartAnalysis(
    val macdLine: List<Double> = emptyList(),
    val signalLine: List<Double> = emptyList(),
    val histogram: List<Double> = emptyList(),
    val latestMacdCents: Long? = null,
    val latestSignalCents: Long? = null,
    val latestHistogramCents: Long? = null,
)

@Serializable
data class ProjectedRsiChartAnalysis(
    val wilderRsi: List<Double> = emptyList(),
    val signalRsi: List<Double> = emptyList(),
    val slope: List<Double> = emptyList(),
    val acceleration: List<Double> = emptyList(),
    val latestWilderRsi: Double? = null,
    val latestSignalRsi: Double? = null,
    val latestSlope: Double? = null,
    val latestAcceleration: Double? = null,
)

@Serializable
data class ProjectedTechnicalSignal(
    val kind: ProjectedTechnicalSignalKind,
    val title: String,
    val meaning: String,
    val bias: ProjectedTechnicalSignalBias,
    val freshCross: Boolean,
)

@Serializable
enum class ProjectedTechnicalSignalKind {
    Ema20Ema50,
    Ema50Ema200,
    MacdSignal,
    RsiMomentum,
    RsiInflection,
}

@Serializable
enum class ProjectedTechnicalSignalBias {
    Bull,
    Bear,
}

@Serializable
data class ProjectedFairValueAnchor(
    val role: ProjectedFairValueRole,
    val valueCents: Long? = null,
    val displayLabel: String,
    val compactLabel: String,
    val sourceLabel: String,
    val provenanceState: ProjectedProvenanceState,
    val trustReason: String? = null,
    val canPopulateAnalystHistory: Boolean = false,
) {
    companion object {
        fun analyst(
            valueCents: Long,
            role: ProjectedFairValueRole,
            compactLabel: String,
            sourceLabel: String,
            provenanceState: ProjectedProvenanceState = ProjectedProvenanceState.Live,
        ): ProjectedFairValueAnchor = ProjectedFairValueAnchor(
            role = role,
            valueCents = valueCents,
            displayLabel = ProjectedFairValueLabels.ANALYST_FAIR_VALUE,
            compactLabel = compactLabel,
            sourceLabel = sourceLabel,
            provenanceState = provenanceState,
            canPopulateAnalystHistory = true,
        )

        fun model(
            valueCents: Long,
            sourceLabel: String,
            role: ProjectedFairValueRole = ProjectedFairValueRole.DcfBaseModel,
            compactLabel: String = "DCF model",
            provenanceState: ProjectedProvenanceState = ProjectedProvenanceState.Live,
            trustReason: String? = null,
        ): ProjectedFairValueAnchor = ProjectedFairValueAnchor(
            role = role,
            valueCents = valueCents,
            displayLabel = ProjectedFairValueLabels.MODEL_FAIR_VALUE,
            compactLabel = compactLabel,
            sourceLabel = sourceLabel,
            provenanceState = provenanceState,
            trustReason = trustReason,
            canPopulateAnalystHistory = false,
        )

        fun unavailable(): ProjectedFairValueAnchor = ProjectedFairValueAnchor(
            role = ProjectedFairValueRole.Unavailable,
            displayLabel = ProjectedFairValueLabels.FAIR_VALUE_UNAVAILABLE,
            compactLabel = "No fair-value source",
            sourceLabel = "No fair-value source",
            provenanceState = ProjectedProvenanceState.Unavailable,
        )
    }
}

object ProjectedFairValueLabels {
    const val ANALYST_FAIR_VALUE: String = "Analyst fair value"
    const val MODEL_FAIR_VALUE: String = "Model fair value"
    const val FAIR_VALUE_UNAVAILABLE: String = "Fair value unavailable"
}

@Serializable
enum class ProjectedFairValueRole {
    AnalystWeightedTarget,
    AnalystMedianTarget,
    AnalystMeanTarget,
    AnalystConsensusTarget,
    AnalystLowTarget,
    AnalystHighTarget,
    DcfBaseModel,
    UncertainDcfModel,
    RestoredDcfModel,
    SourceFreeModel,
    IntrinsicModel,
    Unavailable,
}

@Serializable
enum class ProjectedAnalystTargetStatistic {
    Median,
    Mean,
    Consensus,
}

@Serializable
data class ProjectedValuationAnchor(
    val kind: ProjectedValuationAnchorKind,
    val valueCents: Long,
    val label: String,
    val sourceLabel: String,
    val provenanceState: ProjectedProvenanceState,
) {
    init {
        require(valueCents > 0L) { "Projected valuation anchor value must be positive." }
        require(label.isNotBlank()) { "Projected valuation anchor label is required." }
        require(sourceLabel.isNotBlank()) { "Projected valuation anchor source label is required." }
    }
}

@Serializable
enum class ProjectedValuationAnchorKind {
    PrimaryFairValue,
    AnalystLowTarget,
    AnalystHighTarget,
    DcfBearModel,
    DcfBaseModel,
    DcfBullModel,
    IntrinsicModel,
}

@Serializable
enum class ProjectedConfidence {
    Unavailable,
    Low,
    Provisional,
    High,
}

fun ProjectedConfidence.lowerForProviderUncertainty(): ProjectedConfidence = when (this) {
    ProjectedConfidence.High -> ProjectedConfidence.Provisional
    ProjectedConfidence.Provisional -> ProjectedConfidence.Low
    ProjectedConfidence.Low -> ProjectedConfidence.Low
    ProjectedConfidence.Unavailable -> ProjectedConfidence.Unavailable
}

fun ConfidenceBand.toProjectedConfidence(): ProjectedConfidence = when (this) {
    ConfidenceBand.Low -> ProjectedConfidence.Low
    ConfidenceBand.Provisional -> ProjectedConfidence.Provisional
    ConfidenceBand.High -> ProjectedConfidence.High
}

@Serializable
enum class ProjectedProvenanceState {
    Live,
    Restored,
    Stale,
    Unavailable,
    ParseUncertain,
    ProviderUncertain,
    SourceUnknown,
    Disabled,
    NotEligible,
    Superseded,
}

@Serializable
enum class ProjectedProviderCategory {
    Live,
    Restored,
    Stale,
    ProviderUncertain,
    ParseUncertain,
    Unavailable,
    NotEligible,
    Disabled,
    Superseded,
    SourceUnknown,
}

fun ProjectedProviderCategory.defaultStatusCopy(): String = when (this) {
    ProjectedProviderCategory.Live -> "Live provider data"
    ProjectedProviderCategory.Restored -> "Restored from saved data"
    ProjectedProviderCategory.Stale -> "Stale provider data"
    ProjectedProviderCategory.ProviderUncertain -> "Sources disagree; confidence lowered"
    ProjectedProviderCategory.ParseUncertain -> "Provider format uncertain"
    ProjectedProviderCategory.Unavailable -> "Provider unavailable; retry pending"
    ProjectedProviderCategory.NotEligible -> "Not eligible for DCF"
    ProjectedProviderCategory.Disabled -> "Provider disabled"
    ProjectedProviderCategory.Superseded -> "Refresh superseded"
    ProjectedProviderCategory.SourceUnknown -> "Saved value has no provider source"
}

fun ProjectedProviderCategory.defaultRetryable(): Boolean = when (this) {
    ProjectedProviderCategory.ProviderUncertain,
    ProjectedProviderCategory.ParseUncertain,
    ProjectedProviderCategory.Unavailable,
    ProjectedProviderCategory.Stale -> true
    ProjectedProviderCategory.Live,
    ProjectedProviderCategory.Restored,
    ProjectedProviderCategory.NotEligible,
    ProjectedProviderCategory.Disabled,
    ProjectedProviderCategory.Superseded,
    ProjectedProviderCategory.SourceUnknown -> false
}

@Serializable
data class ProjectedTrustSignal(
    val kind: ProjectedTrustSignalKind,
    val label: String,
)

@Serializable
enum class ProjectedTrustSignalKind {
    SourceUncertain,
    SourceUnknown,
    NoAnalystTarget,
    ModelValue,
    ThinCoverage,
    CoverageUnknown,
}

@Serializable
enum class ProjectedRowDecision {
    Act,
    Watch,
    Avoid,
}

@Serializable
enum class ProjectedRowFreshness {
    Loading,
    Updating,
    Updated,
    Restored,
    Stale,
    Issue,
}

@Serializable
data class ProjectedRowExplanation(
    val kind: ProjectedRowExplanationKind,
    val label: String? = null,
)

@Serializable
enum class ProjectedRowExplanationKind {
    PriceMoved,
    TargetChanged,
    RelativeReRank,
    CombinedMove,
    NoBaseline,
    NoMeaningfulChange,
}
