package com.discountscreener.android.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.FundamentalTimeseriesProvider
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.BuildConfig
import com.discountscreener.android.data.repository.DefaultDashboardRepository
import com.discountscreener.android.domain.logging.AndroidAppLogger
import com.discountscreener.android.domain.usecase.AddDashboardSymbolsUseCase
import com.discountscreener.android.domain.usecase.BootstrapDashboardUseCase
import com.discountscreener.android.domain.usecase.CancelDiscoveryJobUseCase
import com.discountscreener.android.domain.usecase.ClearAllDataUseCase
import com.discountscreener.android.domain.usecase.ClearDiscoveryDataUseCase
import com.discountscreener.android.domain.usecase.DashboardUseCases
import com.discountscreener.android.domain.usecase.GetDashboardSnapshotUseCase
import com.discountscreener.android.domain.usecase.GetEstimatesHistoryUseCase
import com.discountscreener.android.domain.usecase.GetIndexEstimatesUseCase
import com.discountscreener.android.domain.usecase.LoadDiscoverySnapshotUseCase
import com.discountscreener.android.domain.usecase.LoadSystemStatsUseCase
import com.discountscreener.android.domain.usecase.ObserveDashboardUpdatesUseCase
import com.discountscreener.android.domain.usecase.ObserveDiscoveryProgressUseCase
import com.discountscreener.android.domain.usecase.PruneOldRevisionsUseCase
import com.discountscreener.android.domain.usecase.RecreateDiscoveryUniverseUseCase
import com.discountscreener.android.domain.usecase.RefreshDashboardUseCase
import com.discountscreener.android.domain.usecase.RefreshDiscoveryScoresUseCase
import com.discountscreener.android.domain.usecase.SaveDiscoveryConfigUseCase
import com.discountscreener.android.domain.usecase.SaveEstimatesSnapshotUseCase
import com.discountscreener.android.domain.usecase.SearchTickersUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardProfileUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardSymbolUseCase
import com.discountscreener.android.domain.usecase.ToggleDashboardWatchlistUseCase
import com.discountscreener.android.presentation.dashboard.DashboardViewModel

class DiscountScreenerAppContainer(context: Context) {
    private val appContext = context.applicationContext

    /**
     * One client for everything that talks to Yahoo. It carries the cookie jar and crumb session
     * the endpoint requires, so a second instance would bootstrap its own — twice the handshakes,
     * and two independent things to get rate-limited. The market read shares this one.
     */
    private val yahooClient by lazy { YahooFinanceClient() }

    private val repository by lazy {
        // Debug installs (agent live QA / make android-run) always start on profile qa (≤20).
        // Release keeps full product default (sp500). Silence is not permission for full-universe thrash.
        val startupProfile = if (BuildConfig.DEBUG) {
            DefaultDashboardRepository.QA_PROFILE
        } else {
            DefaultDashboardRepository.PRODUCT_DEFAULT_PROFILE
        }
        DefaultDashboardRepository(
            stateStore = SQLiteStateStore(appContext),
            profileCatalog = ProfileCatalog(appContext.assets),
            yahooClient = yahooClient,
            universeCatalog = UniverseCatalog(appContext.assets),
            secondaryTimeseriesProvider = defaultSecondaryTimeseriesProvider(),
            logger = AndroidAppLogger(),
            defaultProfile = startupProfile,
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
            searchTickers = SearchTickersUseCase(repository),
            loadDiscoverySnapshot = LoadDiscoverySnapshotUseCase(repository),
            saveDiscoveryConfig = SaveDiscoveryConfigUseCase(repository),
            recreateDiscoveryUniverse = RecreateDiscoveryUniverseUseCase(repository),
            refreshDiscoveryScores = RefreshDiscoveryScoresUseCase(repository),
            cancelDiscoveryJob = CancelDiscoveryJobUseCase(repository),
            clearDiscoveryData = ClearDiscoveryDataUseCase(repository),
            observeDiscoveryProgress = ObserveDiscoveryProgressUseCase(repository),
        )
    }

    fun dashboardViewModelFactory(): ViewModelProvider.Factory =
        DashboardViewModel.factory(dashboardUseCases)
}

internal fun defaultSecondaryTimeseriesProvider(): FundamentalTimeseriesProvider? = null
