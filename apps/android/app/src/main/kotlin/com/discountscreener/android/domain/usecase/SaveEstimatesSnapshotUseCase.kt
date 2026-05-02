package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.model.IndexEstimatesReport

class SaveEstimatesSnapshotUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(report: IndexEstimatesReport) =
        repository.saveEstimatesSnapshot(report)
}
