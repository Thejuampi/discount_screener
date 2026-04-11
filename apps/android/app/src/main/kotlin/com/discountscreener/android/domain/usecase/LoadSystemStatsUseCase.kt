package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.model.SystemStats
import com.discountscreener.android.domain.repository.DashboardRepository

class LoadSystemStatsUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(): SystemStats = repository.loadSystemStats()
}