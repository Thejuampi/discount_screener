package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import com.discountscreener.core.regime.MarketRegime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProfileLoadSchedulingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun profile_switch_starts_market_after_profile_refresh_finishes() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher, databaseFileName = DB_NAME)
        val releaseQuotes = CompletableDeferred<Unit>()
        val client = GateYahooClient(releaseQuotes)
        val market = RecordingMarketRepository()
        val repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = client,
            universeCatalog = UniverseCatalog(context.assets),
            nowProvider = { START_EPOCH },
            ioDispatcher = dispatcher,
            defaultProfile = DefaultDashboardRepository.QA_PROFILE,
            marketDataRepository = market,
        )
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.Legacy)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, OpportunityScoringModel.Legacy)
            dispatcher.scheduler.runCurrent()

            assertEquals(0, market.refreshes.get())

            releaseQuotes.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, market.refreshes.get())
        } finally {
            releaseQuotes.complete(Unit)
            runCatching { repository.clearAllData() }
            advanceUntilIdle()
            store.close()
        }
    }

    @Test
    fun sp500_profile_switch_starts_one_market_read_after_its_refresh() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher, databaseFileName = DB_NAME)
        val releaseQuotes = CompletableDeferred<Unit>()
        val client = GateYahooClient(releaseQuotes)
        val market = RecordingMarketRepository()
        val repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = client,
            universeCatalog = UniverseCatalog(context.assets),
            nowProvider = { START_EPOCH },
            ioDispatcher = dispatcher,
            defaultProfile = DefaultDashboardRepository.QA_PROFILE,
            marketDataRepository = market,
        )
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.Legacy)
            repository.selectProfile("sp500", ViewFilter(), ChartRange.Year, OpportunityScoringModel.Legacy)
            dispatcher.scheduler.runCurrent()

            assertEquals(0, market.refreshes.get())

            releaseQuotes.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, market.refreshes.get())
            assertEquals(true, client.fetches.get() >= 500)
        } finally {
            releaseQuotes.complete(Unit)
            runCatching { repository.clearAllData() }
            advanceUntilIdle()
            store.close()
        }
    }

    private class RecordingMarketRepository : MarketDataRepository(
        yahooClient = YahooFinanceClient(httpClient = offlineHttpClient()),
        fearGreedClient = CnnFearGreedClient(httpClient = offlineHttpClient()),
    ) {
        val refreshes = AtomicInteger()

        override suspend fun refreshIfStale(symbols: List<String>): MarketRegime {
            refreshes.incrementAndGet()
            return MarketRegime(primaryRegime = "StaleBull", globalConfidenceBps = 8_000)
        }
    }

    private class GateYahooClient(
        private val release: CompletableDeferred<Unit>,
    ) : YahooFinanceClient(httpClient = offlineHttpClient()) {
        val fetches = AtomicInteger()

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            fetches.incrementAndGet()
            release.await()
            val price = 10_000L + symbol.length
            return ProviderFetchResult(
                symbol = symbol,
                snapshot = MarketSnapshot(
                    symbol = symbol,
                    companyName = "$symbol Holdings",
                    profitable = true,
                    marketPriceCents = price,
                    intrinsicValueCents = price + 2_500L,
                ),
                externalSignal = ExternalValuationSignal(
                    symbol = symbol,
                    fairValueCents = price + 2_500L,
                    ageSeconds = 0L,
                ),
                fundamentals = null,
                coverage = ProviderCoverage(
                    core = ProviderComponentState.Fresh,
                    external = ProviderComponentState.Fresh,
                    fundamentals = ProviderComponentState.Missing,
                ),
                diagnostics = emptyList(),
            )
        }

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> =
            emptyList()
    }

    private companion object {
        const val DB_NAME = "profile_load_scheduling_test.sqlite3"
        const val START_EPOCH = 1_700_000_000L
    }
}
