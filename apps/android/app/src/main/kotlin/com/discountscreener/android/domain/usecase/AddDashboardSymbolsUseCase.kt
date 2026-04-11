package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ViewFilter

class AddDashboardSymbolsUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(
        rawInput: String,
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
    ): DashboardSnapshot = repository.addSymbols(rawInput, filter, selectedSymbol, selectedRange)
}
