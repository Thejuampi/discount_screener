package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.CaptureKind
import com.discountscreener.android.data.persistence.EvaluatedSymbolState
import com.discountscreener.android.data.persistence.MetricGroupStatus
import com.discountscreener.android.data.persistence.RawCapture
import com.discountscreener.android.data.persistence.RawCapturePayload
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.persistence.SymbolRevisionInput
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderDiagnostic
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.PriceHistoryPoint
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultDashboardRepositoryTest {
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
    fun bootstrap_uses_sp500_even_when_db_remembers_single_symbol() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            store.replaceTrackedSymbols(listOf("MSTR"))
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())
            val snapshot = repository.bootstrap(ViewFilter(), null, ChartRange.Year)

            assertEquals("sp500", snapshot.currentProfile)
            assertTrue(snapshot.trackedSymbols.size > 400)
            assertFalse(snapshot.trackedSymbols.contains("MSTR"))
        } finally {
            store.close()
        }
    }

    @Test
    fun bootstrap_restores_cached_rows_and_reorders_by_persisted_ranking() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            seedWarmState(store)
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())
            val snapshot = repository.bootstrap(ViewFilter(), null, ChartRange.Year)

            assertEquals(DashboardStartupPhase.ShowingCached, snapshot.startupPhase)
            assertEquals(listOf("NVDA", "AAPL", "MSFT"), snapshot.trackedRows.take(3).map { it.symbol })
            assertTrue(snapshot.trackedRows.take(3).all { it.state == TrackedRowState.Cached })
            assertEquals("NVIDIA Corporation", snapshot.trackedRows.first().companyName)
            assertEquals(
                "NVIDIA Corporation",
                snapshot.candidateRows.first { it.symbol == "NVDA" }.companyName,
            )
            assertTrue(snapshot.candidateRows.isNotEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun first_launch_shows_loading_rows_then_progressively_refreshes_live_data() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = FakeYahooFinanceClient(delayMs = 25)
            val repository = buildRepository(store = store, client = client)

            val bootstrap = repository.bootstrap(ViewFilter(), null, ChartRange.Year)
            assertTrue(bootstrap.trackedRows.isNotEmpty())
            assertTrue(bootstrap.trackedRows.take(10).all { it.state == TrackedRowState.Loading })

            val scheduled = repository.selectProfile("dow", ViewFilter(), ChartRange.Year)
            assertEquals("dow", scheduled.currentProfile)
            assertEquals(DashboardStartupPhase.SwitchingProfile, scheduled.startupPhase)
            assertEquals("Switching to DOW…", scheduled.statusMessage)
            assertEquals(0, scheduled.refreshCompletedSymbols)
            assertEquals(scheduled.trackedSymbols.size, scheduled.refreshTargetSymbols)
            assertTrue(scheduled.trackedRows.all { it.state == TrackedRowState.Loading })

            val finished = awaitSnapshot(repository) { snapshot ->
                snapshot.trackedRows.any { it.state == TrackedRowState.Live } &&
                    snapshot.candidateRows.isNotEmpty()
            }
            assertTrue(finished.trackedRows.any { it.state == TrackedRowState.Live })
            assertTrue(finished.candidateRows.isNotEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun refresh_keeps_cached_symbol_live_when_quote_html_404s_but_chart_succeeds() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            var quoteHtml404Mode = false
            val client = object : FakeYahooFinanceClient() {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    if (quoteHtml404Mode && symbol == "AAPL") {
                        return ProviderFetchResult(
                            symbol = symbol,
                            snapshot = null,
                            externalSignal = null,
                            fundamentals = null,
                            coverage = ProviderCoverage(
                                core = ProviderComponentState.Missing,
                                external = ProviderComponentState.Missing,
                                fundamentals = ProviderComponentState.Missing,
                            ),
                            diagnostics = listOf(
                                ProviderDiagnostic(
                                    component = "quoteHtml",
                                    kind = "error",
                                    detail = "HTTP 404 for https://finance.yahoo.com/quote/AAPL",
                                    retryable = false,
                                ),
                            ),
                        )
                    }
                    return super.fetchSymbol(symbol)
                }

                override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                    if (quoteHtml404Mode && symbol == "AAPL" && range == ChartRange.Year) {
                        return listOf(
                            HistoricalCandle(
                                epochSeconds = 1_700_000_000L,
                                openCents = 12_100L,
                                highCents = 12_500L,
                                lowCents = 12_000L,
                                closeCents = 12_345L,
                                volume = 1_000L,
                            ),
                        )
                    }
                    return super.fetchHistoricalCandles(symbol, range)
                }
            }
            val repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year)
            awaitSnapshot(repository) { snapshot ->
                snapshot.trackedRows.any { it.symbol == "AAPL" && it.state == TrackedRowState.Live }
            }

            quoteHtml404Mode = true
            repository.refreshAll(ViewFilter(), null, ChartRange.Year)
            val row = awaitSnapshot(repository) { snapshot ->
                snapshot.trackedRows.any {
                    it.symbol == "AAPL" &&
                        it.state == TrackedRowState.Live &&
                        it.marketPriceCents == 12_345L &&
                        it.providerIssue.isNullOrBlank()
                }
            }
                .trackedRows
                .first { it.symbol == "AAPL" }

            assertEquals(TrackedRowState.Live, row.state)
            assertEquals(12_345L, row.marketPriceCents)
            assertTrue(row.providerIssue.isNullOrBlank())
        } finally {
            store.close()
        }
    }

    @Test
    fun refresh_uses_dcf_fallback_for_quote_html_404() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = object : FakeYahooFinanceClient() {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    if (symbol == "AAPL") {
                        return ProviderFetchResult(
                            symbol = symbol,
                            snapshot = null,
                            externalSignal = null,
                            fundamentals = null,
                            coverage = ProviderCoverage(
                                core = ProviderComponentState.Missing,
                                external = ProviderComponentState.Missing,
                                fundamentals = ProviderComponentState.Missing,
                            ),
                            diagnostics = listOf(
                                ProviderDiagnostic(
                                    component = "quoteHtml",
                                    kind = "error",
                                    detail = "HTTP 404 for https://finance.yahoo.com/quote/AAPL",
                                    retryable = false,
                                ),
                            ),
                        )
                    }
                    return super.fetchSymbol(symbol)
                }

                override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                    if (symbol == "AAPL" && range == ChartRange.Year) {
                        return listOf(
                            HistoricalCandle(
                                epochSeconds = 1_700_000_000L,
                                openCents = 12_000L,
                                highCents = 12_800L,
                                lowCents = 11_900L,
                                closeCents = 12_345L,
                                volume = 1_000L,
                            ),
                        )
                    }
                    return super.fetchHistoricalCandles(symbol, range)
                }

                override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                    if (symbol == "AAPL") {
                        return richTimeseries()
                    }
                    return super.fetchFundamentalTimeseries(symbol)
                }
            }
            val repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year)
            val row = awaitSnapshot(repository) { snapshot ->
                snapshot.trackedRows.any {
                    it.symbol == "AAPL" &&
                        it.state == TrackedRowState.Live &&
                        it.marketPriceCents == 12_345L &&
                        (it.intrinsicValueCents ?: 0L) > 12_345L &&
                        it.providerIssue.isNullOrBlank()
                }
            }
                .trackedRows
                .first { it.symbol == "AAPL" }

            assertEquals(TrackedRowState.Live, row.state)
            assertEquals(12_345L, row.marketPriceCents)
            assertTrue((row.intrinsicValueCents ?: 0L) > 12_345L)
            assertTrue(row.providerIssue.isNullOrBlank())
        } finally {
            store.close()
        }
    }

    @Test
    fun refresh_uses_dcf_fallback_without_chart_when_quote_page_has_market_cap() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = object : FakeYahooFinanceClient() {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    if (symbol == "AAPL") {
                        return ProviderFetchResult(
                            symbol = symbol,
                            snapshot = null,
                            externalSignal = null,
                            fundamentals = FundamentalSnapshot(
                                symbol = symbol,
                                marketCapDollars = 600_000_000_000L,
                                sharesOutstanding = 4_800_000_000L,
                            ),
                            coverage = ProviderCoverage(
                                core = ProviderComponentState.Missing,
                                external = ProviderComponentState.Missing,
                                fundamentals = ProviderComponentState.Fresh,
                            ),
                            diagnostics = listOf(
                                ProviderDiagnostic(
                                    component = "core",
                                    kind = "missing",
                                    detail = "core snapshot is missing target mean price",
                                    retryable = false,
                                ),
                            ),
                        )
                    }
                    return super.fetchSymbol(symbol)
                }

                override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                    if (symbol == "AAPL" && range == ChartRange.Year) {
                        return emptyList()
                    }
                    return super.fetchHistoricalCandles(symbol, range)
                }

                override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                    if (symbol == "AAPL") {
                        return richTimeseries()
                    }
                    return super.fetchFundamentalTimeseries(symbol)
                }
            }
            val repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year)
            val row = awaitSnapshot(repository) { snapshot ->
                snapshot.trackedRows.any {
                    it.symbol == "AAPL" &&
                        it.state == TrackedRowState.Live &&
                        it.marketPriceCents == 12_500L &&
                        (it.intrinsicValueCents ?: 0L) > 12_500L &&
                        it.providerIssue.isNullOrBlank()
                }
            }
                .trackedRows
                .first { it.symbol == "AAPL" }

            assertEquals(TrackedRowState.Live, row.state)
            assertEquals(12_500L, row.marketPriceCents)
            assertTrue((row.intrinsicValueCents ?: 0L) > 12_500L)
            assertTrue(row.providerIssue.isNullOrBlank())
        } finally {
            store.close()
        }
    }

    private fun buildRepository(
        store: SQLiteStateStore,
        client: FakeYahooFinanceClient,
    ) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = client,
        nowProvider = { 1_700_000_000L },
        ioDispatcher = dispatcher,
    )

    private suspend fun awaitSnapshot(
        repository: DefaultDashboardRepository,
        selectedSymbol: String? = null,
        selectedRange: ChartRange = ChartRange.Year,
        timeoutMs: Long = 5_000,
        predicate: (com.discountscreener.android.domain.model.DashboardSnapshot) -> Boolean,
    ): com.discountscreener.android.domain.model.DashboardSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        var snapshot = repository.currentSnapshot(ViewFilter(), selectedSymbol, selectedRange)
        while (!predicate(snapshot)) {
            if (System.currentTimeMillis() >= deadline) {
                fail("Timed out waiting for repository snapshot to satisfy predicate; last snapshot=$snapshot")
            }
            Thread.sleep(10)
            dispatcher.scheduler.advanceUntilIdle()
            snapshot = repository.currentSnapshot(ViewFilter(), selectedSymbol, selectedRange)
        }
        return snapshot
    }

    private suspend fun seedWarmState(store: SQLiteStateStore) {
        store.persistBatch(
            rawCaptures = listOf(
                chartCapture("NVDA", 12_000),
                chartCapture("AAPL", 11_000),
                chartCapture("MSFT", 10_000),
            ),
            revisions = listOf(
                revision("NVDA", marketPriceCents = 10_000, intrinsicValueCents = 20_000, gapBps = 5_000),
                revision("AAPL", marketPriceCents = 10_000, intrinsicValueCents = 15_000, gapBps = 3_333),
                revision("MSFT", marketPriceCents = 10_000, intrinsicValueCents = 12_000, gapBps = 1_666),
            ),
        )
        store.replaceWatchlist(listOf("AAPL"))
    }

    private fun chartCapture(symbol: String, closeCents: Long) = RawCapture(
        symbol = symbol,
        captureKind = CaptureKind.ChartCandles,
        scopeKey = ChartRange.Year.name,
        capturedAt = 1_700_000_000L,
        payload = RawCapturePayload.Chart(
            range = ChartRange.Year,
            candles = listOf(
                HistoricalCandle(
                    epochSeconds = 1_699_999_000L,
                    openCents = closeCents - 100,
                    highCents = closeCents + 100,
                    lowCents = closeCents - 200,
                    closeCents = closeCents,
                    volume = 1_000,
                ),
            ),
        ),
    )

    private fun revision(
        symbol: String,
        marketPriceCents: Long,
        intrinsicValueCents: Long,
        gapBps: Int,
    ) = SymbolRevisionInput(
        symbol = symbol,
        evaluatedAt = 1_700_000_000L,
        lastSequence = 1,
        updateCount = 1,
        priceHistory = listOf(PriceHistoryPoint(sequence = 1, marketPriceCents = marketPriceCents)),
        payload = EvaluatedSymbolState(
            snapshot = MarketSnapshot(
                symbol = symbol,
                companyName = companyNameFor(symbol),
                profitable = true,
                marketPriceCents = marketPriceCents,
                intrinsicValueCents = intrinsicValueCents,
            ),
            externalSignal = ExternalValuationSignal(
                symbol = symbol,
                fairValueCents = intrinsicValueCents,
                ageSeconds = 0,
            ),
            gapBps = gapBps,
            qualification = QualificationStatus.Qualified,
            externalStatus = ExternalSignalStatus.Supportive,
            coreStatus = MetricGroupStatus(available = true, stale = false),
            fundamentalsStatus = MetricGroupStatus(available = false, stale = false),
            relativeStatus = MetricGroupStatus(available = false, stale = false),
            dcfStatus = MetricGroupStatus(available = false, stale = false),
            chartStatus = MetricGroupStatus(available = true, stale = false),
            isWatched = symbol == "AAPL",
        ),
    )

    @Test
    fun enrichment_populates_all_chart_ranges_after_refresh() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = FakeYahooFinanceClient(delayMs = 5)
            val repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year)
            val snapshot = awaitSnapshot(repository) { refreshed ->
                refreshed.trackedRows.any { it.state == TrackedRowState.Live }
            }
            val symbol = snapshot.trackedSymbols.first()
            awaitSnapshot(repository, selectedSymbol = symbol) { enriched ->
                ChartRange.entries.all { range ->
                    enriched.selectedCharts[range].orEmpty().isNotEmpty()
                }
            }
            val allCharts = ChartRange.entries.associateWith { range ->
                repository.currentSnapshot(ViewFilter(), symbol, range).selectedCharts[range].orEmpty()
            }
            assertTrue(
                "Expected all chart ranges populated after enrichment, but got: ${allCharts.map { (k, v) -> "$k=${v.size}" }}",
                allCharts.all { (_, candles) -> candles.isNotEmpty() }
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun enrichment_records_issues_for_failed_fetches_without_retry() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            var fetchCount = 0
            val failingClient = object : FakeYahooFinanceClient(delayMs = 5) {
                override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                    fetchCount++
                    if (range != ChartRange.Year) {
                        throw java.io.IOException("enrichment chart $range failed for $symbol")
                    }
                    return super.fetchHistoricalCandles(symbol, range)
                }
            }
            val repository = buildRepository(store = store, client = failingClient)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year)
            val snapshot = awaitSnapshot(repository) { current ->
                current.issues.any { it.key.contains("enrichment") }
            }
            val enrichmentIssues = snapshot.issues.filter { it.key.contains("enrichment") }
            assertTrue("Expected enrichment issues for failed chart fetches", enrichmentIssues.isNotEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun ensure_detail_is_instant_after_enrichment() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            var enrichmentFetchRequestCount = 0
            val countingClient = object : FakeYahooFinanceClient(delayMs = 5) {
                override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                    enrichmentFetchRequestCount++
                    return super.fetchHistoricalCandles(symbol, range)
                }
            }
            val repository = buildRepository(store = store, client = countingClient)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year)
            val switched = awaitSnapshot(repository) { current ->
                current.trackedRows.any { it.state == TrackedRowState.Live }
            }
            val symbol = switched.trackedSymbols.first()
            awaitSnapshot(repository, selectedSymbol = symbol) { enriched ->
                ChartRange.entries.all { range ->
                    enriched.selectedCharts[range].orEmpty().isNotEmpty()
                }
            }

            val afterEnrichment = enrichmentFetchRequestCount

            repository.ensureDetailLoaded(symbol, ViewFilter(), ChartRange.Year)
            advanceUntilIdle()

            val afterDetail = enrichmentFetchRequestCount
            assertEquals(
                "ensureDetailLoaded should not make additional network calls after enrichment",
                afterEnrichment,
                afterDetail
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun fallback_snapshot_uses_latest_chart_close_when_quote_html_is_missing() {
        val store = SQLiteStateStore(context)
        try {
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())

            val fallback = repository.fallbackSnapshotFromCachedDetail(
                symbol = "PODD",
                detail = sampleDetail(
                    symbol = "PODD",
                    marketPriceCents = 9_500L,
                    intrinsicValueCents = 18_500L,
                ),
                chartCandles = listOf(
                    HistoricalCandle(
                        epochSeconds = 1_700_000_000L,
                        openCents = 10_000L,
                        highCents = 10_400L,
                        lowCents = 9_800L,
                        closeCents = 10_250L,
                        volume = 1_000L,
                    ),
                ),
            )

            assertEquals(10_250L, fallback?.marketPriceCents)
            assertEquals(18_500L, fallback?.intrinsicValueCents)
            assertEquals("PODD", fallback?.symbol)
        } finally {
            store.close()
        }
    }

    @Test
    fun quote_html_404_is_suppressible_only_for_quote_page_errors() {
        val store = SQLiteStateStore(context)
        try {
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())

            assertTrue(
                repository.isSuppressibleQuoteHtml404(
                    ProviderDiagnostic(
                        component = "quoteHtml",
                        kind = "error",
                        detail = "HTTP 404 for https://finance.yahoo.com/quote/PODD",
                        retryable = false,
                    ),
                ),
            )
            assertFalse(
                repository.isSuppressibleQuoteHtml404(
                    ProviderDiagnostic(
                        component = "chart",
                        kind = "error",
                        detail = "HTTP 404 for https://query1.finance.yahoo.com/v8/finance/chart/PODD",
                        retryable = false,
                    ),
                ),
            )
        } finally {
            store.close()
        }
    }

    private open class FakeYahooFinanceClient(
        private val delayMs: Long = 0,
    ) : YahooFinanceClient() {
        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            delay(delayMs)
            val price = 10_000L + symbol.sumOf { it.code }.toLong()
            val fair = price + 2_500L
            return ProviderFetchResult(
                symbol = symbol,
                snapshot = MarketSnapshot(
                    symbol = symbol,
                    companyName = companyNameFor(symbol),
                    profitable = true,
                    marketPriceCents = price,
                    intrinsicValueCents = fair,
                ),
                externalSignal = ExternalValuationSignal(
                    symbol = symbol,
                    fairValueCents = fair,
                    ageSeconds = 0,
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

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
            delay(delayMs)
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
            delay(delayMs)
            return FundamentalTimeseries(
                freeCashFlow = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 50_000_000_000.0)),
                operatingCashFlow = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 60_000_000_000.0)),
                capitalExpenditure = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 10_000_000_000.0)),
                dilutedAverageShares = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 5_000_000_000.0)),
                interestExpense = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 1_000_000_000.0)),
                pretaxIncome = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 20_000_000_000.0)),
                taxRateForCalcs = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 0.21)),
                netIncome = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 15_000_000_000.0)),
            )
        }
    }

    companion object {
        private const val DB_NAME = "discount_screener_state.sqlite3"
    }
}

