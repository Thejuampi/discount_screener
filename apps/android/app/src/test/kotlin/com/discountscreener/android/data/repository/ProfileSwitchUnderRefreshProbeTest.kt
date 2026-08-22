package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.offlineHttpClient
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

/**
 * Does a profile switch wait for the fetches of the profile it is leaving?
 *
 * The plan's second suspect for DEF-08 says it does: [DefaultDashboardRepository] cancels and
 * *joins* the running refresh before it publishes anything of the new profile. A cancelled
 * coroutine that sits in a socket read does not end when it is cancelled; it ends when the read
 * returns. This probe measures the claim instead of arguing it.
 *
 * The provider is offline and blocks its calling thread for [FETCH_MILLIS], which is what a socket
 * read does to the coroutine that joins it. A suspending `delay` would not do: it cancels at once,
 * and it would report a mechanism the app does not have.
 *
 * Real milliseconds, so nothing here asserts a threshold. It prints two switch times, one against
 * an idle repository and one against a repository with fetches in flight, and their ratio is the
 * finding.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileSwitchUnderRefreshProbeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = OpportunityScoringModel.Legacy

    @Before
    fun setUp() {
        deleteBenchFiles()
    }

    @After
    fun tearDown() {
        deleteBenchFiles()
    }

    @Test
    fun a_switch_reports_what_it_waits_for() = runBlocking {
        val store = SQLiteStateStore(context, databaseFileName = BENCH_DB_NAME)
        val client = BlockingYahooClient()
        val repository = repository(store, client)
        try {

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            // Nothing in flight: the local cost of a switch on its own.
            val idleMillis = measureTimeMillis {
                repository.selectProfile(SECOND_PROFILE, ViewFilter(), ChartRange.Year, model)
            }

            // Now with fetches running. The switch starts one, so the wait is one round of them.
            repository.selectProfile(SMALL_PROFILE, ViewFilter(), ChartRange.Year, model)
            delay(SETTLE_MILLIS)
            val fetchesBefore = client.fetches.get()
            val busyMillis = measureTimeMillis {
                repository.selectProfile(SECOND_PROFILE, ViewFilter(), ChartRange.Year, model)
            }

            println(
                buildString {
                    appendLine("Profile switch under refresh probe (DEF-08, suspect 2)")
                    appendLine("Blocking fetch: $FETCH_MILLIS ms. Profile: $SMALL_PROFILE -> $SECOND_PROFILE.")
                    appendLine("Switch with nothing in flight: $idleMillis ms")
                    appendLine("Switch with fetches in flight: $busyMillis ms")
                    appendLine("Fetches started before the switch: $fetchesBefore")
                },
            )
        } finally {
            stop(repository, store)
        }
    }

    /**
     * The defect itself, as a test: a switch must not cost a network call the user did not ask for.
     *
     * The probe above measured 44 ms with nothing in flight and 1 165 ms with fetches of 600 ms
     * running, so the ceiling below is one whole fetch — far above what the local work of a switch
     * costs and far below what a joined fetch costs. Against a phone on a slow network the same
     * wait is seconds, because it is one Yahoo call, whatever that call takes.
     */
    @Test
    fun a_switch_does_not_wait_for_the_fetches_of_the_profile_it_leaves() = runBlocking {
        val store = SQLiteStateStore(context, databaseFileName = BENCH_DB_NAME)
        val repository = repository(store, BlockingYahooClient())
        try {

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.selectProfile(SMALL_PROFILE, ViewFilter(), ChartRange.Year, model)
            delay(SETTLE_MILLIS)
            val switchMillis = measureTimeMillis {
                repository.selectProfile(SECOND_PROFILE, ViewFilter(), ChartRange.Year, model)
            }

            assertTrue(
                "the switch took $switchMillis ms, about the $FETCH_MILLIS ms of the fetch it joined",
                switchMillis < FETCH_MILLIS,
            )
        } finally {
            stop(repository, store)
        }
    }

    /**
     * The first row a refresh brings back has to reach the screen when it lands.
     *
     * A batch of eight is right for the middle of a round and wrong at its start: it holds the
     * first result until seven more arrive. Over a network that is seconds of a screen that shows
     * nothing new, which is symptom 3 of DEF-08 — "the refresh takes a long time to start".
     *
     * Here one symbol answers and the rest hold their thread, so the round can never fill a batch.
     * The count is read before that symbol lands and after it, and the difference is the row.
     */
    @Test
    fun the_first_refreshed_row_is_published_before_the_batch_is_full() = runBlocking {
        val store = SQLiteStateStore(context, databaseFileName = BENCH_DB_NAME)
        val client = OneFastRestBlockedClient()
        val repository = repository(store, client)
        try {
            val updates = AtomicLong(0)
            val watcher = launch { repository.observeUpdates().collect { updates.set(it) } }

            repository.selectProfile(SMALL_PROFILE, ViewFilter(), ChartRange.Year, model)
            delay(SETTLE_MILLIS)
            val afterSwitch = updates.get()
            delay(SETTLE_MILLIS * 8)
            val afterFirstRow = updates.get()
            watcher.cancel()

            assertTrue(
                "the first row applied no update: $afterSwitch then $afterFirstRow " +
                    "(fetches ${client.fetches.get()}, rows " +
                    repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
                        .trackedRows.count { it.state == TrackedRowState.Live } + ")",
                afterFirstRow > afterSwitch,
            )
        } finally {
            stop(repository, store)
        }
    }

    /**
     * The switch no longer waits for the fetches it leaves, so those fetches now run beside the new
     * profile. Nothing they bring back may land in it.
     *
     * What holds the property was measured, not assumed. With the cancel and the deferred join both
     * removed, so the abandoned round truly runs to its end beside the new profile:
     * - generation guard removed as well: the list came back
     *   `[T, CI, AAPL, AMZN, BMA.BA, GGAL.BA, YPFD.BA, TECO2.BA]` — four symbols of the profile the
     *   user left, sitting in the one they are looking at.
     * - generation guard kept: clean. The guard alone carries it.
     *
     * The reading goes through the candidate rows, which the engine fills from every symbol it
     * holds. Tracked rows come from the profile list and would look clean either way, so they
     * cannot answer this question. The filter query keeps the reading to the one symbol.
     */
    @Test
    fun the_fetches_of_the_profile_left_behind_bring_nothing_into_the_new_one() = runBlocking {
        var store = SQLiteStateStore(context, databaseFileName = BENCH_DB_NAME)
        var repository = repository(store, BlockingYahooClient())
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.selectProfile(SECOND_PROFILE, ViewFilter(), ChartRange.Year, model)

            // Back to the small profile: its refresh starts and its first fetches take the thread.
            repository.selectProfile(SMALL_PROFILE, ViewFilter(), ChartRange.Year, model)
            delay(SETTLE_MILLIS)
            repository.selectProfile(SECOND_PROFILE, ViewFilter(), ChartRange.Year, model)
            // Long enough for every fetch of the abandoned round to return and try to apply.
            delay(FETCH_MILLIS * 2)

            var leaked = repository
                .currentSnapshot(ViewFilter(query = LEFT_BEHIND_SYMBOL), null, ChartRange.Year, model)
                .candidateRows
                .map { it.symbol }

            assertEquals(emptyList<String>(), leaked)
        } finally {
            stop(repository, store)
        }
    }

    /**
     * Leaves nothing of this test running.
     *
     * Robolectric drops its database connections the moment a test returns, so any coroutine of
     * this class still alive then fails on a pointer that no longer exists — and the suite reports
     * that against whichever class runs next. Cancelling once is not enough: a fetch holds its
     * thread for [BLOCKED_MILLIS] whatever is asked of it, and the enrichment that a cancelled
     * refresh starts is born after the first cancel. So: cancel, wait out the longest fetch,
     * cancel again.
     */
    private suspend fun stop(repository: DefaultDashboardRepository, store: SQLiteStateStore) {
        runCatching { repository.clearAllData() }
        delay(BLOCKED_MILLIS + SETTLE_MILLIS)
        runCatching { repository.clearAllData() }
        store.close()
    }

    private fun repository(store: SQLiteStateStore, client: YahooFinanceClient) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = client,
        universeCatalog = UniverseCatalog(context.assets),
        nowProvider = { 1_700_000_000L },
        defaultProfile = SMALL_PROFILE,
    )

    private fun deleteBenchFiles() {
        val base = context.getDatabasePath(BENCH_DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    /**
     * Holds its calling thread for [FETCH_MILLIS], the way a socket read holds it.
     *
     * `Thread.sleep` on purpose. Cancelling the coroutine around it changes nothing, which is the
     * property under measurement.
     */
    private open class BlockingYahooClient : YahooFinanceClient(httpClient = offlineHttpClient()) {
        val fetches = AtomicInteger(0)

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            fetches.incrementAndGet()
            Thread.sleep(FETCH_MILLIS)
            return fastResult(symbol)
        }

        protected fun fastResult(symbol: String): ProviderFetchResult {
            val price = 10_000L + symbol.sumOf { it.code }.toLong()
            val fair = price + 2_500L
            return ProviderFetchResult(
                symbol = symbol,
                snapshot = MarketSnapshot(
                    symbol = symbol,
                    companyName = "$symbol Holdings",
                    profitable = true,
                    marketPriceCents = price,
                    intrinsicValueCents = fair,
                ),
                companyName = "$symbol Holdings",
                externalSignal = ExternalValuationSignal(symbol = symbol, fairValueCents = fair, ageSeconds = 0),
                fundamentals = FundamentalSnapshot(
                    symbol = symbol,
                    marketCapDollars = 100_000_000_000L,
                    sharesOutstanding = 1_000_000_000L,
                    betaMillis = 1_000,
                ),
                coverage = ProviderCoverage(
                    core = ProviderComponentState.Fresh,
                    external = ProviderComponentState.Fresh,
                    fundamentals = ProviderComponentState.Fresh,
                ),
                diagnostics = emptyList(),
            )
        }

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
            Thread.sleep(FETCH_MILLIS)
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
            Thread.sleep(FETCH_MILLIS)
            return FundamentalTimeseries()
        }
    }

    /**
     * One symbol answers at once; every other call holds its thread past the end of the test.
     *
     * The point is a round that applies exactly one result, so a batch of eight can never be
     * filled and any published update belongs to that one row.
     */
    private class OneFastRestBlockedClient : BlockingYahooClient() {

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            val order = fetches.getAndIncrement()
            Thread.sleep(if (order == 0) FIRST_ROW_MILLIS else BLOCKED_MILLIS)
            return fastResult(symbol)
        }

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> =
            emptyList()

        override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries =
            FundamentalTimeseries()
    }

    companion object {
        private const val BENCH_DB_NAME = "profile_switch_probe.sqlite3"
        private const val SMALL_PROFILE = "qa"
        private const val SECOND_PROFILE = "merval"

        /** In [SMALL_PROFILE] and in no symbol of [SECOND_PROFILE], so its presence has one cause. */
        private const val LEFT_BEHIND_SYMBOL = "AMZN"
        private const val FETCH_MILLIS = 600L
        private const val FIRST_ROW_MILLIS = 400L
        private const val BLOCKED_MILLIS = 1_200L
        private const val SETTLE_MILLIS = 100L
    }
}
