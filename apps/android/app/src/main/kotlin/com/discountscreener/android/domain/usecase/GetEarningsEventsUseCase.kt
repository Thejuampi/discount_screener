package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.android.presentation.dashboard.EarningsGateUi

class GetEarningsEventsUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(): EarningsGateUi = repository.earningsEvents()
}
