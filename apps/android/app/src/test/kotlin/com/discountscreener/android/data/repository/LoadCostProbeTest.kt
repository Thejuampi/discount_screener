package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.CountingYahooHttp
import com.discountscreener.android.data.remote.FundamentalTimeseriesProvider
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * What one symbol costs the load, counted at the socket.
 *
 * Juan reports the first load is still far too slow, and that an older build reached about ten
 * times the tickers. The window benches cannot answer that: they replace [YahooFinanceClient] with
 * a fake, so one symbol is one call by construction. Here the shipped client runs whole, over a
 * fake server that counts every request, and the SEC provider counts the symbols that reach it.
 *
 * The reading that matters is round trips per symbol. A window can only divide the wait; it cannot
 * remove a call the code makes.
 */
@RunWith(RobolectricTestRunner::class)
class LoadCostProbeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = OpportunityScoringModel.Legacy

    @Before
    fun setUp() {
        deleteFiles()
    }

    @After
    fun tearDown() {
        deleteFiles()
    }

    @Test
    fun a_first_load_reports_the_round_trips_it_spends_per_symbol() = runBlocking {
        var http = CountingYahooHttp()
        var sec = CountingSecProvider()
        var store = SQLiteStateStore(context, databaseFileName = PROBE_DB_NAME)
        var repository = repository(store, YahooFinanceClient(httpClient = http.client), sec)
        try {
            var millis = measureTimeMillis {
                repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
                repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
                awaitQuiet(http, sec)
            }

            println(
                buildString {
                    appendLine("Load cost probe: $PROBE_SYMBOLS symbols on '$PROBE_PROFILE'")
                    appendLine("Server: answers every call in ${CountingYahooHttp.DEFAULT_LATENCY_MILLIS} ms.")
                    http.calls.toSortedMap().forEach { (kind, count) ->
                        appendLine("  $kind: ${count.get()}")
                    }
                    appendLine("Yahoo round trips: ${http.total()} (${perSymbol(http.total())} per symbol)")
                    appendLine("SEC companyfacts fetches: ${sec.calls.get()} (${perSymbol(sec.calls.get())} per symbol)")
                    appendLine("Peak calls in flight: ${http.peakInFlight.get()}")
                    appendLine("Time inside the network: ${http.networkMillis.get()} ms")
                    appendLine("Time to the whole load: $millis ms")
                },
            )

            assertTrue("the probe reached no symbol", http.count(CountingYahooHttp.QUOTE_SUMMARY) > 0)
        } finally {
            stop(repository, store)
        }
    }


    /**
     * The product budget: `sp500` must finish its first load inside twenty seconds.
     *
     * The shipped [YahooFinanceClient] parses real quoteSummary fixtures. There is no injected
     * sleep on the socket: the wall clock is parse, persist, scoring, and the snapshot collector
     * the presenter runs. A fake Yahoo client would hide that cost.
     */
    @Test
    fun an_sp500_load_completes_within_twenty_seconds() = runBlocking {
        var catalogSize = ProfileCatalog(context.assets).loadProfile(LARGE_PROFILE).size
        var http = CountingYahooHttp(latencyMillis = 0L)
        var sec = CountingSecProvider(latencyMillis = 0L)
        var store = SQLiteStateStore(context, databaseFileName = PROBE_DB_NAME)
        var repository = repository(store, YahooFinanceClient(httpClient = http.client), sec, LARGE_PROFILE)
        var collector: Job? = null
        try {
            var started = System.currentTimeMillis()
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            collector = launch {
                repository.observeUpdates().collectLatest {
                    repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
                }
            }
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            var listReady = awaitLoadDone(repository, http, catalogSize, started)
            var whole = System.currentTimeMillis() - started
            var quoted = http.count(CountingYahooHttp.QUOTE_SUMMARY)
            var inFlight = repository.loadInFlight.value

            println(
                buildString {
                    appendLine("SP500 load budget: '$LARGE_PROFILE', $catalogSize symbols, cap ${TWENTY_SECONDS_MILLIS} ms")
                    http.calls.toSortedMap().forEach { (kind, count) ->
                        appendLine("  $kind: ${count.get()}")
                    }
                    appendLine("Symbols quoted: $quoted")
                    appendLine("SEC companyfacts fetches: ${sec.calls.get()}")
                    appendLine("Yahoo round trips: ${http.total()}")
                    appendLine("Peak calls in flight: ${http.peakInFlight.get()}")
                    appendLine("Time until every symbol was quoted: $listReady ms")
                    appendLine("Time to the whole load: $whole ms")
                    appendLine("Still in flight: $inFlight")
                },
            )

            assertTrue(
                "sp500 first load must finish in ${TWENTY_SECONDS_MILLIS}ms; " +
                    "took ${whole}ms, quoted $quoted of $catalogSize, inFlight=$inFlight",
                catalogSize >= 500 &&
                    quoted >= catalogSize &&
                    !inFlight &&
                    whole <= TWENTY_SECONDS_MILLIS,
            )
        } finally {
            collector?.cancel()
            stop(repository, store)
        }
    }

    /**
     * Waits until the load has started and then until it has ended, or until the budget plus slack.
     * Returns when every symbol was quoted, or 0 if that never happened.
     */
    private suspend fun awaitLoadDone(
        repository: DefaultDashboardRepository,
        http: CountingYahooHttp,
        catalogSize: Int,
        started: Long,
    ): Long {
        var listReady = 0L
        var startDeadline = started + LOAD_START_MILLIS
        while (!repository.loadInFlight.value && System.currentTimeMillis() < startDeadline) {
            delay(POLL_MILLIS)
        }
        var doneDeadline = started + TWENTY_SECONDS_MILLIS + WAIT_SLACK_MILLIS
        while (repository.loadInFlight.value && System.currentTimeMillis() < doneDeadline) {
            if (listReady == 0L && http.count(CountingYahooHttp.QUOTE_SUMMARY) >= catalogSize) {
                listReady = System.currentTimeMillis() - started
            }
            delay(POLL_MILLIS)
        }
        if (listReady == 0L && http.count(CountingYahooHttp.QUOTE_SUMMARY) >= catalogSize) {
            listReady = System.currentTimeMillis() - started
        }
        return listReady
    }

    private fun perSymbol(count: Int): String = String.format("%.1f", count.toDouble() / PROBE_SYMBOLS)

    /** Waits until the server has been quiet for [QUIET_TICKS] ticks, so late passes are counted too. */
    private suspend fun awaitQuiet(http: CountingYahooHttp, sec: CountingSecProvider, quietTicks: Int = QUIET_TICKS) {
        var quiet = 0
        var lastSeen = -1
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (quiet < quietTicks && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
            var seen = http.total() + sec.calls.get()
            quiet = if (seen == lastSeen && seen > 0) quiet + 1 else 0
            lastSeen = seen
        }
    }

    private suspend fun stop(repository: DefaultDashboardRepository, store: SQLiteStateStore) {
        runCatching { repository.clearAllData() }
        delay(SETTLE_MILLIS)
        runCatching { repository.clearAllData() }
        store.close()
    }

    private fun repository(
        store: SQLiteStateStore,
        client: YahooFinanceClient,
        sec: FundamentalTimeseriesProvider,
        profile: String = PROBE_PROFILE,
    ) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = client,
        universeCatalog = UniverseCatalog(context.assets),
        secondaryTimeseriesProvider = sec,
        nowProvider = { 1_700_000_000L },
        defaultProfile = profile,
    )

    private fun deleteFiles() {
        var base = context.getDatabasePath(PROBE_DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    companion object {
        private const val PROBE_DB_NAME = "load_cost_probe.sqlite3"
        private const val PROBE_PROFILE = "qa"
        private const val PROBE_SYMBOLS = 20
        private const val POLL_MILLIS = 50L
        private const val QUIET_TICKS = 8

        private const val DEADLINE_MILLIS = 120_000L
        private const val LARGE_PROFILE = "sp500"
        private const val TWENTY_SECONDS_MILLIS = 20_000L
        /** Past the budget so an overshoot reports its time instead of stopping on the cap. */
        private const val WAIT_SLACK_MILLIS = 5_000L
        private const val LOAD_START_MILLIS = 5_000L
        private const val SETTLE_MILLIS = 300L
    }
}
