package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.market.MarketDataRepository
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.CnnFearGreedClient
import com.discountscreener.android.data.remote.CountingYahooHttp
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.domain.model.MarketReadStatus
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import com.discountscreener.core.regime.MarketRegime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The market read starts when the refresh has ended, so each test lets a refresh run to its end
 * against an offline Yahoo and then waits for the read to land. Real dispatchers, because the
 * refresh runs its calls on I/O threads a test scheduler cannot see.
 */
@RunWith(RobolectricTestRunner::class)
class MarketReadStatusTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun the_first_snapshot_is_pending_while_the_market_read_has_not_run() = runBlocking {
        withRepository(StubMarket()) { repository ->
            assertEquals(
                MarketReadStatus.Pending,
                snapshot(repository).marketReadStatus,
            )
        }
    }

    @Test
    fun a_failed_refresh_is_unavailable() = runBlocking {
        withRepository(FailingMarket()) { repository ->
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            awaitMarketRead(repository)
            assertEquals(MarketReadStatus.Unavailable, snapshot(repository).marketReadStatus)
        }
    }

    @Test
    fun a_successful_refresh_is_ready() = runBlocking {
        withRepository(StubMarket()) { repository ->
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            awaitMarketRead(repository)
            assertEquals(MarketReadStatus.Ready, snapshot(repository).marketReadStatus)
        }
    }

    @Test
    fun a_missing_market_repository_is_unavailable() = runBlocking {
        withRepository(market = null) { repository ->
            assertEquals(MarketReadStatus.Unavailable, snapshot(repository).marketReadStatus)
        }
    }

    @Test
    fun an_unusable_computed_reading_is_still_ready_for_the_tab() = runBlocking {
        withRepository(UnusableMarket()) { repository ->
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            awaitMarketRead(repository)
            assertEquals(MarketReadStatus.Ready, snapshot(repository).marketReadStatus)
        }
    }

    private suspend fun snapshot(repository: DefaultDashboardRepository) =
        repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)

    /** Waits, on the wall clock, until the read has landed or the deadline says it never will. */
    private suspend fun awaitMarketRead(repository: DefaultDashboardRepository) {
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (snapshot(repository).marketReadStatus == MarketReadStatus.Pending && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
        }
    }

    private suspend fun withRepository(
        market: MarketDataRepository?,
        block: suspend (DefaultDashboardRepository) -> Unit,
    ) {
        var store = SQLiteStateStore(context)
        try {
            var repository = DefaultDashboardRepository(
                stateStore = store,
                profileCatalog = ProfileCatalog(context.assets),
                yahooClient = YahooFinanceClient(httpClient = CountingYahooHttp(latencyMillis = 0L).client),
                universeCatalog = UniverseCatalog(context.assets),
                nowProvider = { 1_700_000_000L },
                defaultProfile = DefaultDashboardRepository.QA_PROFILE,
                marketDataRepository = market,
            )
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            try {
                block(repository)
            } finally {
                // The enrichment the refresh started is still writing when the test returns; a
                // write after the store is closed is an uncaught exception charged to whatever
                // test runs next. `clearAllData` stops and joins the profile's work.
                runCatching { repository.clearAllData() }
                delay(SETTLE_MILLIS)
            }
        } finally {
            store.close()
        }
    }

    private class StubMarket : MarketDataRepository(
        yahooClient = YahooFinanceClient(),
        fearGreedClient = CnnFearGreedClient(),
    ) {
        override suspend fun refreshIfStale(symbols: List<String>): MarketRegime = USABLE
        override suspend fun cachedRegime(): MarketRegime = USABLE
    }

    private class UnusableMarket : MarketDataRepository(
        yahooClient = YahooFinanceClient(),
        fearGreedClient = CnnFearGreedClient(),
    ) {
        override suspend fun refreshIfStale(symbols: List<String>): MarketRegime = UNUSABLE
        override suspend fun cachedRegime(): MarketRegime? = null
    }

    private class FailingMarket : MarketDataRepository(
        yahooClient = YahooFinanceClient(),
        fearGreedClient = CnnFearGreedClient(),
    ) {
        override suspend fun refreshIfStale(symbols: List<String>): MarketRegime? {
            error("fixture: market read failed")
        }
    }

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
        const val DEADLINE_MILLIS = 20_000L
        const val POLL_MILLIS = 20L
        const val SETTLE_MILLIS = 300L
        val USABLE = MarketRegime(
            primaryRegime = "Bull",
            environmentBand = "RiskOn",
            actionStance = "HoldTrim",
            globalConfidenceBps = 8000,
        )
        val UNUSABLE = MarketRegime()
    }
}
