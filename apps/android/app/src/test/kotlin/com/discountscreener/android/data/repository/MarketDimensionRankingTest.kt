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
import com.discountscreener.android.domain.model.ScoreJournalRow
import com.discountscreener.android.domain.model.ScoringPreferences
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
import com.discountscreener.core.regime.RegimeScoreStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The scoring controls, measured where they are supposed to have an effect: the ranked list.
 *
 * Two controls, one fixture: the market switch, and the model chip. Both claim to change what the
 * user sees, and both are wired through the same snapshot call, so they are asserted against the
 * same twenty names rather than against two fixtures that could drift apart.
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
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MarketDimensionRankingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    /**
     * A clock that ticks, and it has to tick for one of these tests to mean anything.
     *
     * [rendering_the_list_again_adds_no_journal_entry] counts distinct journal timestamps. Under a
     * frozen clock a journal written thirteen times carries thirteen rows at one second, the
     * primary key folds them into one, and the count reads exactly like a journal written once — so
     * the assertion could not fail on the defect it exists to catch. A second per reading is enough
     * to tell one pass from many, and far too little to move any staleness threshold in the suite.
     */
    private var clock = NOW

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

    /**
     * The fourth chip is a model, not a label.
     *
     * Asserted on the ordered symbols rather than on `state.opportunityScoringModel`, for the same
     * reason the switch is: a chip that set a field the scoring never read would pass a state
     * assertion and change nothing a user can see.
     */
    @Test
    fun choosing_v4_reranks_the_list_against_v3() = runTest(dispatcher) {
        withRepository { repository ->
            var underV3 = rankedSymbols(repository)

            assertNotEquals(underV3, rankedSymbols(repository, OpportunityScoringModel.AggressiveV4))
        }
    }

    /**
     * What V4 scores this fixture, symbol by symbol. The level, not the order.
     *
     * [choosing_v4_reranks_the_list_against_v3] is not enough on its own, and two mutations say so.
     * Replacing V4's composite with a constant leaves it green, because a list of equal scores still
     * sorts differently from V3's — "different" is satisfied by broken.
     *
     * **An order assertion would have been no better, and measuring said so before this test was
     * written.** V4 scores these twenty names 46, 46, 46, then 45 thirteen times, then 44: the
     * composite is nearly flat here, because the fixture gives every symbol identical fundamentals
     * on purpose. `buildRows` therefore settles the order on its fourth tiebreak, `upsideBps`, and
     * the list comes out in discount order whatever the composite says. Deleting the centre term
     * from V4 changes every score and moves no symbol.
     *
     * So the pairs are asserted. A composite that stops computing fails on the numbers, which is
     * the thing that has to be right, rather than on a sequence the discount already decided.
     */
    @Test
    fun v4_scores_the_fixture_at_one_named_level() = runTest(dispatcher) {
        withRepository { repository ->
            assertEquals(
                V4_LEVEL,
                snapshot(repository, OpportunityScoringModel.AggressiveV4)
                    .opportunityRows.map { it.symbol to it.compositeScore },
            )
        }
    }

    @Test
    fun v3_scores_the_fixture_at_one_named_level() = runTest(dispatcher) {
        withRepository { repository ->
            assertEquals(
                V3_LEVEL,
                snapshot(repository, OpportunityScoringModel.AggressiveV3)
                    .opportunityRows.map { it.symbol to it.compositeScore },
            )
        }
    }

    /**
     * The journal is only worth having if something writes to it. A perfect table that nothing
     * calls looks identical, from the store's own tests, to a table that works.
     *
     * Asserted here rather than in `DefaultDashboardRepositoryTest`, and the reason is worth
     * recording: that suite's fake client qualifies no symbol at all, so its Opportunities list is
     * empty and a journal test there passes or fails on the fixture instead of on the wiring. This
     * fixture puts seventeen names on the list.
     */
    @Test
    fun a_refresh_journals_the_scores_it_produced() = runTest(dispatcher) {
        withRepository { repository ->
            assertEquals(
                rankedSymbols(repository).sorted(),
                journal().map { it.symbol }.sorted(),
            )
        }
    }

    /**
     * Off the render path, asserted rather than intended.
     *
     * A snapshot is rebuilt on every state change, several times a second while data arrives. If
     * the journal were written there it would record one scoring pass a dozen times under a dozen
     * timestamps, and the comparison it exists for would be reading its own sampling rate.
     *
     * Counted rather than compared against the pre-render reading, and that is the whole point of
     * the assertion. `before == after` is satisfied by `[] == []`, so a journal that wrote nothing
     * at all would pass it — measured, not supposed: the mutation that makes the write a no-op left
     * that form green. **One** is the only number that means one pass, written once.
     */
    @Test
    fun rendering_the_list_again_adds_no_journal_entry() = runTest(dispatcher) {
        withRepository { repository ->
            repeat(3) { snapshot(repository) }

            assertEquals(1, journal().map { it.scoredAtEpochSeconds }.distinct().size)
        }
    }

    /**
     * Persist writes the flag. It must not tick the dashboard. A persist tick is what lets a
     * snapshot started under the previous scoring control finish after the user changed it.
     */
    @Test
    fun persisting_preferences_does_not_emit_a_dashboard_update() = runTest(dispatcher) {
        withRepository { repository ->
            val before = repository.observeUpdates().first()
            repository.persistScoringPreferences(ScoringPreferences(regimeScoringEnabled = false))
            assertEquals(before, repository.observeUpdates().first())
        }
    }

    /**
     * Persist is a preference write. It must not wait on [DefaultDashboardRepository] snapshot
     * work. A persist that joins `stateMutex` freezes the scoring chip behind the load loop.
     */
    @Test
    fun persist_returns_while_a_snapshot_is_in_flight() = runTest(dispatcher) {
        var entered = CompletableDeferred<Unit>()
        var release = CompletableDeferred<Unit>()
        var pauseSnapshots = false
        var store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            var repository = buildRepository(store) {
                if (pauseSnapshots) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.AggressiveV3)
            advanceUntilIdle()
            pauseSnapshots = true
            var snapshot = async { rankedSymbols(repository) }
            entered.await()
            withTimeout(200) {
                repository.persistScoringPreferences(ScoringPreferences(regimeScoringEnabled = false))
            }
            release.complete(Unit)
            snapshot.await()
            assertEquals(false, repository.loadScoringPreferences().regimeScoringEnabled)
        } finally {
            store.close()
        }
    }

    /** A preference that reached the ranking must also have reached the database. */
    @Test
    fun the_switch_is_written_where_a_cold_start_will_find_it() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            val repository = buildRepository(store)
            repository.persistScoringPreferences(ScoringPreferences(regimeScoringEnabled = false))
            assertEquals(false, store.loadScoringPreferences().regimeScoringEnabled)
        } finally {
            store.close()
        }
    }

    /**
     * Read through a second store instance, on purpose. The journal exists to be read long after
     * the process that wrote it has gone, so a cold read is the read worth testing.
     */
    private suspend fun journal(): List<ScoreJournalRow> {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            return store.loadScoreJournal()
        } finally {
            store.close()
        }
    }

    private suspend fun rankedSymbols(
        repository: DefaultDashboardRepository,
        model: OpportunityScoringModel = OpportunityScoringModel.AggressiveV3,
    ): List<String> = snapshot(repository, model).opportunityRows.map { it.symbol }

    private suspend fun snapshot(
        repository: DefaultDashboardRepository,
        model: OpportunityScoringModel = OpportunityScoringModel.AggressiveV3,
    ) = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)

    private suspend fun TestScope.withRepository(
        block: suspend (DefaultDashboardRepository) -> Unit,
    ) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher)
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

    private fun buildRepository(
        store: SQLiteStateStore,
        beforeSnapshotLocked: (suspend () -> Unit)? = null,
    ) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = FixtureYahooFinanceClient(),
        universeCatalog = UniverseCatalog(context.assets),
        nowProvider = { clock++ },
        ioDispatcher = dispatcher,
        defaultProfile = DefaultDashboardRepository.QA_PROFILE,
        marketDataRepository = StubMarketDataRepository(),
        beforeSnapshotLocked = beforeSnapshotLocked,
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
            dailyCandles(symbol)

        /**
         * Empty, and it is a fix rather than a convenience.
         *
         * This override did not exist, so the DCF source coordinator called straight through to the
         * real client and this test **fetched T and AMZN from Yahoo on every run** — the two names
         * of twenty whose path reaches the timeseries. A ranking test whose inputs arrive over the
         * internet is not a ranking test: it is slower than the rest of the suite put together, it
         * fails when somebody else's service is down or rate-limits the build, and the [V4_LEVEL]
         * numbers below were recorded from whatever those two issuers reported on the day of the
         * green run. Nothing said so, because the leak was silent by design — a defaulted
         * `httpClient` in the superclass constructor and one unoverridden method.
         *
         * Empty is the right fixture and not a weakening: it is what the model already sees for the
         * eighteen other symbols here, so all twenty now enter scoring the same way. That the
         * recorded levels for T and AMZN did not move is worth reading twice — the live fetch was
         * paying for data the composite never used.
         */
        override suspend fun fetchFundamentalTimeseries(symbol: String) = FundamentalTimeseries()
    }

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
        const val NOW = 1_700_000_000L

        val QA_SYMBOLS = listOf(
            "T", "AMZN", "AAPL", "CI", "JPM", "ACGL", "MSFT", "NVDA", "UNH", "JNJ",
            "XOM", "BAC", "V", "WMT", "GOOGL", "META", "TSLA", "HD", "PG", "MRK",
        )

        /**
         * What V4 scores [QA_SYMBOLS], read off a green run rather than predicted.
         *
         * Written out so that a change to the composite has to be looked at and re-recorded here on
         * purpose, instead of passing under an assertion that only asked for "not V3". The flat
         * middle is the fixture's doing rather than the model's: every symbol carries identical
         * fundamentals, so under V4 each one sits exactly on its own sector centre and every
         * sector-relative factor — leverage included — scores zero for all twenty.
         *
         * V4 last moved when its leverage component changed from book debt/equity to net debt over
         * EBITDA, which took the level from 46/45/44 down to 38/37/36. The eight points were being
         * paid by a debt/equity reading of 40, a number the ingestion cannot produce.
         *
         * It moved again when the cash-vote dedup retired the conversion term after an FCF-yield
         * vote: the fixture's names share one conversion reading, so its uniform +5 came off every
         * row at once — 38/37/36 down to 33/32/31.
         */
        val V3_LEVEL = listOf(
            "MSFT" to 45, "ACGL" to 45, "JPM" to 45, "CI" to 45,
            "JNJ" to 44, "UNH" to 44, "NVDA" to 44,
            "META" to 43, "GOOGL" to 43, "WMT" to 43, "V" to 43, "BAC" to 43, "XOM" to 43,
            "MRK" to 42, "PG" to 42, "HD" to 42,
            "TSLA" to 42,
        )

        val V4_LEVEL = listOf(
            "MRK" to 33, "PG" to 33, "HD" to 33,
            "TSLA" to 32, "META" to 32, "GOOGL" to 32, "WMT" to 32, "V" to 32,
            "BAC" to 32, "XOM" to 32, "JNJ" to 32, "UNH" to 32, "NVDA" to 32,
            "MSFT" to 32, "ACGL" to 32, "JPM" to 32,
            "CI" to 31,
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
            // 40B of net debt over 40B of EBITDA is one turn. V4 reads leverage in dollars, so
            // without this the golden level below would be recorded off V4's fallback path instead
            // of the one production takes.
            ebitdaDollars = 40_000_000_000L,
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
