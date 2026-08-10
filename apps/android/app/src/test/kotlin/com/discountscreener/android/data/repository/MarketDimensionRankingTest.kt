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
import com.discountscreener.android.domain.model.ScoringPreferences
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import com.discountscreener.core.regime.MarketRegime
import com.discountscreener.core.regime.RegimeScoreStatus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The runtime switch, measured where it is supposed to have an effect: the ranked list.
 *
 * A test that asserts `state.regimeScoringEnabled == false` after toggling passes on a repository
 * that never reads the flag, so it cannot fail on the defect it exists to catch. These assert the
 * ordered symbols the Opportunities tab would render, which is the thing a broken wiring changes.
 *
 * The fixture is built so the ordering genuinely can flip: two names with near-identical
 * three-bucket scores and opposite beta, in a market whose policy penalises beta. If the fit were
 * silently zero for everyone, [toggling_the_market_dimension_off_reorders_the_list] would fail
 * rather than pass on a coincidence.
 */
@RunWith(RobolectricTestRunner::class)
class MarketDimensionRankingTest {
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
    fun toggling_the_market_dimension_off_reorders_the_list() = runTest(dispatcher) {
        withRepository { repository ->
            val on = rankedSymbols(repository)
            repository.persistScoringPreferences(ScoringPreferences(regimeScoringEnabled = false))
            assertNotEquals(on, rankedSymbols(repository))
        }
    }

    /** Off is off for every row, not merely for the ones whose order happened to change. */
    @Test
    fun toggling_the_market_dimension_off_returns_every_row_to_its_base_score() = runTest(dispatcher) {
        withRepository { repository ->
            rankedSymbols(repository)
            repository.persistScoringPreferences(ScoringPreferences(regimeScoringEnabled = false))
            val rows = snapshot(repository).opportunityRows
            assertTrue(
                "at least one row must carry a base score to compare against",
                rows.isNotEmpty() && rows.all { it.compositeScore == it.compositeScoreBase },
            )
        }
    }

    /** The other direction, so neither test can pass on a repository stuck in one state. */
    @Test
    fun the_dimension_is_included_while_the_switch_is_on() = runTest(dispatcher) {
        withRepository { repository ->
            rankedSymbols(repository)
            val rows = snapshot(repository).opportunityRows
            assertTrue(
                "the fixture must reach Included, or the off-case proves nothing",
                rows.any { it.regimeStatus == RegimeScoreStatus.Included },
            )
        }
    }

    /** A preference that reached the ranking must also have reached the database. */
    @Test
    fun the_switch_is_written_where_a_cold_start_will_find_it() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val repository = buildRepository(store)
            repository.persistScoringPreferences(ScoringPreferences(regimeScoringEnabled = false))
            assertEquals(false, store.loadScoringPreferences().regimeScoringEnabled)
        } finally {
            store.close()
        }
    }

    private suspend fun rankedSymbols(repository: DefaultDashboardRepository): List<String> =
        snapshot(repository).opportunityRows.map { it.symbol }

    private suspend fun snapshot(repository: DefaultDashboardRepository) =
        repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)

    private suspend fun TestScope.withRepository(
        block: suspend (DefaultDashboardRepository) -> Unit,
    ) {
        val store = SQLiteStateStore(context)
        try {
            val repository = buildRepository(store)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            advanceUntilIdle()
            block(repository)
        } finally {
            store.close()
        }
    }

    private fun buildRepository(store: SQLiteStateStore) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = FixtureYahooFinanceClient(),
        universeCatalog = UniverseCatalog(context.assets),
        nowProvider = { NOW },
        ioDispatcher = dispatcher,
        defaultProfile = DefaultDashboardRepository.QA_PROFILE,
        marketDataRepository = StubMarketDataRepository(),
    )

    /**
     * A market that is already read, so the test never depends on network fakes or on the cache's
     * freshness clock — those are [com.discountscreener.android.data.market.MarketDataRepositoryTest]'s
     * subject, and duplicating them here would make this test fail for reasons that are not its own.
     */
    private class StubMarketDataRepository : MarketDataRepository(
        yahooClient = YahooFinanceClient(),
        fearGreedClient = CnnFearGreedClient(),
    ) {
        override suspend fun refreshIfStale(symbols: List<String>): MarketRegime = REGIME

        override suspend fun cachedRegime(): MarketRegime = REGIME

        override suspend fun cachedDailySummaries(): Map<String, ChartRangeSummary> =
            QA_SYMBOLS.associateWith { dailySummary(it) }
    }

    /**
     * The base score and the fit are made to disagree on purpose.
     *
     * Every symbol gets identical fundamentals, so the three original buckets separate the list on
     * one term alone — the discount, which widens with the symbol's index. The daily summaries the
     * fit reads then run the other way: the widest-discount names are the most extended and the
     * most overbought, which is what the market policy marks down.
     *
     * Varying one term for both would prove nothing. Beta, for instance, lowers the base composite
     * through its haircut *and* lowers the fit through the low-beta weight, so a list sorted by beta
     * comes out in the same order either way and a reorder assertion would fail on a correct build.
     */
    private class FixtureYahooFinanceClient : YahooFinanceClient() {
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
            dailyCandles(symbol)
    }

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
        const val NOW = 1_700_000_000L

        val QA_SYMBOLS = listOf(
            "T", "AMZN", "AAPL", "CI", "JPM", "ACGL", "MSFT", "NVDA", "UNH", "JNJ",
            "XOM", "BAC", "V", "WMT", "GOOGL", "META", "TSLA", "HD", "PG", "MRK",
        )

        /**
         * A confident `TrendDeploy` reading, written out rather than computed from a data bundle:
         * the pillars have their own tests, and a hand-built regime makes the policy this test
         * depends on — a beta haircut and a quality tilt — visible in the fixture instead of an
         * emergent property of sixty synthetic price series.
         */
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

        /** Position in [QA_SYMBOLS], which is the one thing every fixture below varies on. */
        fun rank(symbol: String): Long = QA_SYMBOLS.indexOf(symbol).toLong().coerceAtLeast(0L)

        /** Identical for every symbol: the fundamentals bucket must not be what separates the list. */
        fun fundamentals(symbol: String) = FundamentalSnapshot(
            symbol = symbol,
            sectorName = "Technology",
            marketCapDollars = 600_000_000_000L,
            freeCashFlowDollars = 30_000_000_000L,
            operatingCashFlowDollars = 40_000_000_000L,
            totalCashDollars = 10_000_000_000L,
            totalDebtDollars = 50_000_000_000L,
            sharesOutstanding = 4_800_000_000L,
            debtToEquityHundredths = 40,
            returnOnEquityBps = 1_800,
            betaMillis = 1_000,
            forwardPeHundredths = 1_600,
            priceToBookHundredths = 300,
        )

        /**
         * Extension and overboughtness climb with the same index the discount climbs with — so the
         * fit marks down exactly the names the three buckets rank highest.
         */
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

        /** The weekly series the technicals bucket reads: identical for every symbol, by design. */
        fun dailyCandles(symbol: String): List<HistoricalCandle> {
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
