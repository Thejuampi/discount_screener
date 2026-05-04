package com.discountscreener.core.model

import kotlinx.serialization.Serializable

object QuantLensModelVersion {
    const val CURRENT: Int = 2
}

@Serializable
enum class QuantLensPrimaryStatus {
    Available,
    Partial,
    Provisional,
    Sparse,
    Insufficient,
    Unavailable,
}

@Serializable
enum class QuantLensFreshnessQualifier {
    Fresh,
    Stale,
    Restored,
    ProviderUncertain,
}

@Serializable
enum class QuantLensLensId {
    EvidenceStrength,
    ExpectedValueRange,
    CorrelationRisk,
    TrendReliability,
    SimilarSetups,
    HorizonContext,
}

@Serializable
enum class QuantLensHorizon {
    FiveMinutes,
    OneDay,
    ThreeMonths,
}

@Serializable
enum class QuantLensRowLabel {
    EvidenceStrong,
    EvidenceProvisional,
    EvidenceMixed,
    EvidenceSparse,
    EvidenceUnavailable,
    EvRange,
    EvSparse,
    EvUnavailable,
    CorrLow,
    CorrElevated,
    CorrHigh,
    CorrSparse,
    CorrUnavailable,
    TrendReliable,
    TrendModerate,
    TrendNoisy,
    TrendFlat,
    TrendSparse,
    TrendUnavailable,
    SimilarAvailable,
    SimilarSparse,
    SimilarUnavailable,
}

@Serializable
enum class EvidenceStrengthBand {
    Strong,
    Provisional,
    Mixed,
    Sparse,
    Unavailable,
}

@Serializable
enum class ExpectedValueRangeBand {
    ScenarioWeighted,
    ReferenceOnly,
    Sparse,
    Unavailable,
}

@Serializable
enum class ExpectedValueRangeSource {
    Dcf,
    Analyst,
}

@Serializable
enum class CorrelationRiskBand {
    Low,
    Elevated,
    High,
    Sparse,
    Unavailable,
}

@Serializable
enum class TrendReliabilityBand {
    Reliable,
    Moderate,
    Noisy,
    Flat,
    Insufficient,
    Unavailable,
}

@Serializable
enum class SimilarSetupsBand {
    Available,
    Sparse,
    Unavailable,
}

@Serializable
enum class QuantLensReasonCode {
    ScaffoldPending,
    MissingBaseSignal,
    MissingMarketPrice,
    MissingScenarioAnchors,
    InsufficientLocalHistory,
    InsufficientTrendSamples,
    InsufficientComparables,
    CompleteScenarioAnchors,
    HistoricalBaselineAvailable,
    MissingHorizonCandles,
    InsufficientHorizonSamples,
    InvalidHorizonClose,
}

@Serializable
data class QuantLensInput(
    val detail: SymbolDetail,
    val selectedRange: ChartRange,
    val inputFingerprint: String,
    val selectedCandlesByRange: Map<ChartRange, List<HistoricalCandle>> = emptyMap(),
    val chartSummaries: Map<ChartRange, ChartRangeSummary> = emptyMap(),
    val dcfAnalysis: DcfAnalysis? = null,
    val revisions: List<SymbolRevision> = emptyList(),
    val estimateHistory: List<IndexEstimatesReport> = emptyList(),
    val opportunityRows: List<OpportunityRow> = emptyList(),
    val comparableUniverse: List<QuantLensComparable> = emptyList(),
    val correlationSeries: List<QuantLensCorrelationSeries> = emptyList(),
    val scoringModel: OpportunityScoringModel,
    val scoringVersion: Int,
    val nowEpochSeconds: Long,
) {
    init {
        require(detail.symbol.isNotBlank()) { "Quant Lens input symbol is required." }
        require(inputFingerprint.isNotBlank()) { "Quant Lens input fingerprint is required." }
        require(scoringVersion >= 0) { "Quant Lens scoring version cannot be negative." }
    }
}

@Serializable
data class QuantLensReport(
    val symbol: String,
    val selectedRange: ChartRange,
    val computedAtEpochSeconds: Long,
    val modelVersion: Int,
    val inputFingerprint: String,
    val primaryStatus: QuantLensPrimaryStatus,
    val freshnessQualifier: QuantLensFreshnessQualifier? = null,
    val evidenceStrength: QuantLensEvidenceStrength,
    val expectedValueRange: QuantLensExpectedValueRange,
    val correlationRisk: QuantLensCorrelationRisk,
    val trendReliability: QuantLensTrendReliability,
    val horizonContext: QuantLensHorizonContext,
    val similarSetups: QuantLensSimilarSetups,
    val notices: List<QuantLensReasonCode> = emptyList(),
) {
    init {
        require(symbol.isNotBlank()) { "Quant Lens report symbol is required." }
        require(modelVersion > 0) { "Quant Lens model version must be positive." }
        require(inputFingerprint.isNotBlank()) { "Quant Lens report fingerprint is required." }
    }
}

