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
 * The load has to say it is running, because that reading is the only thing holding the process up
 * once the user leaves the app.
 *
 * A signal that were always false would let Android freeze the load, and a signal that were always
 * true would keep a notification on the screen forever. Both readings are taken here.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundLoadSignalTest {
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
    fun a_load_that_is_still_fetching_says_it_is_running() = runBlocking {
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var repository = repository(store)
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            delay(MID_LOAD_MILLIS)

            assertEquals(true, repository.loadInFlight.value)
        } finally {
            stop(repository, store)
        }
    }

    @Test
    fun a_load_that_has_no_symbols_left_says_it_is_over() = runBlocking {
        var store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        var repository = repository(store)
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model)
            awaitIdle(repository)

            assertEquals(false, repository.loadInFlight.value)
        } finally {
            stop(repository, store)
        }
    }

    private suspend fun awaitIdle(repository: DefaultDashboardRepository) {
        var deadline = System.currentTimeMillis() + IDLE_DEADLINE_MILLIS
        while (repository.loadInFlight.value && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
        }
    }

    private suspend fun stop(repository: DefaultDashboardRepository, store: SQLiteStateStore) {
        runCatching { repository.clearAllData() }
        delay(SETTLE_MILLIS)
        runCatching { repository.clearAllData() }
        store.close()
    }

    private fun repository(store: SQLiteStateStore) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = LimitedYahooClient(latencyMillis = SLOW_MILLIS),
        universeCatalog = UniverseCatalog(context.assets),
        nowProvider = { 1_700_000_000L },
        defaultProfile = PROFILE,
    )

    private fun deleteFiles() {
        var base = context.getDatabasePath(DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    private companion object {
        private const val DB_NAME = "background_load_signal.sqlite3"
        private const val PROFILE = "qa"

        /** Long enough per call that the load is certainly still running when it is read. */
        private const val SLOW_MILLIS = 60L
        private const val MID_LOAD_MILLIS = 120L
        private const val POLL_MILLIS = 50L
        private const val IDLE_DEADLINE_MILLIS = 60_000L
        private const val SETTLE_MILLIS = 300L
    }
}
