package com.discountscreener.android.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.repository.DefaultDashboardRepository
import com.discountscreener.android.domain.usecase.AddDashboardSymbolsUseCase
import com.discountscreener.android.domain.usecase.BootstrapDashboardUseCase
import com.discountscreener.android.domain.usecase.ClearAllDataUseCase
import com.discountscreener.android.domain.usecase.DashboardUseCases
import com.discountscreener.android.domain.usecase.GetDashboardSnapshotUseCase
import com.discountscreener.android.domain.usecase.GetEstimatesHistoryUseCase
import com.discountscreener.android.domain.usecase.GetIndexEstimatesUseCase
import com.discountscreener.android.domain.usecase.LoadSystemStatsUseCase
import com.discountscreener.android.domain.usecase.ObserveDashboardUpdatesUseCase
import com.discountscreener.android.domain.usecase.PruneOldRevisionsUseCase
import com.discountscreener.android.domain.usecase.RefreshDashboardUseCase
import com.discountscreener.android.domain.usecase.SaveEstimatesSnapshotUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardProfileUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardSymbolUseCase
import com.discountscreener.android.domain.usecase.ToggleDashboardWatchlistUseCase
import com.discountscreener.android.presentation.dashboard.DashboardViewModel

class DiscountScreenerAppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val repository by lazy {
        DefaultDashboardRepository(
            stateStore = SQLiteStateStore(appContext),
            profileCatalog = ProfileCatalog(appContext.assets),
            yahooClient = YahooFinanceClient(),
        )
    }

    private val dashboardUseCases by lazy {
        DashboardUseCases(
            observeDashboardUpdates = ObserveDashboardUpdatesUseCase(repository),
            bootstrapDashboard = BootstrapDashboardUseCase(repository),
            refreshDashboard = RefreshDashboardUseCase(repository),
            getDashboardSnapshot = GetDashboardSnapshotUseCase(repository),
            selectDashboardSymbol = SelectDashboardSymbolUseCase(repository),
            addDashboardSymbols = AddDashboardSymbolsUseCase(repository),
            selectDashboardProfile = SelectDashboardProfileUseCase(repository),
            toggleDashboardWatchlist = ToggleDashboardWatchlistUseCase(repository),
            loadSystemStats = LoadSystemStatsUseCase(repository),
            pruneOldRevisions = PruneOldRevisionsUseCase(repository),
            clearAllData = ClearAllDataUseCase(repository),
            getIndexEstimates = GetIndexEstimatesUseCase(repository),
            saveEstimatesSnapshot = SaveEstimatesSnapshotUseCase(repository),
            getEstimatesHistory = GetEstimatesHistoryUseCase(repository),
        )
    }

    fun dashboardViewModelFactory(): ViewModelProvider.Factory =
        DashboardViewModel.factory(dashboardUseCases)
}
