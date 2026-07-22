package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.model.IndexEstimatesReport

/**
 * Records an index-estimates snapshot when [com.discountscreener.core.engine.EstimatesHistoryPolicy]
 * says the change is worth keeping (daily durability, material moves only).
 *
 * @return true when a row was written or the current day was replaced
 */
class SaveEstimatesSnapshotUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(report: IndexEstimatesReport): Boolean =
        repository.recordEstimatesSnapshot(report)
}
