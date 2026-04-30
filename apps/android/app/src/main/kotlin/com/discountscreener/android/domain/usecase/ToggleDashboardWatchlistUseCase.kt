package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter

class ToggleDashboardWatchlistUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(
        symbol: String,
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot = repository.toggleWatchlist(symbol, filter, selectedSymbol, selectedRange, opportunityScoringModel)
}
