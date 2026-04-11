package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ViewFilter

class SelectDashboardSymbolUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(
        symbol: String,
        filter: ViewFilter,
        selectedRange: ChartRange,
    ): DashboardSnapshot = repository.ensureDetailLoaded(symbol, filter, selectedRange)
}
