package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic IO ceilings for refresh+enrich on a fixed profile (DOW, ~30 symbols).
 *
 * Historical bulk path (pre-optimization) roughly:
 *   quote N + chart Year N + chart non-Year ~5N + timeseries N ≈ 8N
 * After Opt A/B: bulk non-Year charts = 0; NotEligible thrash reduced on second cycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class IoOptimizationBenchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val legacyModel = OpportunityScoringModel.Legacy

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun dow_refresh_enrich_stays_under_post_optimization_ceilings() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = CountingYahooClient()
            val repository = DefaultDashboardRepository(
                stateStore = store,
                profileCatalog = ProfileCatalog(context.assets),
                yahooClient = client,
                universeCatalog = UniverseCatalog(context.assets),
                nowProvider = { 1_700_000_000L },
                ioDispatcher = dispatcher,
            )

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)
            awaitLive(repository)
            advanceUntilIdle()
            // Allow enrichment to settle.
            repeat(20) {
                advanceUntilIdle()
                Thread.sleep(5)
            }

            val n = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, legacyModel).trackedSymbols.size
            assertTrue("expected DOW-sized profile, got $n", n in 25..40)

            // Pre-opt bulk non-Year would be ~5N; post-opt must be zero during bulk path.
            assertEquals(
                "Bulk enrich must not fetch non-Year chart ranges",
                0,
                client.chartByRange
                    .filterKeys { it != ChartRange.Year }
                    .values
                    .sumOf { it.get() },
            )

            // quote + Year chart roughly 2N; allow some retries/DCF-fallback noise.
            assertTrue(
                "quote fetches too high: ${client.quoteFetches.get()} for n=$n",
                client.quoteFetches.get() <= n * 3L,
            )
            assertTrue(
                "Year chart fetches too high: ${client.chartByRange[ChartRange.Year]?.get()}",
                (client.chartByRange[ChartRange.Year]?.get() ?: 0) <= n * 3L,
            )

            // Total chart+quote+timeseries should be far below legacy ~8N.
            val total = client.quoteFetches.get() + client.chartFetches.get() + client.timeseriesFetches.get()
            val legacyApprox = n * 8L
            assertTrue(
                "total provider calls $total should be well under legacy ~$legacyApprox for n=$n",
                total < legacyApprox * 0.60,
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun detail_open_fetches_missing_ranges_once() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = CountingYahooClient()
            val repository = DefaultDashboardRepository(
                stateStore = store,
                profileCatalog = ProfileCatalog(context.assets),
                yahooClient = client,
                universeCatalog = UniverseCatalog(context.assets),
                nowProvider = { 1_700_000_000L },
                ioDispatcher = dispatcher,
            )

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)
            val snapshot = awaitLive(repository)
            val symbol = snapshot.trackedSymbols.first()
            advanceUntilIdle()
            repeat(10) {
                advanceUntilIdle()
                Thread.sleep(5)
            }

            val chartsBefore = client.chartFetches.get()
            repository.ensureDetailLoaded(symbol, ViewFilter(), ChartRange.Month, legacyModel)
            advanceUntilIdle()
            val chartsAfterFirst = client.chartFetches.get()
            assertTrue(chartsAfterFirst > chartsBefore)

            repository.ensureDetailLoaded(symbol, ViewFilter(), ChartRange.Month, legacyModel)
            advanceUntilIdle()
            assertEquals(chartsAfterFirst, client.chartFetches.get())
        } finally {
            store.close()
        }
    }

    private suspend fun awaitLive(
        repository: DefaultDashboardRepository,
        timeoutMs: Long = 8_000,
    ) = run {
        val deadline = System.currentTimeMillis() + timeoutMs
        var snapshot = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, legacyModel)
        while (snapshot.trackedRows.none { it.state == TrackedRowState.Live }) {
            if (System.currentTimeMillis() >= deadline) {
                fail("Timed out waiting for live rows")
            }
            Thread.sleep(10)
            dispatcher.scheduler.advanceUntilIdle()
            snapshot = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, legacyModel)
        }
        snapshot
    }

    private open class CountingYahooClient : YahooFinanceClient() {
        val quoteFetches = AtomicInteger(0)
        val chartFetches = AtomicInteger(0)
        val timeseriesFetches = AtomicInteger(0)
        val chartByRange = java.util.concurrent.ConcurrentHashMap<ChartRange, AtomicInteger>()

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            quoteFetches.incrementAndGet()
            val price = 10_000L + symbol.sumOf { it.code }.toLong()
            val fair = price + 2_500L
            return ProviderFetchResult(
                symbol = symbol,
                snapshot = MarketSnapshot(
                    symbol = symbol,
                    companyName = "$symbol Holdings",
                    profitable = true,
                    marketPriceCents = price,
                    intrinsicValueCents = fair,
                ),
                companyName = "$symbol Holdings",
                externalSignal = ExternalValuationSignal(
                    symbol = symbol,
                    fairValueCents = fair,
                    ageSeconds = 0,
                ),
                fundamentals = FundamentalSnapshot(
                    symbol = symbol,
                    marketCapDollars = 100_000_000_000L,
                    sharesOutstanding = 1_000_000_000L,
                    betaMillis = 1_000,
                ),
                coverage = ProviderCoverage(
                    core = ProviderComponentState.Fresh,
                    external = ProviderComponentState.Fresh,
                    fundamentals = ProviderComponentState.Fresh,
                ),
                diagnostics = emptyList(),
            )
        }

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
            chartFetches.incrementAndGet()
            chartByRange.getOrPut(range) { AtomicInteger(0) }.incrementAndGet()
            val close = 10_000L + symbol.length * 100L
            return listOf(
                HistoricalCandle(
                    epochSeconds = 1_699_999_000L,
                    openCents = close - 50,
                    highCents = close + 50,
                    lowCents = close - 75,
                    closeCents = close,
                    volume = 1_000,
                ),
            )
        }

        override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
            timeseriesFetches.incrementAndGet()
            return FundamentalTimeseries(
                freeCashFlow = listOf(
                    AnnualReportedValue("2020-01-01", 10_000_000_000.0),
                    AnnualReportedValue("2021-01-01", 12_000_000_000.0),
                    AnnualReportedValue("2022-01-01", 14_000_000_000.0),
                    AnnualReportedValue("2023-01-01", 16_000_000_000.0),
                ),
                dilutedAverageShares = listOf(
                    AnnualReportedValue("2020-01-01", 1_100_000_000.0),
                    AnnualReportedValue("2021-01-01", 1_050_000_000.0),
                    AnnualReportedValue("2022-01-01", 1_000_000_000.0),
                    AnnualReportedValue("2023-01-01", 950_000_000.0),
                ),
            )
        }
    }

    companion object {
        private const val DB_NAME = "discount_screener_state.sqlite3"
    }
}
