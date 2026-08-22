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
 * What a second launch of the same day costs on the wire.
 *
 * The first launch loads a profile cold, over the shipped client and a counting server. The second
 * launch opens a new repository on the same database, minutes later by the clock the repository
 * reads, and asks for a plain refresh, the one the app asks for on its own at start. Every row is
 * on file from the first launch and none of it is a day old, so the second launch owes Yahoo the
 * batch price of the list and nothing per symbol: no `quoteSummary`, no chart, no fundamentals
 * timeseries. Only the Refresh button, a forced refresh, buys those again inside the day.
 */
@RunWith(RobolectricTestRunner::class)
class WarmLaunchCostProbeTest {
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
    fun a_second_launch_of_the_day_spends_no_call_per_symbol() = runBlocking {
        var store = SQLiteStateStore(context, databaseFileName = PROBE_DB_NAME)
        var firstHttp = CountingYahooHttp()
        var first = repository(store, firstHttp, CountingSecProvider(), FIRST_LAUNCH_EPOCH)
        var secondHttp = CountingYahooHttp()
        var second = repository(store, secondHttp, CountingSecProvider(), SECOND_LAUNCH_EPOCH)
        try {
            first.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            first.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            awaitQuiet(firstHttp)

            second.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            second.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            awaitQuiet(secondHttp)

            println(
                buildString {
                    appendLine("Warm launch cost probe: $PROBE_SYMBOLS symbols on '$PROBE_PROFILE'")
                    appendLine("First launch, cold:")
                    firstHttp.calls.toSortedMap().forEach { (kind, count) -> appendLine("  $kind: ${count.get()}") }
                    appendLine("Second launch, ${(SECOND_LAUNCH_EPOCH - FIRST_LAUNCH_EPOCH) / 60} minutes later:")
                    secondHttp.calls.toSortedMap().forEach { (kind, count) -> appendLine("  $kind: ${count.get()}") }
                },
            )

            // Batch calls made; quoteSummary, chart and timeseries calls.
            assertEquals(
                listOf(true, 0, 0, 0),
                listOf(
                    secondHttp.count(CountingYahooHttp.QUOTE_BATCH) > 0,
                    secondHttp.count(CountingYahooHttp.QUOTE_SUMMARY),
                    secondHttp.count(CountingYahooHttp.CHART),
                    secondHttp.count(CountingYahooHttp.TIMESERIES),
                ),
            )
        } finally {
            runCatching { second.clearAllData() }
            delay(SETTLE_MILLIS)
            runCatching { second.clearAllData() }
            store.close()
        }
    }

    /** Waits until the server has been quiet for [QUIET_TICKS] ticks, so late passes are counted too. */
    private suspend fun awaitQuiet(http: CountingYahooHttp) {
        var quiet = 0
        var lastSeen = -1
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (quiet < QUIET_TICKS && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
            var seen = http.total()
            quiet = if (seen == lastSeen && seen > 0) quiet + 1 else 0
            lastSeen = seen
        }
    }

    private fun repository(
        store: SQLiteStateStore,
        http: CountingYahooHttp,
        sec: CountingSecProvider,
        nowEpochSeconds: Long,
    ) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = YahooFinanceClient(httpClient = http.client),
        universeCatalog = UniverseCatalog(context.assets),
        secondaryTimeseriesProvider = sec,
        nowProvider = { nowEpochSeconds },
        defaultProfile = PROBE_PROFILE,
    )

    private fun deleteFiles() {
        var base = context.getDatabasePath(PROBE_DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    companion object {
        private const val PROBE_DB_NAME = "warm_launch_cost_probe.sqlite3"
        private const val PROBE_PROFILE = "qa"
        private const val PROBE_SYMBOLS = 20
        private const val FIRST_LAUNCH_EPOCH = 1_700_000_000L
        private const val SECOND_LAUNCH_EPOCH = FIRST_LAUNCH_EPOCH + 3 * 60 * 60L
        private const val POLL_MILLIS = 50L
        private const val QUIET_TICKS = 8
        private const val DEADLINE_MILLIS = 120_000L
        private const val SETTLE_MILLIS = 300L
    }
}
