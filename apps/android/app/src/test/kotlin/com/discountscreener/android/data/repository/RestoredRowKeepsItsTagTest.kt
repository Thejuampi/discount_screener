package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.domain.model.decisionTagIsCurrent
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A row read back from the database still carries a tag.
 *
 * Reported from the device: open the app, the snapshot of the profile comes back from disk, and
 * every row says Stale with no Act, Watch or Avoid beside it. The numbers that made those tags are
 * on file — the same qualification, confidence and upside the refresh filed — so the screen threw
 * away the last thing the app decided about each name and offered nothing in its place. The tag now
 * survives the restore and is drawn faded, which says the same "not from this refresh" the missing
 * tag was trying to say, and still tells the user what the row was.
 *
 * The claim is that the tag survives, and not that it is the same word. A stale row has its
 * confidence lowered on purpose, so a name that read Act against a fresh quote can read Watch when
 * it comes back from disk. That rule is older than this one and is left alone: it is the app
 * refusing to say Act about a price it has not checked today.
 *
 * The three tests hold each other up, because the failure worth catching here is a quiet one. On
 * its own, "no row lost its tag" passes when no row ever had one, so one test refuses a filed
 * screen with nothing on it, and one refuses a restored screen that came back current, where
 * nothing was at stake. The rule is only proved by the three together.
 *
 * The rule had two copies and both are read from here. The app keeps one for the rows it assembles,
 * and `ScreenDataProjectionEngine` keeps the one the restored screen actually renders. Fixing the
 * app copy alone moved nothing on this test, which is how the second copy was found.
 */
@RunWith(RobolectricTestRunner::class)
class RestoredRowKeepsItsTagTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = OpportunityScoringModel.Legacy
    private lateinit var store: SQLiteStateStore
    private val open = mutableListOf<DefaultDashboardRepository>()

    @Before
    fun setUp() {
        deleteFiles()
        store = SQLiteStateStore(context, databaseFileName = DB_NAME)
    }

    @After
    fun tearDown() {
        runBlocking {
            open.reversed().forEach { repository -> runCatching { repository.clearAllData() } }
            delay(SETTLE_MILLIS)
        }
        store.close()
        deleteFiles()
    }

    /** Without this the property below passes on a screen where no row ever had a tag to lose. */
    @Test
    fun the_first_start_files_a_tag_on_some_row() = runBlocking {
        var runs = restart()

        assertNotEquals(emptyList<String>(), taggedSymbols(runs.filed))
    }

    /** And without this it passes on a screen that came back current, where nothing was at stake. */
    @Test
    fun no_restored_row_reads_as_current() = runBlocking {
        var runs = restart()

        assertEquals(emptyList<String>(), runs.restored.filter { decisionTagIsCurrent(it.freshness) }.map { it.symbol })
    }

    @Test
    fun no_row_that_had_a_tag_comes_back_from_disk_without_one() = runBlocking {
        var runs = restart()
        var hadOne = taggedSymbols(runs.filed).toSet()

        assertEquals(emptyList<String>(), runs.restored.filter { it.symbol in hadOne && it.tag == null }.map { it.symbol })
    }

    /**
     * One refresh filed to the store, then the app opened again over that same store and read
     * before it fetches anything. One store and two repositories: the second start sees exactly the
     * rows the first one left behind, which is the screen the user opens on.
     */
    private suspend fun restart(): Restart {
        var first = launch()
        first.refreshAll(ViewFilter(), null, ChartRange.Year, model)
        awaitSettled(first)
        var filed = rowsOf(first)

        return Restart(filed = filed, restored = rowsOf(launch()))
    }

    private suspend fun rowsOf(repository: DefaultDashboardRepository): List<Row> =
        repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
            .trackedRows
            .map { Row(it.symbol, it.freshness, it.decisionState) }

    private fun taggedSymbols(rows: List<Row>): List<String> = rows.filter { it.tag != null }.map { it.symbol }

    private suspend fun launch(): DefaultDashboardRepository {
        var repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = WideGapYahooClient(),
            universeCatalog = UniverseCatalog(context.assets),
            secondaryTimeseriesProvider = CountingSecProvider(),
            nowProvider = { NOW_EPOCH },
            defaultProfile = PROFILE,
        )
        open += repository
        repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
        return repository
    }

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

    private fun deleteFiles() {
        var base = context.getDatabasePath(DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    private data class Row(val symbol: String, val freshness: RowFreshness, val tag: RowDecisionState?)

    private data class Restart(val filed: List<Row>, val restored: List<Row>)

    private companion object {
        const val DB_NAME = "restored_row_keeps_its_tag.sqlite3"
        const val PROFILE = "qa"
        const val NOW_EPOCH = 1_700_000_000L
        const val POLL_MILLIS = 50L
        const val DEADLINE_MILLIS = 120_000L
        const val SETTLE_MILLIS = 300L
    }
}
