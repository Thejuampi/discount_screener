package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.model.DiscoveryConfig
import com.discountscreener.android.domain.model.DiscoverySnapshot
import com.discountscreener.android.domain.repository.DashboardRepository
import kotlinx.coroutines.flow.Flow

class LoadDiscoverySnapshotUseCase(
    private val repository: DashboardRepository,
) {
    suspend operator fun invoke(): DiscoverySnapshot = repository.loadDiscoverySnapshot()
}

class SaveDiscoveryConfigUseCase(
    private val repository: DashboardRepository,
) {
    suspend operator fun invoke(config: DiscoveryConfig): DiscoverySnapshot =
        repository.saveDiscoveryConfig(config)
}

class RecreateDiscoveryUniverseUseCase(
    private val repository: DashboardRepository,
) {
    suspend operator fun invoke(): DiscoverySnapshot = repository.recreateDiscoveryUniverse()
}

class RefreshDiscoveryScoresUseCase(
    private val repository: DashboardRepository,
) {
    suspend operator fun invoke(): DiscoverySnapshot = repository.refreshDiscoveryScores()
}

class CancelDiscoveryJobUseCase(
    private val repository: DashboardRepository,
) {
    suspend operator fun invoke(): DiscoverySnapshot = repository.cancelDiscoveryJob()
}

class ClearDiscoveryDataUseCase(
    private val repository: DashboardRepository,
) {
    suspend operator fun invoke(): DiscoverySnapshot = repository.clearDiscoveryData()
}

class ObserveDiscoveryProgressUseCase(
    private val repository: DashboardRepository,
) {
    operator fun invoke(): Flow<Unit> = repository.observeDiscoveryProgress()
}
