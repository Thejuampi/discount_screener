package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository

class ClearAllDataUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke() = repository.clearAllData()
}