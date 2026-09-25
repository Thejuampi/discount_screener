package com.discountscreener.android.domain.repository

import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.DiscoveryConfig
import com.discountscreener.android.domain.model.DiscoverySnapshot
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.ScoringPreferences
import com.discountscreener.android.domain.model.SystemStats
import com.discountscreener.android.domain.model.TickerSearchSuggestion
import com.discountscreener.android.presentation.dashboard.EarningsGateUi
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ComputationArea
import com.discountscreener.core.model.ComputationFailure
import com.discountscreener.core.model.ComputationResult
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.ViewFilter
import com.discountscreener.core.portfolio.BookContext
import com.discountscreener.core.portfolio.ImportPlan
import com.discountscreener.core.portfolio.PortfolioLot
import com.discountscreener.core.portfolio.planParsedCsv
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

open class FakeDashboardRepository : DashboardRepository {
    var lots: List<PortfolioLot> = emptyList()
    var bookAsOf: String? = null
    var lastConfirmed: ImportPlan? = null
    var planCalls = 0
    var confirmCalls = 0

    override fun observeUpdates(): Flow<Long> = emptyFlow()

    override suspend fun bootstrap(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

    override suspend fun currentSnapshot(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

    override suspend fun refreshAll(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
        force: Boolean,
    ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

    override suspend fun ensureDetailLoaded(
        symbol: String,
        filter: ViewFilter,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

    override suspend fun loadCachedDetail(
        symbol: String,
        filter: ViewFilter,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot = currentSnapshot(filter, symbol, selectedRange, opportunityScoringModel)

    override suspend fun refreshDetail(
        symbol: String,
        filter: ViewFilter,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot = currentSnapshot(filter, symbol, selectedRange, opportunityScoringModel)

    override suspend fun addSymbols(
        rawInput: String,
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

    override suspend fun selectProfile(
        profile: String,
        filter: ViewFilter,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

    override suspend fun toggleWatchlist(
        symbol: String,
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

    override suspend fun loadScoringPreferences(): ScoringPreferences = ScoringPreferences()

    override suspend fun persistScoringPreferences(preferences: ScoringPreferences) = Unit

    override suspend fun loadSymbolNotes(): Map<String, String> = emptyMap()

    override suspend fun saveSymbolNote(symbol: String, note: String) = Unit

    override suspend fun loadSystemStats(): SystemStats = SystemStats(0, emptyList(), emptyList())

    override suspend fun pruneOldRevisions(retentionDays: Int): Int = 0

    override suspend fun clearAllData() = Unit

    override suspend fun dcfSnapshot(): Map<String, DcfAnalysis> = emptyMap()

    override suspend fun trackedSymbolDetails(): List<SymbolDetail> = emptyList()

    override suspend fun scoreExportCsv(opportunityScoringModel: OpportunityScoringModel): String = ""

    override suspend fun earningsCandidateRows(): List<OpportunityListRow> = emptyList()

    override suspend fun earningsEvents(): EarningsGateUi = EarningsGateUi()

    override suspend fun cachedEarningsCalendar(): Map<String, Long?> = emptyMap()

    override suspend fun refreshEarningsCalendar(symbols: List<String>): Map<String, Long?> = emptyMap()

    override suspend fun earningsLogBackup(): String = ""

    override suspend fun restoreEarningsLog(text: String): Int = 0

    override suspend fun saveAlphaVantageKey(key: String) = Unit

    override suspend fun currentIndexEstimates(): ComputationResult<IndexEstimatesReport> =
        ComputationResult.Error(
            ComputationFailure(
                code = "unused",
                area = ComputationArea.Estimates,
                message = "unused",
                recoverable = true,
            ),
        )

    override suspend fun recordEstimatesSnapshot(report: IndexEstimatesReport): Boolean = false

    override suspend fun estimatesHistory(profileName: String): List<IndexEstimatesReport> = emptyList()

    override suspend fun searchTickers(
        query: String,
        currentProfile: String,
        limit: Int,
    ): List<TickerSearchSuggestion> = emptyList()

    override suspend fun loadDiscoverySnapshot(): DiscoverySnapshot = DiscoverySnapshot()

    override suspend fun saveDiscoveryConfig(config: DiscoveryConfig): DiscoverySnapshot = DiscoverySnapshot()

    override suspend fun recreateDiscoveryUniverse(): DiscoverySnapshot = DiscoverySnapshot()

    override suspend fun refreshDiscoveryScores(): DiscoverySnapshot = DiscoverySnapshot()

    override suspend fun cancelDiscoveryJob(): DiscoverySnapshot = DiscoverySnapshot()

    override suspend fun clearDiscoveryData(): DiscoverySnapshot = DiscoverySnapshot()

    override fun observeDiscoveryProgress(): Flow<Unit> = emptyFlow()

    override suspend fun ensureReplayBackingLoaded(symbol: String, range: ChartRange) = Unit

    override suspend fun planPortfolioCsv(text: String): ImportPlan {
        planCalls++
        return planParsedCsv(text, BookContext(lots, bookAsOf))
    }

    override suspend fun confirmPortfolioPlan(plan: ImportPlan) {
        confirmCalls++
        lastConfirmed = plan
        when (plan) {
            is ImportPlan.ConfirmHoldingsReplace -> {
                lots = plan.positions
                bookAsOf = plan.asOf
            }
            is ImportPlan.ConfirmTradesMerge -> {
                lots = plan.positions
                bookAsOf = plan.nextBookAsOf
            }
            is ImportPlan.Refuse -> Unit
        }
    }

    private fun emptySnapshot(model: OpportunityScoringModel) = DashboardSnapshot(
        availableProfiles = emptyList(),
        currentProfile = "qa",
        trackedSymbols = emptyList(),
        trackedRows = emptyList(),
        watchlistSymbols = emptyList(),
        candidateRows = emptyList(),
        opportunityRows = emptyList(),
        opportunityUniverse = emptyList(),
        opportunityScoringModel = model,
        issues = emptyList(),
        selectedDetail = null,
        selectedCharts = emptyMap(),
        selectedHistory = emptyList(),
        selectedAlerts = emptyList(),
        lastUpdatedAtEpochSeconds = null,
        startupPhase = DashboardStartupPhase.Ready,
        refreshCompletedSymbols = 0,
        refreshTargetSymbols = 0,
        statusMessage = null,
        portfolioLots = lots,
    )
}
