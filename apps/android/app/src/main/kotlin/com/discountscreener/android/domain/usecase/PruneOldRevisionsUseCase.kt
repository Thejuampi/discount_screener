package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository

class PruneOldRevisionsUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(retentionDays: Int): Int = repository.pruneOldRevisions(retentionDays)
}