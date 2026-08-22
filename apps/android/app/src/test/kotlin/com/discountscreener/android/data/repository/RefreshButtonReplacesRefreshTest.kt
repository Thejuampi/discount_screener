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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The Refresh button replaces the refresh that is running, and never runs beside it.
 *
 * Reported from the device: open the app on sp500, wait for the rows to appear, press Refresh, and
 * the button does nothing you can see. It did start a refresh; it started a *second* one, next to
 * the one the app opens with. Two passes asked Yahoo for the same batch of prices, the log read
 * `refresh.prices.first-batch rows=0` twice and `refresh.prices.done priced=0 of 497`, and the
 * forced pass then bought all 497 quotes one at a time. The batch pass exists to spare exactly
 * those 497 calls, and Yahoo answers 429 when it is asked for too many, so the second pass costs
 * the load twice: once in calls, once in the rate limit those calls spend.
 *
 * `startRefresh` reads the running job, clears it, cancels it, and only then launches the new one.
 * The cancel has to run with the state lock free, because the job being cancelled takes that lock
 * to clean up, and the read of the refresh marks after it goes to the database. So the field sat at
 * null across two suspension points, and a start that landed there found nothing to cancel.
 *
 * The racers below are what makes this fail on purpose rather than on a bad day: with one pair the
 * two calls can serialise by luck and the test would pass against the broken code.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshButtonReplacesRefreshTest {
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
    fun a_refresh_asked_for_while_one_runs_replaces_it() = runBlocking {
        var repository = launch()

        coroutineScope {
            repeat(RACERS) { racer ->
                launch { repository.refreshAll(ViewFilter(), null, ChartRange.Year, model, force = racer > 0) }
            }
        }
        awaitSettled(repository)

        assertEquals(1, repository.peekPeakRefreshPasses())
    }

    private suspend fun launch(): DefaultDashboardRepository {
        var repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = SlowYahoo(),
            universeCatalog = UniverseCatalog(context.assets),
            secondaryTimeseriesProvider = CountingSecProvider(),
            nowProvider = { NOW_EPOCH },
            defaultProfile = PROFILE,
        )
        open += repository
        repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
        return repository
    }

    private suspend fun awaitSettled(repository: DefaultDashboardRepository) {
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (true) {
            var snapshot = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
            if (snapshot.startupPhase == DashboardStartupPhase.Ready && !repository.loadInFlight.value) return
            if (System.currentTimeMillis() >= deadline) {
                fail("Timed out waiting for a settled load; last phase=${snapshot.startupPhase}")
            }
            delay(POLL_MILLIS)
        }
    }

    private fun deleteFiles() {
        var base = context.getDatabasePath(DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    /**
     * An offline Yahoo slow enough that a pass is still running when the next one is asked for.
     * A provider that answers at once would let every racer finish before the next one started,
     * and a peak of one would then say nothing about whether two passes can overlap.
     */
    private class SlowYahoo : YahooFinanceClient(httpClient = offlineHttpClient()) {
        override suspend fun fetchQuotes(symbols: List<String>): Map<String, QuoteBatchEntry> {
            delay(CALL_MILLIS)
            return symbols.associateWith { symbol ->
                QuoteBatchEntry(symbol, "$symbol Holdings", priceFor(symbol), true, null)
            }
        }

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            delay(CALL_MILLIS)
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
            delay(CALL_MILLIS)
            var close = priceFor(symbol)
            return listOf(HistoricalCandle(1_699_999_000L, close - 50, close + 50, close - 75, close, 1_000))
        }

        override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries = richTimeseries()

        private fun priceFor(symbol: String): Long = 10_000L + symbol.sumOf { it.code }.toLong()
    }

    private companion object {
        const val DB_NAME = "refresh_button_replaces_refresh.sqlite3"
        const val PROFILE = "qa"
        const val NOW_EPOCH = 1_700_000_000L

        /** The opening refresh plus seven presses of the button, all asked for at once. */
        const val RACERS = 8
        const val CALL_MILLIS = 40L
        const val POLL_MILLIS = 50L

        /**
         * The same wait the other repository tests take. Eight passes over the qa profile settle in
         * a few seconds alone, and this timed out at 60 s when the whole suite ran beside it: the
         * deadline was measuring the machine. The assertion below is on the peak and not on time.
         */
        const val DEADLINE_MILLIS = 120_000L
        const val SETTLE_MILLIS = 300L
    }
}