@Serializable
data class QuantLensHorizonContext(
    val primaryStatus: QuantLensPrimaryStatus,
    val horizons: List<QuantLensHorizonBaseline>,
    val reasonCodes: List<QuantLensReasonCode>,
) {
    init {
        requireValidSection(primaryStatus, reasonCodes)
    }
}

@Serializable
data class QuantLensHorizonBaseline(
    val horizon: QuantLensHorizon,
    val primaryStatus: QuantLensPrimaryStatus,
    val sourceRange: ChartRange,
    val lagCandles: Int,
    val sampleCount: Int,
    val medianAbsoluteMoveBps: Int? = null,
    val p25AbsoluteMoveBps: Int? = null,
    val p75AbsoluteMoveBps: Int? = null,
    val reasonCodes: List<QuantLensReasonCode>,
) {
    init {
        requireValidSection(primaryStatus, reasonCodes)
        require(lagCandles > 0) { "lagCandles must be positive." }
        require(sampleCount >= 0) { "sampleCount cannot be negative." }
        requireBps("medianAbsoluteMoveBps", medianAbsoluteMoveBps, max = 100_000)
        requireBps("p25AbsoluteMoveBps", p25AbsoluteMoveBps, max = 100_000)
        requireBps("p75AbsoluteMoveBps", p75AbsoluteMoveBps, max = 100_000)
    }
}

@Serializable
data class QuantLensRowSummary(
    val symbol: String,
    val fingerprint: String,
    val lensStates: List<QuantLensLensRowState>,
) {
    init {
        require(symbol.isNotBlank()) { "Quant Lens row-summary symbol is required." }
        require(fingerprint.isNotBlank()) { "Quant Lens row-summary fingerprint is required." }
    }
}

@Serializable
data class QuantLensLensRowState(
    val lensId: QuantLensLensId,
    val primaryStatus: QuantLensPrimaryStatus,
    val band: String? = null,
    val label: QuantLensRowLabel? = null,
    val freshnessQualifier: QuantLensFreshnessQualifier? = null,
    val reasonCodes: List<QuantLensReasonCode>,
    val evLowUpsideBps: Int? = null,
    val evHighUpsideBps: Int? = null,
) {
    init {
        requireValidSection(primaryStatus, reasonCodes)
        requireBps("evLowUpsideBps", evLowUpsideBps, min = -100_000, max = 100_000)
        requireBps("evHighUpsideBps", evHighUpsideBps, min = -100_000, max = 100_000)
    }
}

@Serializable
data class QuantLensEvidenceStrength(
    val primaryStatus: QuantLensPrimaryStatus,
    val band: EvidenceStrengthBand,
    val freshnessQualifier: QuantLensFreshnessQualifier? = null,
    val strengthBps: Int? = null,
    val supportCount: Int = 0,
    val conflictCount: Int = 0,
    val neutralCount: Int = 0,
    val reasonCodes: List<QuantLensReasonCode>,
) {
    init {
        requireValidSection(primaryStatus, reasonCodes)
        requireBps("strengthBps", strengthBps)
        require(supportCount >= 0) { "supportCount cannot be negative." }
        require(conflictCount >= 0) { "conflictCount cannot be negative." }
        require(neutralCount >= 0) { "neutralCount cannot be negative." }
    }
}

@Serializable
data class QuantLensExpectedValueRange(
    val primaryStatus: QuantLensPrimaryStatus,
    val band: ExpectedValueRangeBand,
    val source: ExpectedValueRangeSource? = null,
    val freshnessQualifier: QuantLensFreshnessQualifier? = null,
    val weightedFairValueCents: Long? = null,
    val weightedUpsideBps: Int? = null,
    val lowFairValueCents: Long? = null,
    val highFairValueCents: Long? = null,
    val spreadBps: Int? = null,
    val reasonCodes: List<QuantLensReasonCode>,
) {
    init {
        requireValidSection(primaryStatus, reasonCodes)
        requireNonNegativeCents("weightedFairValueCents", weightedFairValueCents)
        requireBps("weightedUpsideBps", weightedUpsideBps, min = -100_000, max = 100_000)
        requireNonNegativeCents("lowFairValueCents", lowFairValueCents)
        requireNonNegativeCents("highFairValueCents", highFairValueCents)
        if (lowFairValueCents != null && highFairValueCents != null) {
            require(lowFairValueCents <= highFairValueCents) {
                "lowFairValueCents cannot exceed highFairValueCents."
            }
        }
        requireBps("spreadBps", spreadBps, max = 100_000)
    }
}

