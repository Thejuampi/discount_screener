package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.offlineHttpClient
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.delay
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
 * What does the first load cost, and what is the cost made of?
 *
 * Juan reports the first load is far too slow, and names eager DCF work as the suspect. The refresh
 * used to answer slowness with a hand-set concurrency of four, a number nobody measured.
 *
 * This bench separates the two. The provider is a server with a real limit: it accepts
 * [SERVER_LIMIT] calls at once and answers the rest with HTTP 429, the shape the app already reads
 * (`isRateLimitDetail`). Latency is a suspending `delay`, so the reading is about the window and not
 * about how many threads the build machine has.
 *
 * The split that decides the fix:
 * - `refresh.symbol` is the fetch of one symbol, and those run as many at a time as the window.
 * - `refresh.round` is the whole round, fetch plus the scoring and the writes that follow it.
 *
 * If the round costs about the sum of the fetches divided by the window, the network decides and an
 * adaptive window is the fix. If the round costs far more, the local work per symbol decides and a
 * larger window buys nothing.
 *
 * This file prints. It asserts only what cannot be an artefact of the machine.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshWindowBenchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = OpportunityScoringModel.Legacy
    private val logger = StageRecordingLogger()

    @Before
    fun setUp() {
        deleteBenchFiles()
    }

    @After
    fun tearDown() {
        deleteBenchFiles()
    }

    /**
     * The first load of a profile of [BENCH_SYMBOLS] symbols, against a server that would take four
     * times what the app asks of it.
     */
    @Test
    fun a_first_load_reports_what_the_window_costs() = runBlocking {
        var store = SQLiteStateStore(context, databaseFileName = BENCH_DB_NAME)
        var client = LimitedYahooClient()
        var repository = repository(store, client)
        try {
            var millis = measureTimeMillis {
                repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
                repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
                awaitRound(client)
            }

            var samples = logger.stageSamples()
            var fetches = samples["refresh.symbol"].orEmpty()
            var fetchTotal = fetches.sum()
            var rounds = samples["refresh.round"].orEmpty()
            var roundTotal = rounds.sum()
            println(
                buildString {
                    appendLine("First load bench (DEF-08, symptom 3: the round advances slowly)")
                    appendLine("Profile: $BENCH_PROFILE, $BENCH_SYMBOLS symbols.")
                    appendLine("Server: $SERVER_LIMIT calls at once, $LATENCY_MILLIS ms each.")
                    appendLine("Symbols reached: ${client.symbolsDone.get()}")
                    appendLine("Peak calls in flight: ${client.peakInFlight.get()}")
                    appendLine("Rejected with 429: ${client.rejected.get()}")
                    appendLine("Fetches measured: ${fetches.size}, total $fetchTotal ms, slowest ${fetches.maxOrNull()} ms")
                    appendLine("Fetch time the window hid: ${fetchTotal / maxOf(1, client.peakInFlight.get())} ms")
                    appendLine("Rounds: ${rounds.size}, total $roundTotal ms")
                    appendLine("First symbol applied at: ${samples["refresh.first-symbol"]?.firstOrNull()} ms")
                    appendLine("Time to the whole load: $millis ms")
                },
            )

            assertTrue("no symbol was fetched", client.symbolsDone.get() > 0)
        } finally {
            stop(repository, store)
        }
    }

    /**
     * The same load against a server that refuses almost everything.
     *
     * This is the case Juan named: when Yahoo answers nothing, `fetchRefreshResult` finds
     * `snapshot == null` and calls `resolveDcfFallback`, which asks for a fundamentals timeseries
     * per symbol. The refusal is what makes the app do the expensive thing, so the load gets slower
     * exactly when the provider is already saying stop.
     */
    @Test
    fun a_refused_load_reports_what_the_fallback_adds() = runBlocking {
        var store = SQLiteStateStore(context, databaseFileName = BENCH_DB_NAME)
        var client = LimitedYahooClient(limit = CHOKED_LIMIT)
        var repository = repository(store, client)
        try {
            var millis = measureTimeMillis {
                repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
                repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
                awaitRound(client)
            }

            var samples = logger.stageSamples()
            var fetches = samples["refresh.symbol"].orEmpty()
            println(
                buildString {
                    appendLine("Refused load bench (DEF-08: the fallback the refusal turns on)")
                    appendLine("Profile: $BENCH_PROFILE, $BENCH_SYMBOLS symbols.")
                    appendLine("Server: $CHOKED_LIMIT calls at once, $LATENCY_MILLIS ms each.")
                    appendLine("Rejected with 429: ${client.rejected.get()}")
                    appendLine("Fallback timeseries calls: ${client.timeseriesCalls.get()}")
                    appendLine("Fetches measured: ${fetches.size}, total ${fetches.sum()} ms, slowest ${fetches.maxOrNull()} ms")
                    appendLine("Rounds: ${samples["refresh.round"].orEmpty().size}, total ${samples["refresh.round"].orEmpty().sum()} ms")
                    appendLine("Time to the whole load: $millis ms")
                },
            )

            assertTrue("the refusal was never reached", client.rejected.get() > 0)
        } finally {
            stop(repository, store)
        }
    }

    /**
     * Waits for the round rather than for a clock, so a slow machine reports slow and stays true.
     * [DEADLINE_MILLIS] is not a reading; it stops the bench from spending the whole Gradle task
     * timeout, which would print nothing at all.
     */
    private suspend fun awaitRound(client: LimitedYahooClient) {
        var idleTicks = 0
        var lastSeen = -1
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (idleTicks < IDLE_TICKS_TO_CALL_IT_DONE && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
            var seen = client.symbolsDone.get()
            idleTicks = if (seen == lastSeen && seen > 0) idleTicks + 1 else 0
            lastSeen = seen
        }
    }

    private suspend fun stop(repository: DefaultDashboardRepository, store: SQLiteStateStore) {
        runCatching { repository.clearAllData() }
        delay(SETTLE_MILLIS)
        runCatching { repository.clearAllData() }
        store.close()
    }

    private fun repository(store: SQLiteStateStore, client: YahooFinanceClient) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = client,
        universeCatalog = UniverseCatalog(context.assets),
        nowProvider = { 1_700_000_000L },
        defaultProfile = BENCH_PROFILE,
        logger = logger,
    )

    private fun deleteBenchFiles() {
        var base = context.getDatabasePath(BENCH_DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    companion object {
        private const val BENCH_DB_NAME = "refresh_window_bench.sqlite3"

        /** The QA universe: large enough for the window to matter, small enough to stay quick. */
        private const val BENCH_PROFILE = "qa"
        private const val BENCH_SYMBOLS = 20

        private const val SERVER_LIMIT = LimitedYahooClient.DEFAULT_LIMIT

        /** A server with almost nothing left to give, which is what a rate limit looks like. */
        private const val CHOKED_LIMIT = 2
        private const val LATENCY_MILLIS = LimitedYahooClient.DEFAULT_LATENCY_MILLIS
        private const val POLL_MILLIS = 50L
        private const val IDLE_TICKS_TO_CALL_IT_DONE = 6
        private const val DEADLINE_MILLIS = 90_000L
        private const val SETTLE_MILLIS = 300L
    }
}
