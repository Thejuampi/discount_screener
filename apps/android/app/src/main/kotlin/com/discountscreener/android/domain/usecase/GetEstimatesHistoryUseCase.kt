package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.model.IndexEstimatesReport

class GetEstimatesHistoryUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(profileName: String): List<IndexEstimatesReport> =
        repository.estimatesHistory(profileName)
}
