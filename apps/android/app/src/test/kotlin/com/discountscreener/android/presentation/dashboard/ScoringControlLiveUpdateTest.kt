package com.discountscreener.android.presentation.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.market.DailyCandleSource
import com.discountscreener.android.data.market.MarketDataRepository
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.CnnFearGreedClient
import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.offlineHttpClient
import com.discountscreener.android.data.repository.DefaultDashboardRepository
import com.discountscreener.android.domain.usecase.AddDashboardSymbolsUseCase
import com.discountscreener.android.domain.usecase.BootstrapDashboardUseCase
import com.discountscreener.android.domain.usecase.CancelDiscoveryJobUseCase
import com.discountscreener.android.domain.usecase.ClearAllDataUseCase
import com.discountscreener.android.domain.usecase.ClearDiscoveryDataUseCase
import com.discountscreener.android.domain.usecase.EnsureReplayBackingLoadedUseCase
import com.discountscreener.android.domain.usecase.ExportScoresUseCase
import com.discountscreener.android.domain.usecase.GetDashboardSnapshotUseCase
import com.discountscreener.android.domain.usecase.GetEstimatesHistoryUseCase
import com.discountscreener.android.domain.usecase.GetIndexEstimatesUseCase
import com.discountscreener.android.domain.usecase.LoadDiscoverySnapshotUseCase
import com.discountscreener.android.domain.usecase.LoadScoringPreferencesUseCase
import com.discountscreener.android.domain.usecase.LoadSymbolNotesUseCase
import com.discountscreener.android.domain.usecase.LoadSystemStatsUseCase
import com.discountscreener.android.domain.usecase.ObserveDashboardUpdatesUseCase
import com.discountscreener.android.domain.usecase.ObserveDiscoveryProgressUseCase
import com.discountscreener.android.domain.usecase.PersistScoringPreferencesUseCase
import com.discountscreener.android.domain.usecase.PruneOldRevisionsUseCase
import com.discountscreener.android.domain.usecase.RecreateDiscoveryUniverseUseCase
import com.discountscreener.android.domain.usecase.RefreshDashboardUseCase
import com.discountscreener.android.domain.usecase.RefreshDiscoveryScoresUseCase
import com.discountscreener.android.domain.usecase.RunOutcomeReportUseCase
import com.discountscreener.android.domain.usecase.RunRetrospectiveUseCase
import com.discountscreener.android.domain.usecase.SaveDiscoveryConfigUseCase
import com.discountscreener.android.domain.usecase.SaveEstimatesSnapshotUseCase
import com.discountscreener.android.domain.usecase.SaveSymbolNoteUseCase
import com.discountscreener.android.domain.usecase.SearchTickersUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardProfileUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardSymbolUseCase
import com.discountscreener.android.domain.usecase.ToggleDashboardWatchlistUseCase
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import com.discountscreener.core.regime.MarketRegime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The ranking suite already locks [DefaultDashboardRepository.currentSnapshot]. These lock the
 * path a chip tap actually takes: dispatch through the presenter onto that same fixture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScoringControlLiveUpdateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private var clock = NOW

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun choosing_v4_through_the_presenter_reranks_the_list() = runTest(dispatcher) {
        withViewModel { viewModel ->
            viewModel.dispatch(DashboardAction.SetOpportunityScoringModel(OpportunityScoringModel.AggressiveV3))
            advanceUntilIdle()
            var underV3 = viewModel.state.value.opportunityRows.map { it.symbol }
            viewModel.dispatch(DashboardAction.SetOpportunityScoringModel(OpportunityScoringModel.AggressiveV4))
            advanceUntilIdle()
            assertNotEquals(underV3, viewModel.state.value.opportunityRows.map { it.symbol })
        }
    }

    @Test
    fun choosing_v4_through_the_presenter_scores_the_fixture_at_the_named_level() = runTest(dispatcher) {
        withViewModel { viewModel ->
            viewModel.dispatch(DashboardAction.SetOpportunityScoringModel(OpportunityScoringModel.AggressiveV4))
            advanceUntilIdle()
            assertEquals(
                V4_LEVEL,
                viewModel.state.value.opportunityRows.map { it.symbol to it.compositeScore },
            )
        }
    }

    @Test
    fun toggling_market_off_through_the_presenter_reorders_the_list() = runTest(dispatcher) {
        withViewModel { viewModel ->
            viewModel.dispatch(DashboardAction.SetOpportunityScoringModel(OpportunityScoringModel.AggressiveV3))
            advanceUntilIdle()
            var on = viewModel.state.value.opportunityRows.map { it.symbol }
            viewModel.dispatch(DashboardAction.SetRegimeScoringEnabled(false))
            advanceUntilIdle()
            assertNotEquals(on, viewModel.state.value.opportunityRows.map { it.symbol })
        }
    }

    @Test
    fun toggling_market_off_through_the_presenter_returns_every_row_to_its_base_score() = runTest(dispatcher) {
        withViewModel { viewModel ->
            viewModel.dispatch(DashboardAction.SetOpportunityScoringModel(OpportunityScoringModel.AggressiveV3))
            advanceUntilIdle()
            viewModel.dispatch(DashboardAction.SetRegimeScoringEnabled(false))
            advanceUntilIdle()
            var rows = viewModel.state.value.opportunityRows
            assertTrue(rows.isNotEmpty() && rows.all { it.compositeScore == it.compositeScoreBase })
        }
    }

    private suspend fun TestScope.withViewModel(block: suspend (DashboardViewModel) -> Unit) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            val repository = buildRepository(store)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            advanceUntilIdle()
            val viewModel = testViewModel(repository)
            // Do not Start: Start refreshes again and the fixture client does not cover the
            // session warmup that refresh uses on a second pass. The repository is already scored.
            block(viewModel)
        } finally {
            store.close()
        }
    }

    private fun testViewModel(repository: DefaultDashboardRepository): DashboardViewModel {
        return DashboardViewModel(
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
            clearAllDataUseCase = ClearAllDataUseCase(repository),
            exportScores = ExportScoresUseCase(
                repository,
                File(System.getProperty("java.io.tmpdir")!!),
                dispatcher,
            ),
            runRetrospective = RunRetrospectiveUseCase(
                EmptyBacktestCandles,
                File(System.getProperty("java.io.tmpdir")!!),
                dispatcher,
            ),
            runOutcomeReport = RunOutcomeReportUseCase(
                journalSource = { emptyList() },
                candleSource = EmptyBacktestCandles,
                streetDiagnosticSource = { emptyMap() },
                exportDirectory = File(System.getProperty("java.io.tmpdir")!!),
                ioDispatcher = dispatcher,
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

    private fun buildRepository(store: SQLiteStateStore) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = FixtureYahooFinanceClient(),
        universeCatalog = UniverseCatalog(context.assets),
        nowProvider = { clock++ },
        ioDispatcher = dispatcher,
        defaultProfile = DefaultDashboardRepository.QA_PROFILE,
        marketDataRepository = StubMarketDataRepository(),
    )

    private object EmptyBacktestCandles : DailyCandleSource {
        override suspend fun loadBacktestCandles(): Map<String, List<HistoricalCandle>> = emptyMap()
    }

    private class StubMarketDataRepository : MarketDataRepository(
        yahooClient = YahooFinanceClient(),
        fearGreedClient = CnnFearGreedClient(),
    ) {
        override suspend fun refreshIfStale(symbols: List<String>): MarketRegime = REGIME

        override suspend fun cachedRegime(): MarketRegime = REGIME

        override suspend fun cachedDailySummaries(): Map<String, ChartRangeSummary> =
            QA_SYMBOLS.associateWith { dailySummary(it) }
    }

    private class FixtureYahooFinanceClient : YahooFinanceClient(httpClient = offlineHttpClient()) {
        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            val price = 10_000L
            val fair = price * (12_000L + rank(symbol) * 200L) / 10_000L
            return ProviderFetchResult(
                symbol = symbol,
                snapshot = MarketSnapshot(
                    symbol = symbol,
                    companyName = symbol,
                    profitable = true,
                    marketPriceCents = price,
                    intrinsicValueCents = fair,
                ),
                companyName = symbol,
                externalSignal = ExternalValuationSignal(symbol = symbol, fairValueCents = fair, ageSeconds = 0),
                fundamentals = fundamentals(symbol),
                coverage = ProviderCoverage(
                    core = ProviderComponentState.Fresh,
                    external = ProviderComponentState.Fresh,
                    fundamentals = ProviderComponentState.Fresh,
                ),
                diagnostics = emptyList(),
            )
        }

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> =
            dailyCandles()

        override suspend fun fetchFundamentalTimeseries(symbol: String) = FundamentalTimeseries()
    }

    private companion object {
        const val DB_NAME = "scoring_control_live_update.sqlite3"
        const val NOW = 1_700_000_000L

        val QA_SYMBOLS = listOf(
            "T", "AMZN", "AAPL", "CI", "JPM", "ACGL", "MSFT", "NVDA", "UNH", "JNJ",
            "XOM", "BAC", "V", "WMT", "GOOGL", "META", "TSLA", "HD", "PG", "MRK",
        )

        // 38/37/36 became 33/32/31 when the cash-vote dedup retired the conversion term after an
        // FCF-yield vote: the fixture's names share one conversion reading, so its uniform +5 came
        // off every row at once.
        val V4_LEVEL = listOf(
            "MRK" to 33, "PG" to 33, "HD" to 33,
            "TSLA" to 32, "META" to 32, "GOOGL" to 32, "WMT" to 32, "V" to 32,
            "BAC" to 32, "XOM" to 32, "JNJ" to 32, "UNH" to 32, "NVDA" to 32,
            "MSFT" to 32, "ACGL" to 32, "JPM" to 32,
            "CI" to 31,
        )

        val REGIME = MarketRegime(
            primaryRegime = "Bull",
            environmentBand = "RiskOn",
            actionStance = "TrendDeploy",
            globalConfidenceBps = 8_000,
            preferQuality = true,
            breadthAboveMa200Pct = 62.0,
            cnnFearGreed = 55,
            asOfEpoch = NOW,
        )

        fun rank(symbol: String): Long = QA_SYMBOLS.indexOf(symbol).toLong().coerceAtLeast(0L)

        fun fundamentals(symbol: String) = FundamentalSnapshot(
            symbol = symbol,
            sectorName = "Technology",
            marketCapDollars = 600_000_000_000L,
            freeCashFlowDollars = 30_000_000_000L,
            operatingCashFlowDollars = 40_000_000_000L,
            totalCashDollars = 10_000_000_000L,
            totalDebtDollars = 50_000_000_000L,
            ebitdaDollars = 40_000_000_000L,
            sharesOutstanding = 4_800_000_000L,
            debtToEquityHundredths = 40,
            returnOnEquityBps = 1_800,
            betaMillis = 1_000,
            forwardPeHundredths = 1_600,
            priceToBookHundredths = 300,
        )

        fun dailySummary(symbol: String) = ChartRangeSummary(
            range = ChartRange.Year,
            capturedAt = NOW,
            candleCount = 252,
            latestCloseCents = 11_000L,
            ema20Cents = 10_800L,
            ema50Cents = 10_500L,
            ema200Cents = 9_800L,
            histogramCents = 4L,
            latestWilderRsi = 30.0 + rank(symbol) * 2.5,
            pos52wPct = 5.0 + rank(symbol) * 4.5,
            adx = 26.0,
            plusDi = 28.0,
            minusDi = 16.0,
        )

        fun dailyCandles(): List<HistoricalCandle> {
            val base = 10_000L
            return (0 until 252).map { index ->
                val close = base + index * 4L
                HistoricalCandle(
                    epochSeconds = NOW - (252L - index) * 86_400L,
                    openCents = close - 30L,
                    highCents = close + 40L,
                    lowCents = close - 50L,
                    closeCents = close,
                    volume = 1_000_000L + index * 500L,
                )
            }
        }
    }
}
