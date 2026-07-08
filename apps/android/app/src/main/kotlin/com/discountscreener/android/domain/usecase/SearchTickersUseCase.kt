package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.model.TickerSearchSuggestion
import com.discountscreener.android.domain.repository.DashboardRepository

class SearchTickersUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(
        query: String,
        currentProfile: String,
        limit: Int = 8,
    ): List<TickerSearchSuggestion> = repository.searchTickers(query, currentProfile, limit)
}
