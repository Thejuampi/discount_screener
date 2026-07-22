package com.discountscreener.android.domain.repository

import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.DiscoveryConfig
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
    suspend fun refreshAll(filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange, opportunityScoringModel: OpportunityScoringModel): DashboardSnapshot
    suspend fun ensureDetailLoaded(symbol: String, filter: ViewFilter, selectedRange: ChartRange, opportunityScoringModel: OpportunityScoringModel): DashboardSnapshot
    suspend fun addSymbols(rawInput: String, filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange, opportunityScoringModel: OpportunityScoringModel): DashboardSnapshot
    suspend fun selectProfile(profile: String, filter: ViewFilter, selectedRange: ChartRange, opportunityScoringModel: OpportunityScoringModel): DashboardSnapshot
    suspend fun toggleWatchlist(symbol: String, filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange, opportunityScoringModel: OpportunityScoringModel): DashboardSnapshot
    suspend fun loadSystemStats(): SystemStats
    suspend fun pruneOldRevisions(retentionDays: Int): Int
    suspend fun clearAllData()
    suspend fun dcfSnapshot(): Map<String, DcfAnalysis>
    suspend fun trackedSymbolDetails(): List<SymbolDetail>
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
}
