package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.CountingYahooHttp
import com.discountscreener.android.data.remote.YahooFinanceClient
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
 * What the user waits for while a large profile is loading.
 *
 * Juan reports three things about the build of 2026-08-18, none of which the build of 2026-08-04
 * did: a switch away from a loading profile does not start the new one; when it starts, it takes
 * an eternity to bring the first row; and a ticker opened during the load waits until the loop
 * reaches it. Every one of them is a wait behind the bulk load, and every one is measured here the
 * same way: the shipped [YahooFinanceClient] over a server that answers in a phone's round trip
 * and writes down when each call arrived.
 *
 * The large profile is the one that makes the wait visible; the readings are printed so the two
 * builds can be compared, and each assertion binds the wait to what a user would call immediate.
 */
@RunWith(RobolectricTestRunner::class)
class InteractiveLoadProbeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = OpportunityScoringModel.Legacy

    @Before
    fun setUp() = deleteFiles()

    @After
    fun tearDown() = deleteFiles()

    /**
     * Symptoms 1 and 2. A switch in the middle of a load has to stop the old load and start the
     * new one, and the new one's first row has to arrive one round trip after the switch.
     */
    @Test
    fun a_switch_during_a_load_starts_the_new_profile_at_once() = runBlocking {
        var http = CountingYahooHttp(latencyMillis = PHONE_YAHOO_MILLIS, concurrencyLimit = SERVER_LIMIT)
        var store = SQLiteStateStore(context, databaseFileName = PROBE_DB_NAME)
        var repository = repository(store, YahooFinanceClient(httpClient = http.client))
        var newSymbols = ProfileCatalog(context.assets).loadProfile(SECOND_PROFILE).toSet()
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            delay(LOAD_HEAD_START_MILLIS)
            var oldCallsBeforeSwitch = http.total()

            var switchedAt = System.currentTimeMillis()
            var switchMillis = measureTimeMillis {
                repository.selectProfile(SECOND_PROFILE, ViewFilter(), ChartRange.Year, model)
            }
            var firstNewQuoteMillis = awaitFirstQuote(http, newSymbols, switchedAt)
            var allNewQuotedMillis = awaitQuotes(http, newSymbols, switchedAt)
            var oldCallsAfterSwitch = http.requests.count { request ->
                request.atMillis >= switchedAt && request.symbol.isNotBlank() && request.symbol !in newSymbols
            }

            println(
                buildString {
                    appendLine(
                        "Switch under load: '$LARGE_PROFILE' loading, switch to '$SECOND_PROFILE' " +
                            "(${newSymbols.size} symbols)",
                    )
                    appendLine(
                        "Yahoo round trip: $PHONE_YAHOO_MILLIS ms. Old load ran $LOAD_HEAD_START_MILLIS ms, " +
                            "$oldCallsBeforeSwitch calls.",
                    )
                    appendLine("selectProfile returned after: $switchMillis ms")
                    appendLine("First quote of the new profile: $firstNewQuoteMillis ms after the switch")
                    appendLine("Every symbol of the new profile quoted: $allNewQuotedMillis ms after the switch")
                    appendLine("Calls still made for the old profile after the switch: $oldCallsAfterSwitch")
                },
            )

            assertTrue(
                "the new profile's first quote left $firstNewQuoteMillis ms after the switch; " +
                    "immediate is under $IMMEDIATE_MILLIS ms",
                firstNewQuoteMillis in 0 until IMMEDIATE_MILLIS,
            )
        } finally {
            stop(repository, store)
        }
    }

    /**
     * Symptom 3. A ticker the user opens is one symbol against a load of five hundred, and the
     * user is looking at it. It has to come back in a few round trips, whatever the loop is doing.
     */
    @Test
    fun an_opened_symbol_does_not_wait_behind_the_load() = runBlocking {
        var http = CountingYahooHttp(latencyMillis = PHONE_YAHOO_MILLIS, concurrencyLimit = SERVER_LIMIT)
        var store = SQLiteStateStore(context, databaseFileName = PROBE_DB_NAME)
        var yahooLog = StageRecordingLogger()
        var repository = repository(store, YahooFinanceClient(httpClient = http.client, logger = yahooLog))
        var symbols = ProfileCatalog(context.assets).loadProfile(LARGE_PROFILE)
        var opened = symbols.last()
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            delay(LOAD_HEAD_START_MILLIS)

            var detailMillis = measureTimeMillis {
                repository.ensureDetailLoaded(opened, ViewFilter(), ChartRange.Year, model)
            }

            println(
                buildString {
                    appendLine(
                        "Opened symbol under load: '$LARGE_PROFILE' loading, open '$opened' (last of the profile)",
                    )
                    appendLine("Yahoo round trip: $PHONE_YAHOO_MILLIS ms. Load ran $LOAD_HEAD_START_MILLIS ms first.")
                    appendLine("ensureDetailLoaded returned after: $detailMillis ms")
                    appendLine("Yahoo refusals: ${http.refusals.get()}; governor holds: ${yahooLog.lines().count { it.contains("cooldownMillis=") && !it.endsWith("cooldownMillis=0") }}")
                },
            )

            assertTrue(
                "opening '$opened' took $detailMillis ms behind the load; " +
                    "immediate is under $DETAIL_IMMEDIATE_MILLIS ms",
                detailMillis < DETAIL_IMMEDIATE_MILLIS,
            )
        } finally {
            stop(repository, store)
        }
    }

    private suspend fun awaitFirstQuote(http: CountingYahooHttp, symbols: Set<String>, since: Long): Long {
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            var first = http.requests.firstOrNull { request ->
                request.kind == CountingYahooHttp.QUOTE_SUMMARY &&
                    request.symbol in symbols &&
                    request.atMillis >= since
            }
            if (first != null) return first.atMillis - since
            delay(POLL_MILLIS)
        }
        return -1L
    }

    private suspend fun awaitQuotes(http: CountingYahooHttp, symbols: Set<String>, since: Long): Long {
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            var quoted = http.requests
                .filter { request -> request.kind == CountingYahooHttp.QUOTE_SUMMARY && request.atMillis >= since }
                .mapTo(HashSet()) { request -> request.symbol }
            if (quoted.containsAll(symbols)) return System.currentTimeMillis() - since
            delay(POLL_MILLIS)
        }
        return -1L
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
        secondaryTimeseriesProvider = CountingSecProvider(),
        nowProvider = { 1_700_000_000L },
        defaultProfile = LARGE_PROFILE,
    )

    private fun deleteFiles() {
        var base = context.getDatabasePath(PROBE_DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    companion object {
        private const val PROBE_DB_NAME = "interactive_load_probe.sqlite3"
        private const val LARGE_PROFILE = "sp500"
        private const val SECOND_PROFILE = "merval"

        /** One Yahoo JSON over mobile data. */
        private const val PHONE_YAHOO_MILLIS = 300L

        /**
         * A server that serves this many calls at once and refuses the rest with 429. Under a
         * healthy server nothing waits and none of the three symptoms shows; under one with a
         * limit, the window shrinks, every permit is contested, and the line in front of it is
         * where the user's wait lives.
         */
        private const val SERVER_LIMIT = 6

        /** Long enough for the window to open and the load to be well under way. */
        private const val LOAD_HEAD_START_MILLIS = 3_000L

        /** A switch is immediate when the new profile is on the wire inside one round trip. */
        private const val IMMEDIATE_MILLIS = 2 * PHONE_YAHOO_MILLIS

        /**
         * An open is a quote, a chart and a filing: a handful of round trips, no queue.
         *
         * Healthy readings run 1 500 to 1 850 ms: the open's own call is refused once when it is
         * the seventh in flight against a server of six, and that costs one backoff. Behind the
         * whole load the same open took 4 655 ms. The bar sits between the two.
         */
        private const val DETAIL_IMMEDIATE_MILLIS = 8 * PHONE_YAHOO_MILLIS
        private const val POLL_MILLIS = 20L
        private const val DEADLINE_MILLIS = 60_000L
        private const val SETTLE_MILLIS = 500L
    }
}