private fun sampleDetail(
    symbol: String,
    marketPriceCents: Long,
    intrinsicValueCents: Long,
) = SymbolDetail(
    symbol = symbol,
    profitable = true,
    marketPriceCents = marketPriceCents,
    intrinsicValueCents = intrinsicValueCents,
    gapBps = 5_000,
    minimumGapBps = 1_500,
    qualification = QualificationStatus.Qualified,
    externalStatus = ExternalSignalStatus.Supportive,
    externalSignalMaxAgeSeconds = 86_400L,
    confidence = ConfidenceBand.High,
    lastSequence = 1,
    updateCount = 1,
    isWatched = false,
    companyName = "$symbol Inc.",
)

private fun companyNameFor(symbol: String): String = when (symbol) {
    "NVDA" -> "NVIDIA Corporation"
    "AAPL" -> "Apple Inc."
    "MSFT" -> "Microsoft Corporation"
    else -> "$symbol Holdings"
}

private fun richTimeseries() = FundamentalTimeseries(
    freeCashFlow = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 30_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 34_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 40_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 46_000_000_000.0),
    ),
    operatingCashFlow = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 70_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 75_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 82_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 90_000_000_000.0),
    ),
    capitalExpenditure = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 10_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 11_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 12_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 13_000_000_000.0),
    ),
    dilutedAverageShares = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 5_100_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 5_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 4_900_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 4_800_000_000.0),
    ),
    interestExpense = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 1_200_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 1_100_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 1_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 900_000_000.0),
    ),
    pretaxIncome = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 60_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 65_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 72_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 80_000_000_000.0),
    ),
    taxRateForCalcs = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 0.21),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 0.21),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 0.21),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 0.21),
    ),
    netIncome = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 48_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 52_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 58_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 64_000_000_000.0),
    ),
)
