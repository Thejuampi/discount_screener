package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A load snapshot must not scan the profile plan board.
 *
 * That scan scores every name the Opportunities list dropped, under the same mutex the refresh
 * uses. On `sp500` that was 1.4 s per tick, every eight rows, and the list stopped moving.
 */
@RunWith(RobolectricTestRunner::class)
class LoadSnapshotBoardTest {
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
    fun a_load_in_flight_does_not_scan_the_profile_plan_board() = runBlocking {
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = LimitedYahooClient(latencyMillis = SLOW_MILLIS),
            universeCatalog = UniverseCatalog(context.assets),
            nowProvider = { 1_700_000_000L },
            defaultProfile = PROFILE,
        )
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            delay(MID_LOAD_MILLIS)
            var snapshot = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)

            assertNull(snapshot.planBoardProfile)
        } finally {
            runCatching { repository.clearAllData() }
            delay(SETTLE_MILLIS)
            store.close()
        }
    }

    /**
     * The gate used to read `loadRunning`, which also counts the enrichment. The enrichment fetches
     * statements for every name and runs for minutes after the prices land, so on `sp500` the
     * boards were never built at all and the Plans screen stayed blocked.
     */
    @Test
    fun the_profile_board_is_built_while_the_enrichment_still_runs() = runBlocking {
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = LimitedYahooClient(latencyMillis = ENRICHMENT_MILLIS),
            universeCatalog = UniverseCatalog(context.assets),
            nowProvider = { 1_700_000_000L },
            defaultProfile = PROFILE,
        )
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)

            assertNotNull(afterRefreshWithEnrichmentRunning(repository).planBoardProfile)
        } finally {
            runCatching { repository.clearAllData() }
            delay(SETTLE_MILLIS)
            store.close()
        }
    }

    /**
     * The enrichment emits a snapshot every few rows and runs for minutes. A board build costs
     * about 1.4 s on `sp500`, under the mutex the refresh needs, so one build per emit added about
     * 2 s to the load and pushed `LoadCostProbeTest` past its twenty seconds.
     *
     * The readout carries both facts. `snapshots` says the refresh built many snapshots, so a
     * single board build is a held build. Without it the test would pass on a load that ticked once.
     */
    @Test
    fun the_enrichment_builds_the_profile_board_once() = runBlocking {
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var logger = StageRecordingLogger()
        var repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = LimitedYahooClient(latencyMillis = ENRICHMENT_MILLIS),
            universeCatalog = UniverseCatalog(context.assets),
            nowProvider = { 1_700_000_000L },
            defaultProfile = PROFILE,
            logger = logger,
        )
        var collector: Job? = null
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            collector = launch {
                repository.observeUpdates().collectLatest {
                    if (repository.loadInFlight.value) {
                        repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
                    }
                }
            }
            logger.clear()
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)

            assertEquals("snapshots=true boardBuildsAtMostOne=true", duringLoad(repository, logger))
        } finally {
            collector?.cancel()
            runCatching { repository.clearAllData() }
            delay(SETTLE_MILLIS)
            store.close()
        }
    }

    /**
     * What the load paid for, read while it still runs. A build that lands after the load ends is
     * a build the screen waited for nothing, so only the readings taken in flight are counted.
     */
    private suspend fun duringLoad(
        repository: DefaultDashboardRepository,
        logger: StageRecordingLogger,
    ): String {
        var readout = ""
        var waited = 0L
        while (waited < WINDOW_TIMEOUT_MILLIS && repository.loadInFlight.value) {
            var samples = logger.stageSamples()
            var snapshots = samples["snapshot.build"].orEmpty().size
            var boards = samples["snapshot.planBoardProfile"].orEmpty().size
            readout = "snapshots=${snapshots > 1} boardBuildsAtMostOne=${boards <= 1}"
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        return readout
    }

    /**
     * The snapshot at the one moment this test is about: the prices have landed and the enrichment
     * is still counted in. It fails when that moment never comes, so the test cannot pass on a run
     * that never reached the state it grades.
     */
    private suspend fun afterRefreshWithEnrichmentRunning(
        repository: DefaultDashboardRepository,
    ): DashboardSnapshot {
        var waited = 0L
        while (waited < WINDOW_TIMEOUT_MILLIS) {
            var snapshot = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
            if (snapshot.startupPhase == DashboardStartupPhase.Ready && repository.loadInFlight.value) {
                return snapshot
            }
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        throw AssertionError("The refresh never ended with the enrichment still running.")
    }

    private fun deleteFiles() {
        var base = context.getDatabasePath(DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    private companion object {
        private const val DB_NAME = "load_snapshot_board.sqlite3"
        private const val PROFILE = "qa"
        private const val SLOW_MILLIS = 80L
        private const val MID_LOAD_MILLIS = 120L
        private const val SETTLE_MILLIS = 300L

        /** Slow enough that the enrichment is still counted in when the prices have landed. */
        private const val ENRICHMENT_MILLIS = 200L
        private const val POLL_MILLIS = 20L
        private const val WINDOW_TIMEOUT_MILLIS = 20_000L
    }
}
