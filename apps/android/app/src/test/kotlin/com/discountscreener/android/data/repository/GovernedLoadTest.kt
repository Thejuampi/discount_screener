package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.CountingYahooHttp
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A whole load against a server that really does have a limit.
 *
 * The old design held one permit for a whole symbol and retried inside it. The window then counted
 * a symbol that was refused four times as one call in flight, so it never learned the limit it was
 * being told about, and it kept sending.
 *
 * Two readings, because they are not the same claim and the first one alone would have been a
 * comfortable lie. Measured against a mutation that puts the retry back inside the permit:
 *
 * - Rows live: 20 of 20 both ways. The load survives either design, so this test proves nothing
 *   about the redesign. It is kept because a load that comes back short is a product defect.
 * - Refusals: 12, 13, 13 as it ships; 30, 41, 41 mutated. The spread inside each design is at most
 *   one call, so the gap is the design and not the machine. This is the reading that measures it.
 *
 * The server answers from fixtures over the real [YahooFinanceClient], so the crumb handshake, the
 * chart call and the retry are the shipped ones. No socket is opened.
 */
@RunWith(RobolectricTestRunner::class)
class GovernedLoadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = OpportunityScoringModel.Legacy

    @Before
    fun setUp() = deleteFiles()

    @After
    fun tearDown() = deleteFiles()

    /** The product claim: a limited server slows the load down and does not shorten it. */
    @Test
    fun every_symbol_arrives_when_the_server_serves_only_a_few_at_a_time() = runBlocking {
        var http = CountingYahooHttp(
            latencyMillis = LATENCY_MILLIS,
            concurrencyLimit = SERVER_LIMIT,
            retryAfterSeconds = RETRY_AFTER_SECONDS,
        )
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var repository = repository(store, YahooFinanceClient(httpClient = http.client))
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            var live = awaitLiveRows(repository)

            println(
                buildString {
                    appendLine("Governed load: $PROFILE_SYMBOLS symbols, server serves $SERVER_LIMIT at once")
                    appendLine("Yahoo round trips: ${http.total()}")
                    appendLine("Refused with 429: ${http.refusals.get()}")
                    appendLine("Peak calls in flight: ${http.peakInFlight.get()}")
                    appendLine("Rows live: $live of $PROFILE_SYMBOLS")
                },
            )

            assertEquals("the limited server cost the load some symbols", PROFILE_SYMBOLS, live)
        } finally {
            runCatching { repository.clearAllData() }
            delay(SETTLE_MILLIS)
            store.close()
        }
    }

    /**
     * The design claim: a refusal teaches the window, so the load stops asking for more than the
     * server gives. Less than one refusal per symbol is the line, and the ceiling is what a load
     * that never learns costs: it keeps a refused call in flight and sends the next one anyway.
     */
    @Test
    fun a_refusal_teaches_the_window_instead_of_being_paid_again_per_symbol() = runBlocking {
        var http = CountingYahooHttp(
            latencyMillis = LATENCY_MILLIS,
            concurrencyLimit = SERVER_LIMIT,
            retryAfterSeconds = RETRY_AFTER_SECONDS,
        )
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var repository = repository(store, YahooFinanceClient(httpClient = http.client))
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            awaitLiveRows(repository)

            var refusals = http.refusals.get()
            println("Refused with 429: $refusals over ${http.total()} round trips for $PROFILE_SYMBOLS symbols")

            assertTrue(
                "$refusals refusals for $PROFILE_SYMBOLS symbols: the window is not learning the limit",
                refusals < PROFILE_SYMBOLS,
            )
        } finally {
            runCatching { repository.clearAllData() }
            delay(SETTLE_MILLIS)
            store.close()
        }
    }

    /**
     * The quota claim. Yahoo, measured on a device on 2026-08-18: it serves at full speed and then
     * answers 429 with no `Retry-After` in ten milliseconds, and a window closed to one is still
     * sixty-five refused calls a second. Once the window is closed and the provider still says no,
     * the load has to stop asking: one probe, then two seconds later, then four, then eight.
     *
     * The reading is the calls sent in a five-second window that opens two seconds after the quota
     * tripped, so the burst that was already in flight is out of it. The ladder puts at most two
     * probes in that window; a load that keeps asking puts in tens.
     */
    @Test
    fun a_quota_that_says_no_without_a_retry_after_is_not_hammered() = runBlocking {
        var http = CountingYahooHttp(latencyMillis = FAST_REFUSAL_MILLIS, refuseAfter = QUOTA_CALLS)
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var repository = repository(store, YahooFinanceClient(httpClient = http.client))
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            var tripped = awaitFirstRefusal(http)
            delay(QUIET_WINDOW_START_MILLIS + QUIET_WINDOW_MILLIS)

            var windowStart = tripped + QUIET_WINDOW_START_MILLIS
            var inWindow = http.requests.filter { request ->
                request.atMillis in windowStart until windowStart + QUIET_WINDOW_MILLIS
            }
            var sentInWindow = inWindow.size
            println(
                "Quota tripped after $QUOTA_CALLS calls; $sentInWindow calls sent in the " +
                    "${QUIET_WINDOW_MILLIS / 1000} s after the first ${QUIET_WINDOW_START_MILLIS / 1000} s; " +
                    "${http.refusals.get()} refused of ${http.total()} in all; in the window: " +
                    inWindow.joinToString { request -> "${request.kind}@${request.atMillis - tripped}" },
            )

            assertTrue(
                "$sentInWindow calls in $QUIET_WINDOW_MILLIS ms against a provider that keeps saying no: " +
                    "the load is hammering a closed quota",
                sentInWindow <= QUIET_WINDOW_MAX_CALLS,
            )
        } finally {
            runCatching { repository.clearAllData() }
            delay(SETTLE_MILLIS)
            store.close()
        }
    }

    /**
     * The chart pass of a refresh used to start when the quote pass had ended, and the quote pass
     * ended when its retry rounds had: one symbol the server would not quote held every chart back
     * for the four backoffs of its rounds. Measured on a device on 2026-08-18: twenty-four seconds
     * of idle wire behind one symbol. The charts start beside the retries now.
     *
     * The reading is the gap between the last quote the server answered and the first chart it was
     * asked for. What remains is the straggler's own attempts inside the first round.
     */
    @Test
    fun the_charts_do_not_wait_for_the_retry_rounds_of_a_straggler() = runBlocking {
        var http = CountingYahooHttp(latencyMillis = LATENCY_MILLIS, brokenSymbols = setOf(STRAGGLER))
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var repository = repository(store, YahooFinanceClient(httpClient = http.client))
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            awaitFirstChart(http)

            var lastAnsweredQuoteAt = http.requests
                .filter { request -> request.kind == CountingYahooHttp.QUOTE_SUMMARY && request.symbol != STRAGGLER }
                .maxOf { request -> request.atMillis }
            // The straggler's own chart is asked for inside the quote round, as the stand-in for
            // its missing quote; the chart pass is the first chart of anyone else.
            var firstChartAt = http.requests
                .first { request -> request.kind == CountingYahooHttp.CHART && request.symbol != STRAGGLER }
                .atMillis
            var gapMillis = firstChartAt - lastAnsweredQuoteAt
            println(
                "Straggler $STRAGGLER answered 503; first chart asked $gapMillis ms after the last " +
                    "answered quote; straggler quote attempts before it: " +
                    http.requests.count { request ->
                        request.symbol == STRAGGLER && request.kind == CountingYahooHttp.QUOTE_SUMMARY &&
                            request.atMillis < firstChartAt
                    },
            )

            assertTrue(
                "the first chart waited $gapMillis ms after the last answered quote: " +
                    "the chart pass is behind the straggler's retry rounds",
                gapMillis <= CHART_START_MAX_GAP_MILLIS,
            )
        } finally {
            runCatching { repository.clearAllData() }
            delay(SETTLE_MILLIS)
            store.close()
        }
    }

    private suspend fun awaitFirstChart(http: CountingYahooHttp) {
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (http.requests.any { request -> request.kind == CountingYahooHttp.CHART && request.symbol != STRAGGLER }) {
                return
            }
            delay(POLL_MILLIS)
        }
    }

    private suspend fun awaitFirstRefusal(http: CountingYahooHttp): Long {
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (http.refusals.get() == 0 && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
        }
        return System.currentTimeMillis()
    }

    private suspend fun awaitLiveRows(repository: DefaultDashboardRepository): Int {
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        var live = 0
        while (live < PROFILE_SYMBOLS && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
            live = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
                .trackedRows.count { row -> row.state == TrackedRowState.Live }
        }
        return live
    }

    private fun repository(store: SQLiteStateStore, client: YahooFinanceClient) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = client,
        universeCatalog = UniverseCatalog(context.assets),
        nowProvider = { 1_700_000_000L },
        defaultProfile = PROFILE,
    )

    private fun deleteFiles() {
        var base = context.getDatabasePath(DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    companion object {
        private const val DB_NAME = "governed_load.sqlite3"
        private const val PROFILE = "qa"
        private const val PROFILE_SYMBOLS = 20

        /** Small on purpose: the window has to find it, and finding it is what is under test. */
        private const val SERVER_LIMIT = 2
        private const val LATENCY_MILLIS = 40L

        /** Short, so the reading is about the decision to wait and not about the length of a wait. */
        private const val RETRY_AFTER_SECONDS = 1L
        /** A quota: this many calls answered, every one after them refused, as fast as Yahoo does. */
        private const val QUOTA_CALLS = 10
        private const val FAST_REFUSAL_MILLIS = 10L
        private const val QUIET_WINDOW_START_MILLIS = 2_000L
        private const val QUIET_WINDOW_MILLIS = 5_000L

        /**
         * After the quota trips, the hold ladder is 1, 2 and 4 s, so five seconds of quiet holds
         * at most three probes. A closed window alone sent sixty-five refused calls a second.
         */
        private const val QUIET_WINDOW_MAX_CALLS = 3
        private const val STRAGGLER = "MRK"

        /**
         * The straggler's attempts inside the first round: four for the quote and, the quote being
         * empty, four for the chart, each with a full-jitter backoff up to 0.4, 0.8 and 1.6 s. That
         * is under six seconds at the worst draw and about three at the middle; read 3 980 and
         * 6 654 ms on two runs. The retry rounds behind it come to more than twenty: read 40 962 ms
         * before the chart pass was moved beside them.
         */
        private const val CHART_START_MAX_GAP_MILLIS = 10_000L
        private const val POLL_MILLIS = 50L
        private const val DEADLINE_MILLIS = 120_000L
        private const val SETTLE_MILLIS = 300L
    }
}
