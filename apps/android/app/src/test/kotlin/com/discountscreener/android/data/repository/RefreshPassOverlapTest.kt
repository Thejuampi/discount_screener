package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.QuoteBatchEntry
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.offlineHttpClient
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The batch price pass and the quote pass run beside each other, not one after the other.
 *
 * The batch pass used to run to completion before the first `quoteSummary` was asked for. On the
 * 1 937-symbol universe the last price batch landed 8.0 s after the profile switch and the first
 * quote result 8.1 s, so eight of those seconds bought prices and nothing else. A row whose own
 * quote is not fresh needs a `quoteSummary` whatever the batch returns, so it does not wait.
 *
 * A test that reads the clock would call any order fast enough on a quiet machine. Here the batch
 * never answers at all. If the quote pass waits for it, nothing is ever asked and this times out.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshPassOverlapTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = OpportunityScoringModel.Legacy
    private lateinit var store: SQLiteStateStore
    private val open = mutableListOf<DefaultDashboardRepository>()

    @Before
    fun setUp() {
        deleteFiles()
        store = SQLiteStateStore(context, databaseFileName = DB_NAME)
    }

    @After
    fun tearDown() {
        runBlocking {
            open.reversed().forEach { repository -> runCatching { repository.clearAllData() } }
            delay(SETTLE_MILLIS)
        }
        store.close()
        deleteFiles()
    }

    @Test
    fun a_quote_is_asked_while_the_batch_price_is_still_in_flight() = runBlocking {
        coldLaunch()
        var yahoo = StalledBatchYahoo(hangBatch = true)
        launch(yahoo)

        var asked = withTimeoutOrNull(DEADLINE_MILLIS) { yahoo.quoteAsked.await() } != null

        yahoo.answerBatch()
        assertTrue("no quote was asked while the batch price hung", asked)
    }

    /** A warm store, so the batch pass has rows to price. It prices nothing it has never seen. */
    private suspend fun coldLaunch() {
        var yahoo = StalledBatchYahoo(hangBatch = false)
        var repository = launch(yahoo)
        awaitReady(repository)
    }

    private suspend fun launch(yahoo: StalledBatchYahoo): DefaultDashboardRepository {
        var repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = yahoo,
            universeCatalog = UniverseCatalog(context.assets),
            secondaryTimeseriesProvider = CountingSecProvider(),
            nowProvider = { NOW_EPOCH },
            defaultProfile = PROFILE,
        )
        open += repository
        repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
        repository.refreshAll(ViewFilter(), null, ChartRange.Year, model, force = true)
        return repository
    }

    private suspend fun awaitReady(repository: DefaultDashboardRepository) {
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (true) {
            var snapshot = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
            if (snapshot.startupPhase == DashboardStartupPhase.Ready) return
            if (System.currentTimeMillis() >= deadline) {
                fail("Timed out waiting for Ready; last phase=${snapshot.startupPhase}")
            }
            delay(POLL_MILLIS)
        }
    }

    private fun deleteFiles() {
        var base = context.getDatabasePath(DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    /** An offline Yahoo whose batch price call can be held open for as long as the test wants. */
    private class StalledBatchYahoo(hangBatch: Boolean) : YahooFinanceClient(httpClient = offlineHttpClient()) {
        val quoteAsked = CompletableDeferred<Unit>()
        private val batchAnswered = CompletableDeferred<Unit>()

        init {
            if (!hangBatch) batchAnswered.complete(Unit)
        }

        fun answerBatch() {
            batchAnswered.complete(Unit)
        }

        override suspend fun fetchQuotes(symbols: List<String>): Map<String, QuoteBatchEntry> {
            batchAnswered.await()
            return symbols.associateWith { symbol ->
                QuoteBatchEntry(symbol, "$symbol Holdings", priceFor(symbol), true, null)
            }
        }

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            quoteAsked.complete(Unit)
            var price = priceFor(symbol)
            return ProviderFetchResult(
                symbol = symbol,
                snapshot = MarketSnapshot(
                    symbol = symbol,
                    companyName = "$symbol Holdings",
                    profitable = true,
                    marketPriceCents = price,
                    intrinsicValueCents = price + 2_500L,
                ),
                companyName = "$symbol Holdings",
                externalSignal = ExternalValuationSignal(symbol = symbol, fairValueCents = price + 2_500L, ageSeconds = 0),
                fundamentals = dcfFundamentals(symbol),
                coverage = ProviderCoverage(
                    core = ProviderComponentState.Fresh,
                    external = ProviderComponentState.Fresh,
                    fundamentals = ProviderComponentState.Fresh,
                ),
                diagnostics = emptyList(),
            )
        }

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
            var close = priceFor(symbol)
            return listOf(HistoricalCandle(1_699_999_000L, close - 50, close + 50, close - 75, close, 1_000))
        }

        override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries = richTimeseries()

        private fun priceFor(symbol: String): Long = 10_000L + symbol.sumOf { it.code }.toLong()
    }

    private companion object {
        const val DB_NAME = "refresh_pass_overlap.sqlite3"
        const val PROFILE = "qa"
        const val NOW_EPOCH = 1_700_000_000L
        const val POLL_MILLIS = 50L
        const val DEADLINE_MILLIS = 30_000L
        const val SETTLE_MILLIS = 300L
    }
}
