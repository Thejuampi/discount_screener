package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
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

            assertEquals(0, snapshot.planBoardProfile.scanned)
        } finally {
            runCatching { repository.clearAllData() }
            delay(SETTLE_MILLIS)
            store.close()
        }
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
    }
}
