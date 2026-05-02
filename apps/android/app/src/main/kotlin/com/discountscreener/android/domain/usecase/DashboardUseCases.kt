package com.discountscreener.android.domain.usecase

data class DashboardUseCases(
    val observeDashboardUpdates: ObserveDashboardUpdatesUseCase,
    val bootstrapDashboard: BootstrapDashboardUseCase,
    val refreshDashboard: RefreshDashboardUseCase,
    val getDashboardSnapshot: GetDashboardSnapshotUseCase,
    val selectDashboardSymbol: SelectDashboardSymbolUseCase,
    val addDashboardSymbols: AddDashboardSymbolsUseCase,
    val selectDashboardProfile: SelectDashboardProfileUseCase,
    val toggleDashboardWatchlist: ToggleDashboardWatchlistUseCase,
    val loadSystemStats: LoadSystemStatsUseCase,
    val pruneOldRevisions: PruneOldRevisionsUseCase,
    val clearAllData: ClearAllDataUseCase,
    val getIndexEstimates: GetIndexEstimatesUseCase,
    val saveEstimatesSnapshot: SaveEstimatesSnapshotUseCase,
    val getEstimatesHistory: GetEstimatesHistoryUseCase,
)