@Serializable
data class QuantLensCorrelationRisk(
    val primaryStatus: QuantLensPrimaryStatus,
    val band: CorrelationRiskBand,
    val freshnessQualifier: QuantLensFreshnessQualifier? = null,
    val topPairs: List<QuantLensCorrelationPair> = emptyList(),
    val validPairCount: Int = 0,
    val reasonCodes: List<QuantLensReasonCode>,
) {
    init {
        requireValidSection(primaryStatus, reasonCodes)
        require(validPairCount >= 0) { "validPairCount cannot be negative." }
    }
}

@Serializable
data class QuantLensCorrelationPair(
    val symbol: String,
    val correlationBps: Int,
    val overlapCount: Int,
    val range: ChartRange,
) {
    init {
        require(symbol.isNotBlank()) { "Correlation pair symbol is required." }
        requireBps("correlationBps", correlationBps, min = -10_000, max = 10_000)
        require(overlapCount >= 0) { "overlapCount cannot be negative." }
    }
}

@Serializable
data class QuantLensTrendReliability(
    val primaryStatus: QuantLensPrimaryStatus,
    val band: TrendReliabilityBand,
    val freshnessQualifier: QuantLensFreshnessQualifier? = null,
    val sampleCount: Int = 0,
    val rSquaredBps: Int? = null,
    val movementBps: Int? = null,
    val reasonCodes: List<QuantLensReasonCode>,
) {
    init {
        requireValidSection(primaryStatus, reasonCodes)
        require(sampleCount >= 0) { "sampleCount cannot be negative." }
        requireBps("rSquaredBps", rSquaredBps)
        requireBps("movementBps", movementBps, min = -100_000, max = 100_000)
    }
}

@Serializable
data class QuantLensSimilarSetups(
    val primaryStatus: QuantLensPrimaryStatus,
    val band: SimilarSetupsBand,
    val freshnessQualifier: QuantLensFreshnessQualifier? = null,
    val qualifyingComparableCount: Int = 0,
    val matches: List<SimilarSetupMatch> = emptyList(),
    val reasonCodes: List<QuantLensReasonCode>,
) {
    init {
        requireValidSection(primaryStatus, reasonCodes)
        require(qualifyingComparableCount >= 0) { "qualifyingComparableCount cannot be negative." }
    }
}

@Serializable
data class SimilarSetupMatch(
    val symbol: String,
    val similarityBps: Int,
    val distanceBps: Int,
    val sharedFeatureCount: Int,
    val compositeScore: Int? = null,
    val reasonCodes: List<QuantLensReasonCode> = emptyList(),
) {
    init {
        require(symbol.isNotBlank()) { "Similar setup symbol is required." }
        requireBps("similarityBps", similarityBps)
        requireBps("distanceBps", distanceBps)
        require(sharedFeatureCount >= 0) { "sharedFeatureCount cannot be negative." }
    }
}

@Serializable
data class QuantLensComparable(
    val symbol: String,
    val valuationUpsideBps: Int? = null,
    val evidenceStrengthBps: Int? = null,
    val opportunityScore: Int? = null,
    val trendReliabilityBps: Int? = null,
    val evSpreadBps: Int? = null,
) {
    init {
        require(symbol.isNotBlank()) { "Quant Lens comparable symbol is required." }
        requireBps("valuationUpsideBps", valuationUpsideBps, min = -100_000, max = 100_000)
        requireBps("evidenceStrengthBps", evidenceStrengthBps)
        requireBps("trendReliabilityBps", trendReliabilityBps)
        requireBps("evSpreadBps", evSpreadBps, max = 100_000)
    }
}

@Serializable
data class QuantLensCorrelationSeries(
    val symbol: String,
    val range: ChartRange,
    val candles: List<HistoricalCandle>,
    val freshnessQualifier: QuantLensFreshnessQualifier? = null,
) {
    init {
        require(symbol.isNotBlank()) { "Quant Lens correlation series symbol is required." }
    }
}

private fun requireValidSection(
    primaryStatus: QuantLensPrimaryStatus,
    reasonCodes: List<QuantLensReasonCode>,
) {
    if (primaryStatus == QuantLensPrimaryStatus.Available) {
        require(reasonCodes.isNotEmpty()) {
            "Available Quant Lens sections require at least one reason code."
        }
    }
}

private fun requireNonNegativeCents(name: String, value: Long?) {
    if (value != null) {
        require(value >= 0) { "$name cannot be negative." }
    }
}

private fun requireBps(
    name: String,
    value: Int?,
    min: Int = 0,
    max: Int = 10_000,
) {
    if (value != null) {
        require(value in min..max) { "$name must be between $min and $max." }
    }
}
