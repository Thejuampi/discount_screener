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
import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.core.engine.MarketParams
import com.discountscreener.core.engine.MarketParamsSource
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The same-day contract of a plain refresh, the one the app asks for on its own at launch.
 *
 * A row whose own quote is less than a day old is kept: it shows as Restored with the time of
 * that quote, it is left out of the refresh count, and it buys no quoteSummary and no chart. The
 * batch price still lands on it, but the batch price is filed apart and never stands in for the
 * quote, so the next day every row is quoted again. A DCF the market moved inside the day is
 * recomputed from the timeseries on file, with no call. The Refresh button, a forced refresh,
 * buys everything again.
 */
@RunWith(RobolectricTestRunner::class)
class SameDayRefreshTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = OpportunityScoringModel.Legacy
    private var now = FIRST_LAUNCH_EPOCH
    private var rfBps = 430
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
    fun a_same_day_reopen_keeps_a_row_restored_with_the_time_of_its_own_quote() = runBlocking {
        var first = coldLaunch()
        var symbol = first.trackedSymbols().first()
        now = FIRST_LAUNCH_EPOCH + THREE_HOURS
        var second = launch(SameDayYahoo())

        var snapshot = awaitSnapshot(second) { it.startupPhase == DashboardStartupPhase.Ready }

        var row = snapshot.trackedRows.first { it.symbol == symbol }
        assertEquals(Pair(RowFreshness.Restored, FIRST_LAUNCH_EPOCH), Pair(row.freshness, row.freshnessAsOfEpochSeconds))
    }

    @Test
    fun a_same_day_reopen_buys_no_quote_and_no_chart() = runBlocking {
        coldLaunch()
        now = FIRST_LAUNCH_EPOCH + THREE_HOURS
        var yahoo = SameDayYahoo()
        var second = launch(yahoo)

        awaitSnapshot(second) { it.startupPhase == DashboardStartupPhase.Ready }
        awaitQuiet(yahoo)

        assertEquals(Pair(0, 0), Pair(yahoo.quoteCalls.get(), yahoo.chartCalls.get()))
    }

    /**
     * The banner counts every row of the profile, and it reports a kept row done.
     *
     * The total used to be the rows this refresh would buy, and two passes set it by two rules,
     * so it moved under the user in the middle of a refresh. Here one row cannot be priced by the
     * batch and must be quoted; the quote hangs. The banner must read "all but that one done, out
     * of all of them", not "0 out of 1".
     */
    @Test
    fun a_same_day_reopen_counts_the_whole_profile_and_reports_a_kept_row_done() = runBlocking {
        var first = coldLaunch()
        var tracked = first.trackedSymbols()
        var unpriced = tracked.first()
        now = FIRST_LAUNCH_EPOCH + THREE_HOURS
        var second = launch(SameDayYahoo(unpricedInBatch = setOf(unpriced), hangQuotes = true))

        var snapshot = awaitSnapshot(second) {
            it.startupPhase == DashboardStartupPhase.Refreshing && it.refreshCompletedSymbols > 0
        }

        assertEquals(
            Pair(tracked.size - 1, tracked.size),
            Pair(snapshot.refreshCompletedSymbols, snapshot.refreshTargetSymbols),
        )
    }

    @Test
    fun a_forced_refresh_buys_every_quote_again() = runBlocking {
        var first = coldLaunch()
        now = FIRST_LAUNCH_EPOCH + THREE_HOURS
        var yahoo = SameDayYahoo()
        var second = launch(yahoo, force = true)

        awaitSnapshot(second) { it.startupPhase == DashboardStartupPhase.Ready }
        awaitQuiet(yahoo)

        assertEquals(first.trackedSymbols().size, yahoo.quoteCalls.get())
    }

    /** The batch price lands on every warm row at every launch; if it counted, no row would ever be quoted again. */
    @Test
    fun a_batch_price_does_not_move_the_day_a_row_was_quoted() = runBlocking {
        var first = coldLaunch()
        now = FIRST_LAUNCH_EPOCH + THREE_HOURS
        var second = launch(SameDayYahoo())
        awaitSnapshot(second) { it.startupPhase == DashboardStartupPhase.Ready }
        // A day and an hour after the quotes; the batch price of the second launch is 22 hours old.
        now = FIRST_LAUNCH_EPOCH + ONE_DAY + ONE_HOUR
        var yahoo = SameDayYahoo()
        var third = launch(yahoo)

        awaitSnapshot(third) { it.startupPhase == DashboardStartupPhase.Ready }
        awaitQuiet(yahoo)

        assertEquals(first.trackedSymbols().size, yahoo.quoteCalls.get())
    }

    /**
     * A one basis point move of the rate changes the market fingerprint and retires every DCF.
     * Inside the day the timeseries on file is the same data Yahoo would send, so the DCF is
     * recomputed from it and the wire stays quiet.
     */
    @Test
    fun a_dcf_the_market_moved_is_recomputed_from_the_file_with_no_call() = runBlocking {
        var first = coldLaunch()
        awaitDcf(first, MarketParams(rfBps = 430).fingerprint())
        now = FIRST_LAUNCH_EPOCH + THREE_HOURS
        rfBps = 431
        var yahoo = SameDayYahoo()
        var second = launch(yahoo)

        awaitDcf(second, MarketParams(rfBps = 431).fingerprint())

        assertEquals(0, yahoo.timeseriesCalls.get())
    }

    /**
     * On a device on 2026-08-19, 354 of 497 S&P 500 rows could not be valued from Yahoo's
     * timeseries, and each of them cost one timeseries call at every launch: the answer was never
     * filed, so the day's copy did not exist. The verdict may be "unavailable"; the data is still
     * a day's worth of data.
     */
    @Test
    fun a_row_the_engine_could_not_value_is_not_asked_for_again_inside_the_day() = runBlocking {
        coldLaunch(SameDayYahoo(timeseries = ::unusableTimeseries))
        now = FIRST_LAUNCH_EPOCH + THREE_HOURS
        var yahoo = SameDayYahoo(timeseries = ::unusableTimeseries)
        var second = launch(yahoo)

        awaitSnapshot(second) { it.startupPhase == DashboardStartupPhase.Ready }
        awaitQuiet(yahoo)

        assertEquals(0, yahoo.timeseriesCalls.get())
    }

    private suspend fun coldLaunch(yahoo: SameDayYahoo = SameDayYahoo()): DefaultDashboardRepository {
        var first = launch(yahoo)
        awaitSnapshot(first) { it.startupPhase == DashboardStartupPhase.Ready }
        awaitQuiet(yahoo)
        return first
    }

    private suspend fun launch(yahoo: SameDayYahoo, force: Boolean = false): DefaultDashboardRepository {
        var repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = yahoo,
            universeCatalog = UniverseCatalog(context.assets),
            secondaryTimeseriesProvider = CountingSecProvider(),
            nowProvider = { now },
            defaultProfile = PROFILE,
            marketParamsSource = MarketParamsSource { MarketParams(rfBps = rfBps) },
        )
        open += repository
        repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
        repository.refreshAll(ViewFilter(), null, ChartRange.Year, model, force = force)
        return repository
    }

    private suspend fun DefaultDashboardRepository.trackedSymbols(): List<String> =
        currentSnapshot(ViewFilter(), null, ChartRange.Year, model).trackedSymbols

    private suspend fun awaitSnapshot(
        repository: DefaultDashboardRepository,
        predicate: (DashboardSnapshot) -> Boolean,
    ): DashboardSnapshot {
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        var snapshot = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
        while (!predicate(snapshot)) {
            if (System.currentTimeMillis() >= deadline) {
                fail("Timed out waiting for a snapshot; last phase=${snapshot.startupPhase} status=${snapshot.statusMessage}")
            }
            delay(POLL_MILLIS)
            snapshot = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
        }
        return snapshot
    }

    /** Waits until every tracked row has a DCF that carries [fingerprint]. */
    private suspend fun awaitDcf(repository: DefaultDashboardRepository, fingerprint: String) {
        var symbols = repository.trackedSymbols()
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (true) {
            var dcf = repository.dcfSnapshot()
            if (symbols.all { symbol -> dcf[symbol]?.reasonCodes?.contains(fingerprint) == true }) return
            if (System.currentTimeMillis() >= deadline) {
                fail("Timed out waiting for DCF $fingerprint; have ${dcf.size} of ${symbols.size}")
            }
            delay(POLL_MILLIS)
        }
    }

    private suspend fun awaitQuiet(yahoo: SameDayYahoo) {
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

    /** An offline Yahoo that counts what it is asked and answers with rows a DCF can be built on. */
    private class SameDayYahoo(
        private val unpricedInBatch: Set<String> = emptySet(),
        private val hangQuotes: Boolean = false,
        private val timeseries: () -> FundamentalTimeseries = ::richTimeseries,
    ) : YahooFinanceClient(httpClient = offlineHttpClient()) {
        val batchCalls = AtomicInteger()
        val quoteCalls = AtomicInteger()
        val chartCalls = AtomicInteger()
        val timeseriesCalls = AtomicInteger()

        fun total(): Int = batchCalls.get() + quoteCalls.get() + chartCalls.get() + timeseriesCalls.get()

        override suspend fun fetchQuotes(symbols: List<String>): Map<String, QuoteBatchEntry> {
            batchCalls.incrementAndGet()
            return symbols.filter { it !in unpricedInBatch }.associateWith { symbol ->
                QuoteBatchEntry(symbol, "$symbol Holdings", priceFor(symbol), true, null)
            }
        }

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            quoteCalls.incrementAndGet()
            if (hangQuotes) awaitCancellation()
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
            chartCalls.incrementAndGet()
            var close = priceFor(symbol)
            return listOf(HistoricalCandle(1_699_999_000L, close - 50, close + 50, close - 75, close, 1_000))
        }

        override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
            timeseriesCalls.incrementAndGet()
            return timeseries()
        }

        private fun priceFor(symbol: String): Long = 10_000L + symbol.sumOf { it.code }.toLong()
    }

    private companion object {
        const val DB_NAME = "same_day_refresh.sqlite3"
        const val PROFILE = "qa"
        const val FIRST_LAUNCH_EPOCH = 1_700_000_000L
        const val ONE_HOUR = 60 * 60L
        const val THREE_HOURS = 3 * ONE_HOUR
        const val ONE_DAY = 24 * 60 * 60L
        const val POLL_MILLIS = 50L
        const val QUIET_TICKS = 8
        const val DEADLINE_MILLIS = 60_000L
        const val SETTLE_MILLIS = 300L
    }
}
