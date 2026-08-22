package com.discountscreener.android.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import java.io.File
import com.discountscreener.android.data.capture.ScreenCaptureSink
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.market.MarketDataRepository
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.CnnFearGreedClient
import com.discountscreener.android.data.remote.FredDgs10Client
import com.discountscreener.android.data.remote.FundamentalTimeseriesProvider
import com.discountscreener.android.data.remote.SecEdgarCacheGc
import com.discountscreener.android.data.remote.SecEdgarTimeseriesProvider
import com.discountscreener.android.data.remote.SecIssuerComponentClient
import com.discountscreener.android.data.remote.MarketsInsiderYieldClient
import com.discountscreener.android.data.remote.YahooFinanceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.discountscreener.android.data.remote.YahooTnxClient
import com.discountscreener.android.BuildConfig
import com.discountscreener.android.data.repository.DefaultDashboardRepository
import com.discountscreener.core.engine.CachedObservedMarketParamsSource
import com.discountscreener.core.engine.CachedYahooTnxMarketParamsSource
import com.discountscreener.core.engine.FredThenTnxMarketParamsSource
import com.discountscreener.android.domain.logging.AndroidAppLogger
import com.discountscreener.android.domain.usecase.AddDashboardSymbolsUseCase
import com.discountscreener.android.domain.usecase.BootstrapDashboardUseCase
import com.discountscreener.android.domain.usecase.CancelDiscoveryJobUseCase
import com.discountscreener.android.domain.usecase.ClearAllDataUseCase
import com.discountscreener.android.domain.usecase.ClearDiscoveryDataUseCase
import com.discountscreener.android.domain.usecase.DashboardUseCases
import com.discountscreener.android.domain.usecase.EnsureReplayBackingLoadedUseCase
import com.discountscreener.android.domain.usecase.GetDashboardSnapshotUseCase
import com.discountscreener.android.domain.usecase.GetEstimatesHistoryUseCase
import com.discountscreener.android.domain.usecase.GetIndexEstimatesUseCase
import com.discountscreener.android.domain.usecase.LoadDiscoverySnapshotUseCase
import com.discountscreener.android.domain.usecase.LoadScoringPreferencesUseCase
import com.discountscreener.android.domain.usecase.ExportScoresUseCase
import com.discountscreener.android.domain.usecase.RunOutcomeReportUseCase
import com.discountscreener.android.domain.usecase.RunRetrospectiveUseCase
import com.discountscreener.android.domain.usecase.LoadSymbolNotesUseCase
import com.discountscreener.android.domain.usecase.LoadSystemStatsUseCase
import com.discountscreener.android.domain.usecase.ObserveDashboardUpdatesUseCase
import com.discountscreener.android.domain.usecase.ObserveDiscoveryProgressUseCase
import com.discountscreener.android.domain.usecase.PersistScoringPreferencesUseCase
import com.discountscreener.android.domain.usecase.PruneOldRevisionsUseCase
import com.discountscreener.android.domain.usecase.RecreateDiscoveryUniverseUseCase
import com.discountscreener.android.domain.usecase.RefreshDashboardUseCase
import com.discountscreener.android.domain.usecase.RefreshDiscoveryScoresUseCase
import com.discountscreener.android.domain.usecase.SaveDiscoveryConfigUseCase
import com.discountscreener.android.domain.usecase.SaveEstimatesSnapshotUseCase
import com.discountscreener.android.domain.usecase.SaveSymbolNoteUseCase
import com.discountscreener.android.domain.usecase.SearchTickersUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardProfileUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardSymbolUseCase
import com.discountscreener.android.domain.usecase.ToggleDashboardWatchlistUseCase
import com.discountscreener.android.presentation.dashboard.DashboardViewModel

class DiscountScreenerAppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One client for everything that talks to Yahoo. It carries the cookie jar and crumb session
     * the endpoint requires, so a second instance would bootstrap its own — twice the handshakes,
     * and two independent things to get rate-limited. The market read shares this one.
     */
    private val yahooClient by lazy { YahooFinanceClient(logger = AndroidAppLogger()) }

    /**
     * One store for the whole app. Two helpers over one database file is two write queues over one
     * lock, and the market read now writes here too — so the sharing is load-bearing rather than
     * merely tidy.
     */
    private val stateStore by lazy { SQLiteStateStore(appContext) }

    private val marketDataRepository by lazy {
        MarketDataRepository(
            yahooClient = yahooClient,
            fearGreedClient = CnnFearGreedClient(),
            dailyCandleSink = stateStore,
        )
    }

    private val repository by lazy {
        val startupProfile = startupProfile(BuildConfig.QA_UNIVERSE)
        val marketParamsDir = File(appContext.cacheDir, "market-params")
        val marketParamsSource = FredThenTnxMarketParamsSource(
            fred = CachedObservedMarketParamsSource(
                fetchCsv = FredDgs10Client()::csv,
                cacheFile = File(marketParamsDir, "dgs10.csv").toPath(),
            ),
            tnx = CachedYahooTnxMarketParamsSource(
                // `MarketParamsSource.current()` is blocking and is read from the engine, which is
                // not a coroutine. This is the one place a suspending client meets it, so the
                // bridge lives here rather than as a blocking door back inside the client.
                fetchJson = { runBlocking { YahooTnxClient().chart() } },
                cacheFile = File(marketParamsDir, "tnx.json").toPath(),
            ),
        )
        DefaultDashboardRepository(
            stateStore = stateStore,
            profileCatalog = ProfileCatalog(appContext.assets),
            yahooClient = yahooClient,
            universeCatalog = UniverseCatalog(appContext.assets),
            marketDataRepository = marketDataRepository,
            secondaryTimeseriesProvider = defaultSecondaryTimeseriesProvider(
                cacheDir = File(appContext.cacheDir, "sec-edgar"),
                sweepScope = backgroundScope,
            ),
            logger = AndroidAppLogger(),
            defaultProfile = startupProfile,
            marketParamsSource = marketParamsSource,
            issuerYieldLookup = MarketsInsiderYieldClient(),
            componentLookup = SecIssuerComponentClient(
                cacheDir = File(appContext.cacheDir, "sec-edgar"),
            ),
            projectionCapture = screenCaptureSink::capture,
        )
    }

    /**
     * Writes the screen input to a place `adb pull` can reach, when it is armed.
     *
     * External files, because the app's private directory needs root to read on a release device
     * and the whole point is to get the file onto a workstation. It stays idle until armed, so a
     * user who never arms it pays one file check per snapshot.
     */
    private val screenCaptureSink by lazy {
        ScreenCaptureSink(
            directory = File(
                appContext.getExternalFilesDir(null) ?: appContext.filesDir,
                ScreenCaptureSink.DIRECTORY_NAME,
            ).apply { mkdirs() },
            logger = AndroidAppLogger(),
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
            loadScoringPreferences = LoadScoringPreferencesUseCase(repository),
            persistScoringPreferences = PersistScoringPreferencesUseCase(repository),
            loadSymbolNotes = LoadSymbolNotesUseCase(repository),
            saveSymbolNote = SaveSymbolNoteUseCase(repository),
            loadSystemStats = LoadSystemStatsUseCase(repository),
            pruneOldRevisions = PruneOldRevisionsUseCase(repository),
            clearAllData = ClearAllDataUseCase(repository),
            exportScores = ExportScoresUseCase(repository, appContext.filesDir),
            runRetrospective = RunRetrospectiveUseCase(stateStore, appContext.filesDir),
            runOutcomeReport = RunOutcomeReportUseCase(
                journalSource = { stateStore.loadScoreJournal() },
                candleSource = stateStore,
                streetDiagnosticSource = { repository.streetDiagnosticUpsideBps() },
                exportDirectory = appContext.filesDir,
            ),
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
            ensureReplayBackingLoaded = EnsureReplayBackingLoadedUseCase(repository),
        )
    }

    /**
     * Holds the process up for as long as the repository says a load is running.
     *
     * Started once, from the activity, because the platform only lets a foreground service start
     * while the app is in front. After that the load survives the user leaving.
     */
    fun keepLoadsRunningInBackground() {
        ForegroundLoadKeeper(appContext).keep(repository.loadInFlight, backgroundScope)
    }

    fun dashboardViewModelFactory(): ViewModelProvider.Factory =
        DashboardViewModel.factory(dashboardUseCases)
}

/**
 * Cold-start universe for an installed build.
 *
 * Only a QA install (`make android-run-qa`, which sets `BuildConfig.QA_UNIVERSE`) boots the
 * ≤20-symbol `qa` universe. The on-device database stays. A regular `make android-run` install
 * and every release build boot the product default (`sp500`).
 */
internal fun startupProfile(qaUniverse: Boolean): String =
    if (qaUniverse) {
        DefaultDashboardRepository.QA_PROFILE
    } else {
        DefaultDashboardRepository.PRODUCT_DEFAULT_PROFILE
    }

internal fun defaultSecondaryTimeseriesProvider(
    cacheDir: File = File("sec-edgar"),
    sweepScope: CoroutineScope? = null,
): FundamentalTimeseriesProvider {
    startSecEdgarCacheGc(cacheDir, sweepScope)
    return SecEdgarTimeseriesProvider(cacheDir)
}

internal fun startSecEdgarCacheGc(
    cacheDir: File,
    sweepScope: CoroutineScope?,
    gc: SecEdgarCacheGc = SecEdgarCacheGc(cacheDir),
) {
    if (sweepScope == null) return
    sweepScope.launch {
        gc.sweep()
        while (isActive) {
            delay(SecEdgarCacheGc.SWEEP_INTERVAL_MILLIS)
            gc.sweep()
        }
    }
}
