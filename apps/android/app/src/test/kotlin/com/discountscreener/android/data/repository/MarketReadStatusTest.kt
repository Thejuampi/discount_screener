package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.market.MarketDataRepository
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.CnnFearGreedClient
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.domain.model.MarketReadStatus
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import com.discountscreener.core.regime.MarketRegime
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
class MarketReadStatusTest {
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
    fun the_first_snapshot_is_pending_while_the_market_read_has_not_run() = runTest(dispatcher) {
        withRepository(StubMarket()) { repository ->
            assertEquals(
                MarketReadStatus.Pending,
                snapshot(repository).marketReadStatus,
            )
        }
    }

    @Test
    fun a_failed_refresh_is_unavailable() = runTest(dispatcher) {
        withRepository(FailingMarket()) { repository ->
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            advanceUntilIdle()
            assertEquals(MarketReadStatus.Unavailable, snapshot(repository).marketReadStatus)
        }
    }

    @Test
    fun a_successful_refresh_is_ready() = runTest(dispatcher) {
        withRepository(StubMarket()) { repository ->
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            advanceUntilIdle()
            assertEquals(MarketReadStatus.Ready, snapshot(repository).marketReadStatus)
        }
    }

    @Test
    fun a_missing_market_repository_is_unavailable() = runTest(dispatcher) {
        withRepository(market = null) { repository ->
            assertEquals(MarketReadStatus.Unavailable, snapshot(repository).marketReadStatus)
        }
    }

    @Test
    fun an_unusable_computed_reading_is_still_ready_for_the_tab() = runTest(dispatcher) {
        withRepository(UnusableMarket()) { repository ->
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            advanceUntilIdle()
            assertEquals(MarketReadStatus.Ready, snapshot(repository).marketReadStatus)
        }
    }

    private suspend fun snapshot(repository: DefaultDashboardRepository) =
        repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)

    private suspend fun withRepository(
        market: MarketDataRepository?,
        block: suspend (DefaultDashboardRepository) -> Unit,
    ) {
        var store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            var repository = DefaultDashboardRepository(
                stateStore = store,
                profileCatalog = ProfileCatalog(context.assets),
                yahooClient = YahooFinanceClient(),
                universeCatalog = UniverseCatalog(context.assets),
                nowProvider = { 1_700_000_000L },
                ioDispatcher = dispatcher,
                defaultProfile = DefaultDashboardRepository.QA_PROFILE,
                marketDataRepository = market,
            )
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            block(repository)
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
        val USABLE = MarketRegime(
            primaryRegime = "Bull",
            environmentBand = "RiskOn",
            actionStance = "HoldTrim",
            globalConfidenceBps = 8000,
        )
        val UNUSABLE = MarketRegime()
    }
}
