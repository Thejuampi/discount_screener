package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.model.ScoringPreferences
import com.discountscreener.android.domain.repository.DashboardRepository

class LoadScoringPreferencesUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(): ScoringPreferences = repository.loadScoringPreferences()
}

class PersistScoringPreferencesUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(preferences: ScoringPreferences) =
        repository.persistScoringPreferences(preferences)
}
