package com.discountscreener.android.domain.model

import com.discountscreener.core.engine.checkedUpsideBps
import com.discountscreener.core.model.AlertEvent
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.IssueRecord
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRevision

enum class DashboardStartupPhase {
    Restoring,
    SwitchingProfile,
    ShowingCached,
    Refreshing,
    Ready,
}

enum class TrackedRowState {
    Loading,
    Cached,
    Live,
    Failed,
}

enum class RowFreshness {
    Loading,
    Restored,
    Updating,
    Updated,
    Stale,
    Issue,
}

enum class RowExplanationKind {
    PriceMoved,
    TargetChanged,
    RelativeReRank,
    CombinedMove,
    NoBaseline,
    NoMeaningfulChange,
}

enum class RowDecisionState {
    Act,
    Watch,
    Avoid,
}

enum class ChangeDirection {
    Up,
    Down,
}

data class RankMovement(
    val direction: ChangeDirection,
    val places: Int,
    val previousIndex: Int,
    val currentIndex: Int,
)

enum class ValuationChangeTier {
    Significant,
    Major,
}

data class ValuationChange(
    val direction: ChangeDirection,
    val previousFairValueCents: Long,
    val currentFairValueCents: Long,
    val changeBps: Int,
    val tier: ValuationChangeTier,
)

fun rankMovement(previousIndex: Int?, currentIndex: Int): RankMovement? {
    previousIndex ?: return null
    if (previousIndex == currentIndex) return null
    return RankMovement(
        direction = if (currentIndex < previousIndex) ChangeDirection.Up else ChangeDirection.Down,
        places = kotlin.math.abs(currentIndex - previousIndex),
        previousIndex = previousIndex,
        currentIndex = currentIndex,
    )
}

fun significantValuationChange(previousFairValueCents: Long?, currentFairValueCents: Long?): ValuationChange? {
    previousFairValueCents ?: return null
    currentFairValueCents ?: return null
    val changeBps = checkedUpsideBps(previousFairValueCents, currentFairValueCents) ?: return null
    val absoluteChangeBps = kotlin.math.abs(changeBps)
    if (absoluteChangeBps < SIGNIFICANT_VALUATION_MOVE_BPS) {
        return null
    }
    return ValuationChange(
        direction = if (changeBps >= 0) ChangeDirection.Up else ChangeDirection.Down,
        previousFairValueCents = previousFairValueCents,
        currentFairValueCents = currentFairValueCents,
        changeBps = changeBps,
        tier = if (absoluteChangeBps >= MAJOR_VALUATION_MOVE_BPS) {
            ValuationChangeTier.Major
        } else {
            ValuationChangeTier.Significant
        },
    )
}

fun preferredAnalystTargetFairValueCents(detail: SymbolDetail?): Long? =
    detail?.weightedExternalSignalFairValueCents ?: detail?.externalSignalFairValueCents

fun preferredAnalystCoverageCount(detail: SymbolDetail?): Int? =
    detail?.weightedAnalystCount ?: detail?.analystOpinionCount

data class TrackedSymbolRow(
    val symbol: String,
    val marketPriceCents: Long? = null,
    val intrinsicValueCents: Long? = null,
    val gapBps: Int? = null,
    val upsideBps: Int? = gapBps,
    val confidence: ConfidenceBand? = null,
    val qualification: QualificationStatus? = null,
    val isWatched: Boolean = false,
    val state: TrackedRowState = TrackedRowState.Loading,
    val freshness: RowFreshness = RowFreshness.Loading,
    val stale: Boolean = false,
    val providerIssue: String? = null,
    val trustNote: String? = null,
    val freshnessAsOfEpochSeconds: Long? = null,
    val companyName: String? = null,
    val rankMovement: RankMovement? = null,
    val valuationChange: ValuationChange? = null,
    val explanation: RowExplanationKind? = null,
    val decisionState: RowDecisionState? = null,
)

data class OpportunityListRow(
    val symbol: String,
    val marketPriceCents: Long,
    val intrinsicValueCents: Long,
    val gapBps: Int,
    val upsideBps: Int = gapBps,
    val confidence: ConfidenceBand,
    val isWatched: Boolean,
    val freshness: RowFreshness = RowFreshness.Loading,
    val providerIssue: String? = null,
    val trustNote: String? = null,
    val freshnessAsOfEpochSeconds: Long? = null,
    val fundamentalsScore: Int? = null,
    val technicalScore: Int? = null,
    val forecastScore: Int? = null,
    val compositeScore: Int,
    val coverageCount: Int,
    val fundamentalsSignals: List<String> = emptyList(),
    val technicalSignals: List<String> = emptyList(),
    val forecastSignals: List<String> = emptyList(),
    val companyName: String? = null,
    val rankMovement: RankMovement? = null,
    val valuationChange: ValuationChange? = null,
    val explanation: RowExplanationKind? = null,
    val decisionState: RowDecisionState? = null,
)

data class DashboardSnapshot(
    val availableProfiles: List<String>,
    val currentProfile: String,
    val trackedSymbols: List<String>,
    val trackedRows: List<TrackedSymbolRow>,
    val watchlistSymbols: List<String>,
    val candidateRows: List<CandidateRow>,
    val opportunityRows: List<OpportunityListRow>,
    val opportunityScoringModel: OpportunityScoringModel,
    val issues: List<IssueRecord>,
    val selectedDetail: SymbolDetail?,
    val selectedCharts: Map<ChartRange, List<HistoricalCandle>>,
    val selectedHistory: List<SymbolRevision>,
    val selectedAlerts: List<AlertEvent>,
    val lastUpdatedAtEpochSeconds: Long?,
    val startupPhase: DashboardStartupPhase,
    val refreshCompletedSymbols: Int,
    val refreshTargetSymbols: Int,
    val statusMessage: String?,
)

private const val SIGNIFICANT_VALUATION_MOVE_BPS = 500
private const val MAJOR_VALUATION_MOVE_BPS = 2_000
