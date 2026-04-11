package com.discountscreener.android.domain.model

import com.discountscreener.core.model.AlertEvent
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.IssueRecord
import com.discountscreener.core.model.OpportunityRow
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
    val stale: Boolean = false,
    val providerIssue: String? = null,
    val companyName: String? = null,
)

data class DashboardSnapshot(
    val availableProfiles: List<String>,
    val currentProfile: String,
    val trackedSymbols: List<String>,
    val trackedRows: List<TrackedSymbolRow>,
    val watchlistSymbols: List<String>,
    val candidateRows: List<CandidateRow>,
    val opportunityRows: List<OpportunityRow>,
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
