package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What one snapshot pays for the sector levels the V4 model scores against.
 *
 * The table is one walk over every ingested detail, so each pass that scores builds it once,
 * above its own loop over rows. A build moved inside that loop reads the same and costs the whole
 * universe per row: on the 1 937-symbol universe one profile plan board built it about 1 900
 * times, 3.7 million detail reads and 18 s of a two-core device, all of it under the lock the
 * refresh needs to apply its next result.
 *
 * The compiler holds one half of this: the scoring call takes the table as a plain parameter with
 * no default, so a caller inside a loop has to name where its table came from. Nothing in the
 * shape of the code says whether that name was hoisted, so the builds are counted here.
 *
 * The count is read against two profiles rather than against a number written down. A number
 * would pin today's passes and go red on an honest new one; the rule is that the count belongs to
 * the passes and not to the profile, and only a build inside a row loop makes the two come apart.
 *
 * Measured: today both profiles build 2 tables, one for the Opportunities list and one for the
 * profile plan board. With the build put back inside the plan board loop they read 22 and 227,
 * the size of each profile plus those 2. The gap the test has to see is 205.
 */
@RunWith(RobolectricTestRunner::class)
class SectorBenchmarkCostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = OpportunityScoringModel.AggressiveV4
    private val open = mutableListOf<Pair<DefaultDashboardRepository, SQLiteStateStore>>()

    @After
    fun tearDown() {
        runBlocking {
            open.reversed().forEach { (repository, _) -> runCatching { repository.clearAllData() } }
            delay(SETTLE_MILLIS)
        }
        open.forEach { (_, store) -> store.close() }
        open.forEach { (_, store) -> deleteFiles(store.databaseName) }
    }

    @Test
    fun the_sector_table_count_does_not_read_the_size_of_the_profile() = runBlocking {
        assertEquals(buildsForOneSnapshot(SMALL_PROFILE), buildsForOneSnapshot(LARGE_PROFILE))
    }

    /** The tables one settled snapshot builds. Settled, because the boards are skipped under load. */
    private suspend fun buildsForOneSnapshot(profile: String): Int {
        var repository = settledRepository(profile)
        var before = repository.peekSectorBenchmarkBuilds()
        repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
        return repository.peekSectorBenchmarkBuilds() - before
    }

    private suspend fun settledRepository(profile: String): DefaultDashboardRepository {
        var dbName = "sector_benchmark_cost_$profile.sqlite3"
        deleteFiles(dbName)
        var store = SQLiteStateStore(context, databaseFileName = dbName)
        var repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = OfflineYahooClient(candlesPerChart = CANDLES),
            universeCatalog = UniverseCatalog(context.assets),
            nowProvider = { NOW_EPOCH },
            defaultProfile = profile,
        )
        open += repository to store
        repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
        repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
        awaitSettled(repository)
        return repository
    }

    /**
     * Ready alone is not enough: the boards are skipped while a load runs, so a snapshot taken
     * then builds fewer tables and the count would read the timing instead of the code.
     */
    private suspend fun awaitSettled(repository: DefaultDashboardRepository) {
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (true) {
            var snapshot = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
            if (snapshot.startupPhase == DashboardStartupPhase.Ready && !repository.loadInFlight.value) return
            if (System.currentTimeMillis() >= deadline) {
                fail("Timed out waiting for a settled load; last phase=${snapshot.startupPhase}")
            }
            delay(POLL_MILLIS)
        }
    }

    private fun deleteFiles(dbName: String) {
        var base = context.getDatabasePath(dbName)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    private companion object {
        /** Twenty symbols against two hundred and twenty-five: a per-row build cannot match both. */
        const val SMALL_PROFILE = "qa"
        const val LARGE_PROFILE = "nikkei"
        const val CANDLES = 5
        const val NOW_EPOCH = 1_700_000_000L
        const val POLL_MILLIS = 50L
        const val DEADLINE_MILLIS = 120_000L
        const val SETTLE_MILLIS = 300L
    }
}
