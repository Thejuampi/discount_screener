package com.discountscreener.android.domain.repository

import com.discountscreener.android.presentation.dashboard.EarningsGateUi
import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.DiscoveryConfig
import com.discountscreener.android.domain.model.ScoringPreferences
import com.discountscreener.android.domain.model.DiscoverySnapshot
import com.discountscreener.android.domain.model.SystemStats
import com.discountscreener.android.domain.model.TickerSearchSuggestion
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ComputationResult
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.flow.Flow

interface DashboardRepository {
    fun observeUpdates(): Flow<Long>
    suspend fun bootstrap(filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange, opportunityScoringModel: OpportunityScoringModel): DashboardSnapshot
    suspend fun currentSnapshot(filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange, opportunityScoringModel: OpportunityScoringModel): DashboardSnapshot
    /**
     * Starts a refresh and returns the snapshot as it is now. [force] buys a new quoteSummary
     * and year chart even when those captures are less than a day old. Start leaves [force]
     * false. The Refresh button sets it true.
     */
    suspend fun refreshAll(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
        force: Boolean = false,
    ): DashboardSnapshot
    suspend fun ensureDetailLoaded(symbol: String, filter: ViewFilter, selectedRange: ChartRange, opportunityScoringModel: OpportunityScoringModel): DashboardSnapshot
    suspend fun addSymbols(rawInput: String, filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange, opportunityScoringModel: OpportunityScoringModel): DashboardSnapshot
    suspend fun selectProfile(profile: String, filter: ViewFilter, selectedRange: ChartRange, opportunityScoringModel: OpportunityScoringModel): DashboardSnapshot
    suspend fun toggleWatchlist(symbol: String, filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange, opportunityScoringModel: OpportunityScoringModel): DashboardSnapshot
    /** Restores the persisted scoring model and market-dimension switch, and applies the latter. */
    suspend fun loadScoringPreferences(): ScoringPreferences

    /**
     * Writes both and applies the market-dimension flag. Does not emit a dashboard update.
     * The caller rebuilds the list after persist so a load tick cannot apply the previous scores.
     */
    suspend fun persistScoringPreferences(preferences: ScoringPreferences)

    /**
     * The reader's own notes, by symbol.
     *
     * Deliberately not part of [DashboardSnapshot]. A note has no weight in any score, and every
     * snapshot method above rebuilds the whole list; carrying notes there would rebuild that list
     * every time someone types a sentence.
     */
    suspend fun loadSymbolNotes(): Map<String, String>

    /** Writes one note. A blank note clears it. */
    suspend fun saveSymbolNote(symbol: String, note: String)

    suspend fun loadSystemStats(): SystemStats
    suspend fun pruneOldRevisions(retentionDays: Int): Int
    suspend fun clearAllData()
    suspend fun dcfSnapshot(): Map<String, DcfAnalysis>
    suspend fun trackedSymbolDetails(): List<SymbolDetail>

    /**
     * Debug-only: the current opportunity scores plus the raw inputs behind them, as CSV.
     *
     * The four buckets share inputs, and only a whole-population measurement can say how much that
     * matters. Nothing in the product reads this; it is the input to the offline correlation.
     */
    suspend fun scoreExportCsv(opportunityScoringModel: OpportunityScoringModel): String
    suspend fun earningsEvents(): EarningsGateUi
    suspend fun currentIndexEstimates(): ComputationResult<IndexEstimatesReport>
    /**
     * Records an estimates snapshot using [com.discountscreener.core.engine.EstimatesHistoryPolicy]
     * (one durable point per UTC day; skips enrichment noise).
     * @return true when a row was written or replaced
     */
    suspend fun recordEstimatesSnapshot(report: IndexEstimatesReport): Boolean
    suspend fun estimatesHistory(profileName: String): List<IndexEstimatesReport>
    suspend fun searchTickers(query: String, currentProfile: String, limit: Int = 8): List<TickerSearchSuggestion>

    /** Discovery membership + scores (separate from tracked profile book). */
    suspend fun loadDiscoverySnapshot(): DiscoverySnapshot
    suspend fun saveDiscoveryConfig(config: DiscoveryConfig): DiscoverySnapshot
    suspend fun recreateDiscoveryUniverse(): DiscoverySnapshot
    suspend fun refreshDiscoveryScores(): DiscoverySnapshot
    suspend fun cancelDiscoveryJob(): DiscoverySnapshot
    suspend fun clearDiscoveryData(): DiscoverySnapshot
    fun observeDiscoveryProgress(): Flow<Unit>
    suspend fun ensureReplayBackingLoaded(symbol: String, range: ChartRange)
}
