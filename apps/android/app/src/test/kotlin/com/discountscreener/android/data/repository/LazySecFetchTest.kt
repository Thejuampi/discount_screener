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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * SEC EDGAR is asked for one symbol at a time, and only when that symbol is opened.
 *
 * One symbol there costs a whole companyfacts file: 4.28 MB on average over twenty real files, of
 * which the sieve keeps about 3%. Paid once per symbol on a bulk load, that file was 77% of the load in
 * the `sp500` projection — about 2.1 GB downloaded, written and read back for one screen. No
 * concurrency window can remove a call the code makes, so the call moves instead.
 *
 * The list therefore resolves its DCF from the cheap Yahoo timeseries, which keeps every row's
 * score. Opening a symbol is the first moment one symbol is worth one file.
 */
@RunWith(RobolectricTestRunner::class)
class LazySecFetchTest {
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
    fun a_first_load_downloads_no_companyfacts_file() = runBlocking {
        var http = CountingYahooHttp()
        var sec = CountingSecProvider(latencyMillis = 0)
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var repository = repository(store, http, sec)
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            awaitQuiet(http, sec)

            assertEquals("the bulk load reached SEC: ${sec.symbols}", 0, sec.calls.get())
        } finally {
            stop(repository, store)
        }
    }

    /**
     * Twice, because a symbol SEC has nothing for answers null and leaves the cached analysis on
     * its Yahoo source. Without a record of the attempt, every later open would pay the file again.
     */
    @Test
    fun opening_a_symbol_downloads_its_companyfacts_file_once() = runBlocking {
        var http = CountingYahooHttp()
        var sec = CountingSecProvider(latencyMillis = 0)
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var repository = repository(store, http, sec)
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            awaitQuiet(http, sec)

            repository.ensureDetailLoaded(OPENED_SYMBOL, ViewFilter(), ChartRange.Year, model)
            repository.ensureDetailLoaded(OPENED_SYMBOL, ViewFilter(), ChartRange.Year, model)

            assertEquals("SEC was asked for ${sec.symbols}", 1, sec.calls.get())
        } finally {
            stop(repository, store)
        }
    }

    /** Waits until the server has been quiet for [QUIET_TICKS] ticks, so late passes are counted too. */
    private suspend fun awaitQuiet(http: CountingYahooHttp, sec: CountingSecProvider) {
        var quiet = 0
        var lastSeen = -1
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (quiet < QUIET_TICKS && System.currentTimeMillis() < deadline) {
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
        http: CountingYahooHttp,
        sec: CountingSecProvider,
    ) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = YahooFinanceClient(httpClient = http.client),
        universeCatalog = UniverseCatalog(context.assets),
        secondaryTimeseriesProvider = sec,
        nowProvider = { 1_700_000_000L },
        defaultProfile = PROFILE,
    )

    private fun deleteFiles() {
        var base = context.getDatabasePath(DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    companion object {
        private const val DB_NAME = "lazy_sec_fetch.sqlite3"
        private const val PROFILE = "qa"
        private const val OPENED_SYMBOL = "AAPL"
        private const val POLL_MILLIS = 50L
        private const val QUIET_TICKS = 8
        private const val DEADLINE_MILLIS = 120_000L
        private const val SETTLE_MILLIS = 300L
    }
}
