package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.persistence.EvaluatedSymbolState
import com.discountscreener.android.data.persistence.SymbolRevisionInput
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.QuoteBatchEntry
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.offlineHttpClient
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ProjectedProviderCategory
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DetailRefreshOwnershipTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val model = OpportunityScoringModel.Legacy

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun earlier_bulk_pass_cannot_replace_a_completed_manual_ticker_refresh() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME, ioDispatcher = dispatcher)
        val passStarted = CompletableDeferred<Unit>()
        val releasePass = CompletableDeferred<Unit>()
        val client = object : OfflineYahoo() {
            var aaplCalls = 0

            override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                if (symbol != "AAPL") return quote(symbol, 10_000)
                aaplCalls += 1
                return quote(symbol, if (aaplCalls == 1) 22_000 else 11_000)
            }
        }
        try {
            val repository = repository(store, client) {
                passStarted.complete(Unit)
                releasePass.await()
            }
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model, force = true)
            passStarted.await()

            repository.refreshDetail("AAPL", ViewFilter(), ChartRange.Year, model)
            releasePass.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, client.aaplCalls)
            assertEquals(22_000L, repository.currentSnapshot(ViewFilter(), "AAPL", ChartRange.Year, model)
                .selectedDetail?.marketPriceCents)
            assertEquals(22_000L, store.loadCachedSymbolState("AAPL")?.snapshot?.marketPriceCents)
        } finally {
            releasePass.complete(Unit)
            store.close()
        }
    }

    @Test
    fun rejected_batch_quote_cannot_mark_a_manual_refresh_as_restored_or_count_it_as_kept() =
        runTest(dispatcher) {
            val store = SQLiteStateStore(context, databaseFileName = DB_NAME, ioDispatcher = dispatcher)
            val batchStarted = CompletableDeferred<Unit>()
            val releaseBatch = CompletableDeferred<Unit>()
            val lateQuoteStarted = CompletableDeferred<Unit>()
            val releaseLateQuote = CompletableDeferred<Unit>()
            val client = object : OfflineYahoo() {
                var aaplCalls = 0

                override suspend fun fetchQuotes(symbols: List<String>): Map<String, QuoteBatchEntry> {
                    if ("AAPL" !in symbols) return emptyMap()
                    batchStarted.complete(Unit)
                    releaseBatch.await()
                    return mapOf("AAPL" to QuoteBatchEntry("AAPL", "AAPL Holdings", 11_000, true, null))
                }

                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    if (symbol != "AAPL") return quote(symbol, 10_000)
                    aaplCalls += 1
                    if (aaplCalls == 3) {
                        lateQuoteStarted.complete(Unit)
                        releaseLateQuote.await()
                    }
                    return quote(symbol, if (aaplCalls == 2) 22_000 else 11_000)
                }
            }
            try {
                val repository = repository(store, client)
                repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
                repository.refreshDetail("AAPL", ViewFilter(), ChartRange.Year, model)
                repository.refreshAll(ViewFilter(), null, ChartRange.Year, model, force = false)
                batchStarted.await()
                runCurrent()

                repository.refreshDetail("AAPL", ViewFilter(), ChartRange.Year, model)
                val progressBeforeBatch = repository.currentSnapshot(ViewFilter(), "AAPL", ChartRange.Year, model)
                    .refreshCompletedSymbols
                releaseBatch.complete(Unit)
                runCurrent()

                assertTrue("A rejected batch quote must leave AAPL for the quote round", lateQuoteStarted.isCompleted)
                val during = repository.currentSnapshot(ViewFilter(), "AAPL", ChartRange.Year, model)
                assertEquals(progressBeforeBatch, during.refreshCompletedSymbols)
                assertEquals(ProjectedProviderCategory.Live, during.screenData.providerState.category)

                releaseLateQuote.complete(Unit)
                advanceUntilIdle()
                val final = repository.currentSnapshot(ViewFilter(), "AAPL", ChartRange.Year, model)
                assertEquals(ProjectedProviderCategory.Live, final.screenData.providerState.category)
                assertEquals(22_000L, final.selectedDetail?.marketPriceCents)
            } finally {
                releaseBatch.complete(Unit)
                releaseLateQuote.complete(Unit)
                store.close()
            }
        }

    @Test
    fun ad_hoc_response_from_the_old_profile_cannot_enter_the_new_profile() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME, ioDispatcher = dispatcher)
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val client = object : OfflineYahoo() {
            override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                if (symbol == "SHOP") {
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                }
                return quote(symbol, 22_000)
            }
        }
        try {
            val repository = repository(store, client)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            val oldDetail = launch {
                repository.ensureDetailLoaded("SHOP", ViewFilter(), ChartRange.Year, model)
            }
            fetchStarted.await()
            repository.selectProfile("merval", ViewFilter(), ChartRange.Year, model)
            releaseFetch.complete(Unit)
            oldDetail.join()
            advanceUntilIdle()

            val snapshot = repository.currentSnapshot(ViewFilter(), "SHOP", ChartRange.Year, model)
            assertEquals("merval", snapshot.currentProfile)
            assertNull(snapshot.selectedDetail)
            assertEquals(emptyList<String>(), snapshot.candidateRows.map { it.symbol }.filter { it == "SHOP" })
        } finally {
            releaseFetch.complete(Unit)
            store.close()
        }
    }

    @Test
    fun delayed_ad_hoc_response_cannot_replace_a_newer_manual_ticker_refresh() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME, ioDispatcher = dispatcher)
        val adHocStarted = CompletableDeferred<Unit>()
        val releaseAdHoc = CompletableDeferred<Unit>()
        val client = object : OfflineYahoo() {
            var shopCalls = 0

            override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                if (symbol != "SHOP") return quote(symbol, 10_000)
                shopCalls += 1
                if (shopCalls == 1) {
                    adHocStarted.complete(Unit)
                    releaseAdHoc.await()
                    return quote(symbol, 11_000)
                }
                return quote(symbol, 22_000)
            }
        }
        try {
            val repository = repository(store, client)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            val adHocDetail = launch {
                repository.ensureDetailLoaded("SHOP", ViewFilter(), ChartRange.Year, model)
            }
            adHocStarted.await()

            repository.refreshDetail("SHOP", ViewFilter(), ChartRange.Year, model)
            releaseAdHoc.complete(Unit)
            adHocDetail.join()
            advanceUntilIdle()

            assertEquals(2, client.shopCalls)
            assertEquals(22_000L, repository.currentSnapshot(ViewFilter(), "SHOP", ChartRange.Year, model)
                .selectedDetail?.marketPriceCents)
            assertEquals(22_000L, store.loadCachedSymbolState("SHOP")?.snapshot?.marketPriceCents)
        } finally {
            releaseAdHoc.complete(Unit)
            store.close()
        }
    }

    @Test
    fun delayed_detail_chart_cannot_replace_a_newer_manual_chart_refresh() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME, ioDispatcher = dispatcher)
        val oldChartStarted = CompletableDeferred<Unit>()
        val releaseOldChart = CompletableDeferred<Unit>()
        val client = object : OfflineYahoo() {
            var monthCalls = 0

            override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                if (symbol != "SHOP" || range != ChartRange.Month) return emptyList()
                monthCalls += 1
                val price = if (monthCalls == 1) {
                    oldChartStarted.complete(Unit)
                    releaseOldChart.await()
                    11_000L
                } else {
                    22_000L
                }
                return listOf(HistoricalCandle(1_699_999_000L, price, price, price, price, 1_000))
            }
        }
        try {
            val repository = repository(store, client)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshDetail("SHOP", ViewFilter(), ChartRange.Year, model)
            val oldDetail = launch {
                repository.ensureDetailLoaded("SHOP", ViewFilter(), ChartRange.Month, model)
            }
            oldChartStarted.await()

            repository.refreshDetail("SHOP", ViewFilter(), ChartRange.Month, model)
            releaseOldChart.complete(Unit)
            oldDetail.join()
            advanceUntilIdle()

            assertEquals(2, client.monthCalls)
            assertEquals(22_000L, repository.currentSnapshot(ViewFilter(), "SHOP", ChartRange.Month, model)
                .selectedCharts[ChartRange.Month]?.lastOrNull()?.closeCents)
            assertEquals(22_000L, store.loadPricingHistory("SHOP")
                .first { it.range == ChartRange.Month }.candles.last().closeCents)
        } finally {
            releaseOldChart.complete(Unit)
            store.close()
        }
    }

    @Test
    fun delayed_detail_dcf_cannot_replace_a_newer_manual_ticker_refresh() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME, ioDispatcher = dispatcher)
        val oldDcfStarted = CompletableDeferred<Unit>()
        val releaseOldDcf = CompletableDeferred<Unit>()
        val client = object : OfflineYahoo() {
            var timeseriesCalls = 0

            override suspend fun fetchSymbol(symbol: String): ProviderFetchResult =
                quote(symbol, 22_000).copy(fundamentals = dcfFundamentals(symbol))

            override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                timeseriesCalls += 1
                if (symbol == "SHOP" && timeseriesCalls == 1) {
                    oldDcfStarted.complete(Unit)
                    releaseOldDcf.await()
                    return dcfTimeseries(32_000_000_000.0)
                }
                return dcfTimeseries(46_000_000_000.0)
            }
        }
        try {
            val repository = repository(store, client)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            val oldDetail = launch {
                repository.ensureDetailLoaded("SHOP", ViewFilter(), ChartRange.Year, model)
            }
            oldDcfStarted.await()

            repository.refreshDetail("SHOP", ViewFilter(), ChartRange.Year, model)
            val afterRefresh = repository.currentSnapshot(ViewFilter(), "SHOP", ChartRange.Year, model)
            assertEquals(46_000_000_000L, afterRefresh.selectedHistory.last().dcfAnalysis?.latestFcfDollars)

            releaseOldDcf.complete(Unit)
            oldDetail.join()
            advanceUntilIdle()

            val final = repository.currentSnapshot(ViewFilter(), "SHOP", ChartRange.Year, model)
            assertEquals(46_000_000_000L, final.selectedHistory.last().dcfAnalysis?.latestFcfDollars)
            assertEquals(46_000_000_000L, store.loadCachedSymbolState("SHOP")?.dcfAnalysis?.latestFcfDollars)
        } finally {
            releaseOldDcf.complete(Unit)
            store.close()
        }
    }

    @Test
    fun detail_dcf_cannot_use_old_fundamentals_after_a_later_bulk_quote_applies() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME, ioDispatcher = dispatcher)
        val oldDcfStarted = CompletableDeferred<Unit>()
        val releaseOldDcf = CompletableDeferred<Unit>()
        val bulkStarted = CompletableDeferred<Unit>()
        val releaseBulk = CompletableDeferred<Unit>()
        val oldFundamentals = dcfFundamentals("AAPL")
        val newFundamentals = oldFundamentals.copy(betaMillis = 1_100)
        val client = object : OfflineYahoo() {
            var aaplTimeseriesCalls = 0

            override suspend fun fetchSymbol(symbol: String): ProviderFetchResult =
                if (symbol == "AAPL") quote(symbol, 22_000).copy(fundamentals = newFundamentals)
                else quote(symbol, 10_000)

            override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                if (symbol != "AAPL") return richTimeseries()
                aaplTimeseriesCalls += 1
                if (aaplTimeseriesCalls == 1) {
                    oldDcfStarted.complete(Unit)
                    releaseOldDcf.await()
                    return dcfTimeseries(32_000_000_000.0)
                }
                return dcfTimeseries(46_000_000_000.0)
            }
        }
        try {
            store.persistBatch(
                rawCaptures = emptyList(),
                revisions = listOf(
                    SymbolRevisionInput(
                        symbol = "AAPL",
                        evaluatedAt = 1_700_000_000L,
                        lastSequence = 1,
                        updateCount = 1,
                        priceHistory = emptyList(),
                        payload = EvaluatedSymbolState(
                            snapshot = MarketSnapshot("AAPL", "AAPL Holdings", true, 11_000, 13_500),
                            fundamentals = oldFundamentals,
                        ),
                    ),
                ),
            )
            val repository = repository(store, client) {
                bulkStarted.complete(Unit)
                releaseBulk.await()
            }
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model, force = true)
            bulkStarted.await()
            val oldDetail = launch {
                repository.ensureDetailLoaded("AAPL", ViewFilter(), ChartRange.Year, model)
            }
            oldDcfStarted.await()

            releaseBulk.complete(Unit)
            advanceUntilIdle()
            assertEquals(newFundamentals, repository.currentSnapshot(ViewFilter(), "AAPL", ChartRange.Year, model)
                .selectedDetail?.fundamentals)

            releaseOldDcf.complete(Unit)
            oldDetail.join()
            advanceUntilIdle()

            val final = repository.currentSnapshot(ViewFilter(), "AAPL", ChartRange.Year, model)
            assertEquals(46_000_000_000L, final.selectedHistory.last().dcfAnalysis?.latestFcfDollars)
            assertEquals(46_000_000_000L, store.loadCachedSymbolState("AAPL")?.dcfAnalysis?.latestFcfDollars)
        } finally {
            releaseOldDcf.complete(Unit)
            releaseBulk.complete(Unit)
            store.close()
        }
    }

    @Test
    fun detail_chart_remains_on_disk_when_a_newer_quote_arrives_during_dcf_work() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME, ioDispatcher = dispatcher)
        val oldDcfStarted = CompletableDeferred<Unit>()
        val releaseOldDcf = CompletableDeferred<Unit>()
        val client = object : OfflineYahoo() {
            var quoteCalls = 0
            var yearChartCalls = 0
            var timeseriesCalls = 0

            override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                if (symbol != "SHOP") return quote(symbol, 10_000)
                quoteCalls += 1
                return if (quoteCalls == 1) {
                    quote(symbol, 11_000).copy(fundamentals = dcfFundamentals(symbol))
                } else {
                    quote(symbol, 22_000)
                }
            }

            override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                if (symbol != "SHOP" || range != ChartRange.Year) return emptyList()
                yearChartCalls += 1
                return if (yearChartCalls == 2) {
                    listOf(HistoricalCandle(1_699_999_000L, 11_000, 11_000, 11_000, 11_000, 1_000))
                } else {
                    emptyList()
                }
            }

            override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                timeseriesCalls += 1
                if (symbol == "SHOP" && timeseriesCalls == 1) {
                    oldDcfStarted.complete(Unit)
                    releaseOldDcf.await()
                }
                return richTimeseries()
            }
        }
        try {
            val repository = repository(store, client)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            val oldDetail = launch {
                repository.ensureDetailLoaded("SHOP", ViewFilter(), ChartRange.Year, model)
            }
            oldDcfStarted.await()
            assertEquals(11_000L, repository.currentSnapshot(ViewFilter(), "SHOP", ChartRange.Year, model)
                .selectedCharts[ChartRange.Year]?.lastOrNull()?.closeCents)

            repository.refreshDetail("SHOP", ViewFilter(), ChartRange.Year, model)
            releaseOldDcf.complete(Unit)
            oldDetail.join()
            advanceUntilIdle()

            assertEquals(22_000L, repository.currentSnapshot(ViewFilter(), "SHOP", ChartRange.Year, model)
                .selectedDetail?.marketPriceCents)
            val reopened = repository(store, client)
            reopened.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            val restored = reopened.loadCachedDetail("SHOP", ViewFilter(), ChartRange.Year, model)
            assertEquals(11_000L, restored.selectedCharts[ChartRange.Year]?.lastOrNull()?.closeCents)
        } finally {
            releaseOldDcf.complete(Unit)
            store.close()
        }
    }

    @Test
    fun older_bulk_year_chart_cannot_replace_a_newer_manual_year_chart() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME, ioDispatcher = dispatcher)
        val bulkChartStarted = CompletableDeferred<Unit>()
        val releaseBulkChart = CompletableDeferred<Unit>()
        val client = object : OfflineYahoo() {
            var aaplQuotes = 0
            var aaplCharts = 0

            override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                if (symbol != "AAPL") return quote(symbol, 10_000)
                aaplQuotes += 1
                return quote(symbol, if (aaplQuotes == 1) 11_000 else 22_000)
            }

            override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                if (symbol != "AAPL" || range != ChartRange.Year) return emptyList()
                aaplCharts += 1
                val price = if (aaplCharts == 1) {
                    bulkChartStarted.complete(Unit)
                    releaseBulkChart.await()
                    11_000L
                } else {
                    22_000L
                }
                return listOf(HistoricalCandle(1_699_999_000L, price, price, price, price, 1_000))
            }
        }
        try {
            val repository = repository(store, client)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model, force = true)
            bulkChartStarted.await()

            repository.refreshDetail("AAPL", ViewFilter(), ChartRange.Year, model)
            releaseBulkChart.complete(Unit)
            advanceUntilIdle()

            assertEquals(22_000L, repository.currentSnapshot(ViewFilter(), "AAPL", ChartRange.Year, model)
                .selectedCharts[ChartRange.Year]?.lastOrNull()?.closeCents)
            assertEquals(22_000L, store.loadPricingHistory("AAPL")
                .first { it.range == ChartRange.Year }.candles.last().closeCents)
        } finally {
            releaseBulkChart.complete(Unit)
            store.close()
        }
    }

    @Test
    fun older_bulk_enrichment_dcf_cannot_replace_a_newer_manual_dcf() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME, ioDispatcher = dispatcher)
        val bulkDcfStarted = CompletableDeferred<Unit>()
        val releaseBulkDcf = CompletableDeferred<Unit>()
        val client = object : OfflineYahoo() {
            var aaplTimeseriesCalls = 0

            override suspend fun fetchSymbol(symbol: String): ProviderFetchResult =
                if (symbol == "AAPL") quote(symbol, 22_000).copy(fundamentals = dcfFundamentals(symbol))
                else quote(symbol, 10_000)

            override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                if (symbol != "AAPL") return richTimeseries()
                aaplTimeseriesCalls += 1
                if (aaplTimeseriesCalls == 1) {
                    bulkDcfStarted.complete(Unit)
                    releaseBulkDcf.await()
                    return dcfTimeseries(32_000_000_000.0)
                }
                return dcfTimeseries(46_000_000_000.0)
            }
        }
        try {
            val repository = repository(store, client)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model, force = true)
            bulkDcfStarted.await()

            repository.refreshDetail("AAPL", ViewFilter(), ChartRange.Year, model)
            val afterManual = repository.currentSnapshot(ViewFilter(), "AAPL", ChartRange.Year, model)
            assertEquals(46_000_000_000L, afterManual.selectedHistory.last().dcfAnalysis?.latestFcfDollars)

            releaseBulkDcf.complete(Unit)
            advanceUntilIdle()

            val final = repository.currentSnapshot(ViewFilter(), "AAPL", ChartRange.Year, model)
            assertEquals(46_000_000_000L, final.selectedHistory.last().dcfAnalysis?.latestFcfDollars)
            assertEquals(46_000_000_000L, store.loadCachedSymbolState("AAPL")?.dcfAnalysis?.latestFcfDollars)
        } finally {
            releaseBulkDcf.complete(Unit)
            store.close()
        }
    }

    @Test
    fun chart_response_from_the_old_profile_cannot_enter_the_new_profile() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME, ioDispatcher = dispatcher)
        val chartStarted = CompletableDeferred<Unit>()
        val releaseChart = CompletableDeferred<Unit>()
        val client = object : OfflineYahoo() {
            var blockChart = false

            override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                if (symbol != "SHOP" || !blockChart) return emptyList()
                chartStarted.complete(Unit)
                releaseChart.await()
                return listOf(HistoricalCandle(1_699_999_000L, 21_900, 22_100, 21_800, 22_000, 1_000))
            }
        }
        try {
            val repository = repository(store, client)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshDetail("SHOP", ViewFilter(), ChartRange.Year, model)
            client.blockChart = true
            val oldDetail = launch {
                repository.ensureDetailLoaded("SHOP", ViewFilter(), ChartRange.Year, model)
            }
            chartStarted.await()
            repository.selectProfile("merval", ViewFilter(), ChartRange.Year, model)
            releaseChart.complete(Unit)
            oldDetail.join()
            advanceUntilIdle()

            val snapshot = repository.currentSnapshot(ViewFilter(), "SHOP", ChartRange.Year, model)
            assertEquals("merval", snapshot.currentProfile)
            assertNull(snapshot.selectedDetail)
            assertEquals(emptyList<HistoricalCandle>(), snapshot.selectedCharts[ChartRange.Year].orEmpty())
        } finally {
            releaseChart.complete(Unit)
            store.close()
        }
    }

    private fun repository(
        store: SQLiteStateStore,
        client: YahooFinanceClient,
        afterRefreshPassRegistered: (suspend () -> Unit)? = null,
    ) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = client,
        universeCatalog = UniverseCatalog(context.assets),
        nowProvider = { 1_700_000_000L },
        ioDispatcher = dispatcher,
        computeDispatcher = dispatcher,
        defaultProfile = "qa",
        afterRefreshPassRegistered = afterRefreshPassRegistered,
    )

    private open class OfflineYahoo : YahooFinanceClient(httpClient = offlineHttpClient()) {
        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult = quote(symbol, 10_000)

        override suspend fun fetchQuotes(symbols: List<String>): Map<String, QuoteBatchEntry> = emptyMap()

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> =
            emptyList()

        protected fun quote(symbol: String, price: Long) = ProviderFetchResult(
            symbol = symbol,
            snapshot = MarketSnapshot(symbol, "$symbol Holdings", true, price, price + 2_500),
            companyName = "$symbol Holdings",
            externalSignal = ExternalValuationSignal(symbol, price + 2_500, 0),
            fundamentals = null,
            coverage = ProviderCoverage(
                core = ProviderComponentState.Fresh,
                external = ProviderComponentState.Fresh,
                fundamentals = ProviderComponentState.Missing,
            ),
            diagnostics = emptyList(),
        )
    }

    private fun dcfTimeseries(latestFcf: Double): FundamentalTimeseries {
        val complete = richTimeseries()
        return complete.copy(
            freeCashFlow = complete.freeCashFlow.dropLast(1) +
                AnnualReportedValue("2023-01-01", latestFcf),
        )
    }

    private companion object {
        const val DB_NAME = "detail_refresh_ownership.sqlite3"
    }
}
