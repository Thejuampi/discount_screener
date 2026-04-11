package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository
import kotlinx.coroutines.flow.Flow

class ObserveDashboardUpdatesUseCase(private val repository: DashboardRepository) {
    operator fun invoke(): Flow<Long> = repository.observeUpdates()
}
