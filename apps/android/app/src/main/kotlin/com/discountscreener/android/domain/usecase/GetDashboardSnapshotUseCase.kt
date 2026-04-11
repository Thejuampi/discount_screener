package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ViewFilter

class GetDashboardSnapshotUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
    ): DashboardSnapshot = repository.currentSnapshot(filter, selectedSymbol, selectedRange)
}
