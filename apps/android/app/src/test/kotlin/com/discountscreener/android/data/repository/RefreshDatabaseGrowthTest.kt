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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What a refresh adds to the database when it found nothing new.
 *
 * A refresh used to file a revision per symbol whatever it found. On a two-week-old install
 * 29 809 of the 60 368 revision rows were a byte copy of the row before them, 109 MB of the
 * 216 MB in that table, and the file grew by 23 MB a day. Every one of those bytes was written
 * in front of the next result the refresh wanted to apply, so the app got slower every day it ran.
 *
 * `RevisionHistoryTest` holds the store to that rule. This holds the whole refresh to it. The
 * guard reads `payload_json` and nothing else, so anything that puts a clock reading inside that
 * payload brings the growth back without touching the store at all.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshDatabaseGrowthTest {
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
    fun a_refresh_that_found_the_same_data_files_no_revision() = runBlocking {
        var yahoo = SteadyYahoo()
        var repository = launch(yahoo)
        var filed = revisionCount()

        refreshAgain(repository, yahoo)

        assertEquals(filed, revisionCount())
    }

    /** The other half of the rule, and the proof the first test can fail: a change is still filed. */
    @Test
    fun a_refresh_that_found_a_new_price_files_a_revision() = runBlocking {
        var yahoo = SteadyYahoo()
        var repository = launch(yahoo)
        var filed = revisionCount()

        yahoo.movePrices()
        refreshAgain(repository, yahoo)

        assertTrue("the refresh filed no revision for a price that moved", revisionCount() > filed)
    }

    private suspend fun launch(yahoo: SteadyYahoo): DefaultDashboardRepository {
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
        repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
        awaitReady(repository)
        awaitQuiet(yahoo)
        return repository
    }

    /**
     * A forced refresh, the Refresh button. It buys every quote and every chart again, so the
     * second round has the same work to file as the first and only the content can differ.
     */
    private suspend fun refreshAgain(repository: DefaultDashboardRepository, yahoo: SteadyYahoo) {
        yahoo.clearCounts()
        repository.refreshAll(ViewFilter(), null, ChartRange.Year, model, force = true)
        awaitReady(repository)
        awaitQuiet(yahoo)
    }

    private fun revisionCount(): Long =
        store.writableDatabase.compileStatement("SELECT COUNT(*) FROM symbol_revision").simpleQueryForLong()

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

    /** Waits until the provider stops being asked, so a late pass is counted too. */
    private suspend fun awaitQuiet(yahoo: SteadyYahoo) {
        var quiet = 0
        var lastSeen = -1
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (quiet < QUIET_TICKS && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
            var seen = yahoo.total()
            quiet = if (seen == lastSeen) quiet + 1 else 0
            lastSeen = seen
        }
    }

    private fun deleteFiles() {
        var base = context.getDatabasePath(DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    /** An offline Yahoo that answers the same thing every time until it is told to move. */
    private class SteadyYahoo : YahooFinanceClient(httpClient = offlineHttpClient()) {
        private val calls = AtomicInteger()
        private val offset = AtomicLong()

        fun total(): Int = calls.get()

        fun clearCounts() = calls.set(0)

        fun movePrices() {
            offset.set(PRICE_MOVE_CENTS)
        }

        override suspend fun fetchQuotes(symbols: List<String>): Map<String, QuoteBatchEntry> {
            calls.incrementAndGet()
            return symbols.associateWith { symbol ->
                QuoteBatchEntry(symbol, "$symbol Holdings", priceFor(symbol), true, null)
            }
        }

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            calls.incrementAndGet()
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
            calls.incrementAndGet()
            var close = priceFor(symbol)
            return listOf(HistoricalCandle(1_699_999_000L, close - 50, close + 50, close - 75, close, 1_000))
        }

        override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
            calls.incrementAndGet()
            return richTimeseries()
        }

        private fun priceFor(symbol: String): Long =
            10_000L + symbol.sumOf { it.code }.toLong() + offset.get()
    }

    private companion object {
        const val DB_NAME = "refresh_database_growth.sqlite3"
        const val PROFILE = "qa"
        const val NOW_EPOCH = 1_700_000_000L
        const val PRICE_MOVE_CENTS = 1_500L
        const val POLL_MILLIS = 50L
        const val QUIET_TICKS = 8
        const val DEADLINE_MILLIS = 60_000L
        const val SETTLE_MILLIS = 300L
    }
}
