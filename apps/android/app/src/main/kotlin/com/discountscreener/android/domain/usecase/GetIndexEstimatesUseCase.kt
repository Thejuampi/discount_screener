package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.model.ComputationResult
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.OpportunityScoringModel

class GetIndexEstimatesUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(opportunityScoringModel: OpportunityScoringModel): ComputationResult<IndexEstimatesReport> {
        return repository.currentIndexEstimates()
    }
}
