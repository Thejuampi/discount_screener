package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.model.ChartRange

class EnsureReplayBackingLoadedUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(symbol: String, range: ChartRange) =
        repository.ensureReplayBackingLoaded(symbol, range)
}
