package com.discountscreener.android.domain.repository

import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.SystemStats
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.flow.Flow

interface DashboardRepository {
    fun observeUpdates(): Flow<Long>
    suspend fun bootstrap(filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange): DashboardSnapshot
    suspend fun currentSnapshot(filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange): DashboardSnapshot
    suspend fun refreshAll(filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange): DashboardSnapshot
    suspend fun ensureDetailLoaded(symbol: String, filter: ViewFilter, selectedRange: ChartRange): DashboardSnapshot
    suspend fun addSymbols(rawInput: String, filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange): DashboardSnapshot
    suspend fun selectProfile(profile: String, filter: ViewFilter, selectedRange: ChartRange): DashboardSnapshot
    suspend fun toggleWatchlist(symbol: String, filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange): DashboardSnapshot
    suspend fun loadSystemStats(): SystemStats
    suspend fun pruneOldRevisions(retentionDays: Int): Int
    suspend fun clearAllData()
}
