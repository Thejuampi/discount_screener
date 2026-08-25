package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the screen pays for each update while a full profile loads.
 *
 * The view model reacts to every `updates` tick with a whole snapshot, built under `stateMutex`.
 * A snapshot is O(symbols): the plan board walks every row's year of candles. A load ticks once
 * per batch of rows, so the local cost of a load is snapshots times symbols, and neither of the
 * earlier benches saw it: they ran twenty symbols with one candle each.
 *
 * Here the profile is the largest the app ships and every chart is a year of daily candles. The
 * collector does what the view model does, `collectLatest` over the updates. It prints how many
 * snapshots were built, what one costs, and how many were thrown away before a render.
 */
@RunWith(RobolectricTestRunner::class)
class SnapshotCostBenchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = OpportunityScoringModel.Legacy
    private val logger = StageRecordingLogger()

    @Before
    fun setUp() {
        deleteFiles()
    }

    @After
    fun tearDown() {
        deleteFiles()
    }

    @Test
    fun a_full_load_reports_what_its_snapshots_cost() = runBlocking {
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var repository = repository(store)
        var ticks = AtomicInteger()
        var rendered = AtomicInteger()
        var collector: Job? = null
        try {
            var started = System.currentTimeMillis()
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            var bootstrapMillis = System.currentTimeMillis() - started
            collector = launch {
                repository.observeUpdates().collectLatest {
                    ticks.incrementAndGet()
                    repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
                    rendered.incrementAndGet()
                }
            }
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            awaitQuiet(repository)
            var whole = System.currentTimeMillis() - started

            var samples = logger.stageSamples()
            var builds = samples["snapshot.build"].orEmpty().sorted()
            var report = buildString {
                appendLine("Snapshot cost bench: '$PROFILE', $CANDLES candles per chart, offline provider")
                appendLine("| Reading | Value |")
                appendLine("| --- | --- |")
                appendLine("| bootstrap ms | $bootstrapMillis |")
                appendLine("| whole load ms | $whole |")
                appendLine("| refresh round ms | ${samples["refresh.round"]?.firstOrNull() ?: "-"} |")
                appendLine("| update ticks seen | ${ticks.get()} |")
                appendLine("| snapshots built | ${builds.size} |")
                appendLine("| snapshots rendered | ${rendered.get()} |")
                appendLine("| snapshot ms, fastest / middle / slowest | ${builds.firstOrNull()} / ${builds.getOrNull(builds.size / 2)} / ${builds.lastOrNull()} |")
                appendLine("| snapshot ms, sum | ${builds.sum()} |")
                appendLine("| symbols fetched | ${samples["refresh.symbol"].orEmpty().size} |")
                listOf(
                    "refresh.symbol", "refresh.apply", "refresh.persist",
                    "snapshot.candidates", "snapshot.score", "snapshot.request", "snapshot.project", "snapshot.trackedRows", "snapshot.opportunityRows", "snapshot.planBoard", "snapshot.planBoardProfile", "snapshot.leftoverBoard", "snapshot.crossBoard", "snapshot.crossBoardProfile",
                ).forEach { stage ->
                    var values = samples[stage].orEmpty().sorted()
                    appendLine("| $stage ms, sum / middle / slowest | ${values.sum()} / ${values.getOrNull(values.size / 2)} / ${values.lastOrNull()} |")
                }
            }
            println(report)
            runCatching { File(context.cacheDir, "snapshot-cost-bench-report.txt").writeText(report) }

            assertTrue("the load built no snapshot", builds.isNotEmpty())
        } finally {
            collector?.cancel()
            runCatching { repository.clearAllData() }
            delay(SETTLE_MILLIS)
            runCatching { repository.clearAllData() }
            store.close()
        }
    }

    /** Waits until the refresh stops applying symbols, so late passes are counted too. */
    private suspend fun awaitQuiet(repository: DefaultDashboardRepository) {
        var quiet = 0
        var lastSeen = -1
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (quiet < QUIET_TICKS && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
            var seen = logger.stageSamples()["refresh.symbol"].orEmpty().size
            quiet = if (seen == lastSeen && seen > 0 && !repository.loadInFlight.value) quiet + 1 else 0
            lastSeen = seen
        }
    }

    private fun repository(store: SQLiteStateStore) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = OfflineYahooClient(candlesPerChart = CANDLES),
        universeCatalog = UniverseCatalog(context.assets),
        nowProvider = { 1_700_000_000L },
        defaultProfile = PROFILE,
        logger = logger,
    )

    private fun deleteFiles() {
        var base = context.getDatabasePath(DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    companion object {
        private const val DB_NAME = "snapshot_cost_bench.sqlite3"
        private const val PROFILE = "sp500"
        private const val CANDLES = 250
        private const val POLL_MILLIS = 100L
        private const val QUIET_TICKS = 10
        private const val DEADLINE_MILLIS = 300_000L
        private const val SETTLE_MILLIS = 300L
    }
}
